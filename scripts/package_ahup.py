#!/usr/bin/env python3
"""Build signed AHUTong v3 archives (Python 3.8+, JDK 17+, Android SDK).

Input: AAR, AGP-built plugin APK, or prepared payload directory.
Release signing is mandatory. Credentials come from AHUP_SIGNING_CONFIG or
AHUP_KEYSTORE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD environment variables.
The Java signer receives secrets on stdin, never in process arguments.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

SIGNER = Path(__file__).with_name("AhupSign.java")
PLUGIN_ID = re.compile(r"^[a-z][a-z0-9_]{1,63}$")
DEX_NAME = re.compile(r"^classes(?:[2-9]|[1-9][0-9]+)?\.dex$")
MAX_PAYLOAD_BYTES = 256 * 1024 * 1024
MAX_FILE_BYTES = 128 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_ENTRIES = 4096
RESERVED = {"manifest.json", "signature.sig"}


def safe_path(name):
    """Reject traversal and Windows aliases instead of silently normalizing."""
    if not name or "\\" in name or ":" in name or name.startswith("/"):
        raise ValueError("Unsafe archive path: %r" % name)
    parts = name.split("/")
    if any(p in ("", ".", "..") or p.endswith((".", " ")) or
           any(ord(c) < 32 for c in p) for p in parts):
        raise ValueError("Unsafe archive path: %r" % name)
    devices = {"CON", "PRN", "AUX", "NUL"}
    devices.update("COM%d" % n for n in range(1, 10))
    devices.update("LPT%d" % n for n in range(1, 10))
    if any(p.split(".")[0].upper() in devices for p in parts):
        raise ValueError("Reserved archive path: %r" % name)
    return name


def read_archive(path):
    result, names, total = {}, set(), 0
    with zipfile.ZipFile(path) as archive:
        if len(archive.infolist()) > MAX_ENTRIES:
            raise ValueError("Archive contains too many entries")
        for info in archive.infolist():
            name = safe_path(info.filename.rstrip("/"))
            if name.casefold() in names:
                raise ValueError("Duplicate archive entry: " + name)
            names.add(name.casefold())
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Symlinks are forbidden: " + name)
            if info.is_dir():
                continue
            if info.file_size > MAX_FILE_BYTES:
                raise ValueError("Archive entry exceeds the per-file size limit: " + name)
            total += info.file_size
            if total > MAX_PAYLOAD_BYTES:
                raise ValueError("Archive exceeds the unpacked size limit")
            result[name] = archive.read(info)
    return result


def put_payload(payload, name, data):
    safe_path(name)
    if len(data) > MAX_FILE_BYTES:
        raise ValueError("Payload entry exceeds the per-file size limit: " + name)
    if name in RESERVED:
        raise ValueError("Reserved payload entry: " + name)
    for existing in payload:
        if existing.casefold() == name.casefold():
            if existing == name and payload[existing] == data:
                return
            raise ValueError("Conflicting payload entry: " + name)
    payload[name] = data
    if len(payload) > MAX_ENTRIES or sum(map(len, payload.values())) > MAX_PAYLOAD_BYTES:
        raise ValueError("Plugin exceeds the package limit")


def deterministic_zip(target, entries):
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(entries.items()):
            safe_path(name)
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            if name == "resources.arsc":
                # Android loads the resource table by mmap. Keep it stored and
                # four-byte aligned after removing the APK's code/native files.
                entry.compress_type = zipfile.ZIP_STORED
                padding = (-(archive.fp.tell() + 30 + len(name.encode("utf-8")) + 4)) % 4
                entry.extra = struct.pack("<HH", 0xFFFF, padding) + bytes(padding)
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)


def default_icon(tint):
    """Dependency-free PNG; packaging does not require Pillow."""
    rgb = ((tint >> 16) & 255, (tint >> 8) & 255, tint & 255)
    rows = b"".join(b"\x00" + bytes(rgb + (255,)) * 96 for _ in range(96))
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 96, 96, 8, 6, 0, 0, 0)) +
            chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def dex_classes(data):
    """Read defined class descriptors, not merely referenced host SDK types."""
    if len(data) < 112 or not re.fullmatch(b"dex\n0[34][0-9]\x00", data[:8]):
        raise ValueError("Invalid DEX header")
    def integer(offset):
        if offset < 0 or offset + 4 > len(data):
            raise ValueError("Truncated DEX")
        return struct.unpack_from("<I", data, offset)[0]
    if integer(32) != len(data):
        raise ValueError("Invalid DEX file size")
    strings, strings_at = integer(56), integer(60)
    types, types_at = integer(64), integer(68)
    count, classes_at = integer(96), integer(100)
    if strings_at + strings * 4 > len(data) or types_at + types * 4 > len(data) or classes_at + count * 32 > len(data):
        raise ValueError("Invalid DEX tables")
    result = set()
    for index in range(count):
        type_index = integer(classes_at + index * 32)
        if type_index >= types:
            raise ValueError("Invalid DEX type reference")
        string_index = integer(types_at + type_index * 4)
        if string_index >= strings:
            raise ValueError("Invalid DEX string reference")
        offset = integer(strings_at + string_index * 4)
        for _ in range(5):
            if offset >= len(data):
                raise ValueError("Truncated DEX string")
            value = data[offset]
            offset += 1
            if value < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        end = data.find(b"\x00", offset)
        if end < 0:
            raise ValueError("Truncated DEX descriptor")
        result.add(data[offset:end].decode("utf-8"))
    return result


def java_executable(explicit=None):
    candidates = [explicit]
    if os.environ.get("JAVA_HOME"):
        candidates.append(str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")))
    candidates.append(shutil.which("java"))
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate).resolve())
    raise ValueError("JDK missing; set JAVA_HOME or --java")


def sdk_directory(module_dir, explicit=None):
    candidates = [explicit, os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_HOME")]
    for parent in [module_dir] + list(module_dir.parents):
        properties = parent / "local.properties"
        if properties.is_file():
            for line in properties.read_text(encoding="utf-8-sig").splitlines():
                if line.startswith("sdk.dir="):
                    candidates.append(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\"))
    candidates += [str(Path.home() / "AppData/Local/Android/Sdk"),
                   str(Path.home() / "Library/Android/sdk"), str(Path.home() / "Android/Sdk")]
    for candidate in candidates:
        if candidate and (Path(candidate) / "build-tools").is_dir():
            return Path(candidate).resolve()
    raise ValueError("Android SDK missing; set ANDROID_SDK_ROOT or --sdk")


def version_key(path):
    return tuple(int(x) for x in re.findall(r"\d+", path.name))


def signing_configuration(path=None):
    config_path = path or os.environ.get("AHUP_SIGNING_CONFIG")
    if config_path:
        config_file = Path(config_path).resolve()
        raw = json.loads(config_file.read_text(encoding="utf-8-sig"))
        if not isinstance(raw, dict):
            raise ValueError("Signing configuration must be an object")
        raw = raw.get("signing", raw)
        if not isinstance(raw, dict):
            raise ValueError("Signing configuration must be an object")
        # Existing AIO release configuration: read only the keystore object.
        if isinstance(raw.get("keystore"), dict):
            ks = raw["keystore"]
            config = {"keystore": ks.get("path"), "storePassword": ks.get("store_password"),
                      "keyAlias": ks.get("key_alias"), "keyPassword": ks.get("key_password"),
                      "storeType": ks.get("type", "JKS")}
        else:
            config = {key: raw.get(key) for key in
                      ("keystore", "storePassword", "keyAlias", "keyPassword", "storeType")}
            config["keystore"] = config["keystore"] or raw.get("keystorePath")
        keystore = config.get("keystore")
        if keystore and not Path(keystore).is_absolute():
            config["keystore"] = str(config_file.parent / keystore)
    else:
        config = {"keystore": os.environ.get("AHUP_KEYSTORE"),
                  "storePassword": os.environ.get("AHUP_STORE_PASSWORD"),
                  "keyAlias": os.environ.get("AHUP_KEY_ALIAS"),
                  "keyPassword": os.environ.get("AHUP_KEY_PASSWORD"),
                  "storeType": os.environ.get("AHUP_STORE_TYPE")}
    if not config.get("keystore"):
        return None
    config["keyPassword"] = config.get("keyPassword") or config.get("storePassword")
    config["storeType"] = config.get("storeType") or "JKS"
    if any(not isinstance(config.get(key), str) or not config[key]
           for key in ("keystore", "storePassword", "keyAlias", "keyPassword", "storeType")):
        raise ValueError("Signing configuration lacks required fields")
    if not Path(config["keystore"]).is_file():
        raise ValueError("Configured signing keystore does not exist")
    return config


def sign(java, config, manifest_path=None, signature_path=None, certificate_path=None):
    """Signer errors are redacted; secret input never reaches logs."""
    fields = ("keystore", "storePassword", "keyAlias", "keyPassword", "storeType")
    stdin = "\n".join(base64.b64encode(config[key].encode("utf-8")).decode("ascii") for key in fields) + "\n"
    args = [java, str(SIGNER)]
    if manifest_path is not None:
        args += ["sign", str(manifest_path), str(signature_path)]
    else:
        args += ["export-certificate", str(certificate_path)]
    result = subprocess.run(args, input=stdin.encode("ascii"), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise ValueError("AHUP signing failed; check JDK 17+, key alias and signing credentials")


def gather_aar(path, payload, jars, work, abis):
    needs_resources = False
    for name, data in read_archive(path).items():
        if name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
            jar = work / ("input-%d.jar" % len(jars))
            jar.write_bytes(data)
            jars.append(jar)
        elif name.startswith("assets/"):
            put_payload(payload, name, data)
        elif name.startswith("jni/") and name.endswith(".so"):
            parts = name.split("/")
            if len(parts) != 3:
                raise ValueError("Invalid AAR native library path: " + name)
            if not abis or parts[1] in abis:
                put_payload(payload, "lib/" + name[4:], data)
        elif name.startswith("res/"):
            needs_resources = True
    return needs_resources


def gather_apk(path, payload, work, abis):
    resource_entries = {}
    for name, data in read_archive(path).items():
        if DEX_NAME.fullmatch(name):
            put_payload(payload, "dex/" + name, data)
        elif name.startswith("assets/"):
            # Keep inside resources.apk as well: plugin Context.assets uses it.
            resource_entries[name] = data
            put_payload(payload, name, data)
        elif name.startswith("lib/") and name.endswith(".so"):
            parts = name.split("/")
            if len(parts) != 3:
                raise ValueError("Invalid APK native library path: " + name)
            if not abis or parts[1] in abis:
                put_payload(payload, name, data)
        elif name in ("AndroidManifest.xml", "resources.arsc") or name.startswith("res/"):
            resource_entries[name] = data
        elif not re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", name, re.IGNORECASE):
            # JVM resources such as META-INF/services belong to private
            # dependencies and must remain available through the class loader.
            resource_entries[name] = data
    if "AndroidManifest.xml" in resource_entries:
        resource_archive = work / "resources.apk"
        deterministic_zip(resource_archive, resource_entries)
        put_payload(payload, "resources.apk", resource_archive.read_bytes())


def compile_dex(args, jars, payload, work):
    if not jars:
        raise ValueError("No plugin classes found in AAR inputs")
    sdk = sdk_directory(Path(args.module_dir).resolve(), args.sdk)
    build_tools = sorted((sdk / "build-tools").glob("*/lib/d8.jar"), key=lambda p: version_key(p.parent.parent))
    platforms = sorted((sdk / "platforms").glob("android-*/android.jar"), key=lambda p: version_key(p.parent))
    if not build_tools or not platforms:
        raise ValueError("SDK must contain build-tools and an Android platform")
    output = work / "dex.zip"
    command = [java_executable(args.java), "-cp", str(build_tools[-1]), "com.android.tools.r8.D8",
               "--release", "--min-api", str(args.min_sdk), "--lib", str(platforms[-1]), "--output", str(output)]
    for classpath in args.classpath:
        command += ["--classpath", str(Path(classpath).resolve())]
    command += [str(p.resolve()) for p in jars]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise ValueError("D8 failed:\n" + result.stderr.decode("utf-8", errors="replace")[-8000:])
    for name, data in read_archive(output).items():
        if DEX_NAME.fullmatch(name):
            put_payload(payload, "dex/" + name, data)


def dependency(value):
    parts = value.split(":")
    if len(parts) not in (2, 3) or not PLUGIN_ID.fullmatch(parts[0]):
        raise argparse.ArgumentTypeError("Dependency must be id:minVersion[:maxVersion]")
    try:
        result = {"id": parts[0], "minVersion": int(parts[1])}
        if len(parts) == 3:
            result["maxVersion"] = int(parts[2])
        if result["minVersion"] < 1 or result.get("maxVersion", result["minVersion"]) < result["minVersion"]:
            raise ValueError()
        return result
    except ValueError:
        raise argparse.ArgumentTypeError("Dependency versions must be positive inclusive version codes")


def make_manifest(args, payload, signed):
    if not PLUGIN_ID.fullmatch(args.id):
        raise ValueError("Plugin id must match [a-z][a-z0-9_]{1,63}")
    if args.version_code < 1 or args.min_sdk < 26:
        raise ValueError("versionCode must be positive and minSdk at least 26")
    if not re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+", args.entry):
        raise ValueError("entryClass must be a fully qualified Java class name")
    deps = args.dependency
    if len({d["id"] for d in deps}) != len(deps) or any(d["id"] == args.id for d in deps):
        raise ValueError("Duplicate or self-referential plugin dependency")
    manifest = {"formatVersion": 3, "id": args.id, "title": args.title, "summary": args.summary,
                "tint": args.tint, "versionCode": args.version_code, "versionName": args.version,
                "version": args.version, "author": args.author, "entryClass": args.entry,
                "icon": "icon.png", "hostApiVersion": args.host_api_version,
                "runtimeVersion": args.runtime_version, "minSdk": args.min_sdk,
                "required": args.required, "release": signed,
                "homeCard": args.home_card,
                "foreground": args.foreground,
                "autoStart": args.auto_start,
                "userVisible": not args.hidden,
                "dependencies": deps, "capabilities": sorted(set(c.strip() for c in args.capabilities.split(",") if c.strip())),
                "abis": sorted({name.split("/")[1] for name in payload if name.startswith("lib/")}),
                "signatureAlgorithm": "SHA256withRSA",
                "files": [{"path": name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
                          for name, data in sorted(payload.items())]}
    return json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def package(args):
    config = signing_configuration(args.signing_config)
    if not config and not args.allow_unsigned_debug:
        raise ValueError("Release signing is required; configure AHUP_SIGNING_CONFIG (unsigned debug requires --allow-unsigned-debug)")
    if args.allow_unsigned_debug and config:
        raise ValueError("Choose signed release or unsigned debug; not both")
    source, output = Path(args.source).resolve(), Path(args.out).resolve()
    work_parent = Path(args.module_dir).resolve() / "build" / "ahup-work"
    work_parent.mkdir(parents=True, exist_ok=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    abis = {a.strip() for a in args.abi.split(",") if a.strip()}
    with tempfile.TemporaryDirectory(prefix="package-", dir=str(work_parent)) as work_dir:
        work, payload = Path(work_dir), {}
        if source.is_dir():
            for item in sorted(source.rglob("*")):
                if item.is_symlink():
                    raise ValueError("Payload cannot contain symlinks")
                if item.is_file():
                    put_payload(payload, item.relative_to(source).as_posix(), item.read_bytes())
        elif source.suffix.lower() == ".apk":
            gather_apk(source, payload, work, abis)
        else:
            jars, needs_resources = [], False
            for aar in [source] + [Path(p) for p in args.extra_aar]:
                needs_resources = gather_aar(aar, payload, jars, work, abis) or needs_resources
            jars.extend(Path(p) for p in args.extra_jar)
            if needs_resources and not args.resources_apk:
                raise ValueError("AAR contains Android resources: pass linked --resources-apk with matching R classes, or package an AGP plugin APK")
            compile_dex(args, jars, payload, work)
        if args.resources_apk:
            resource_file = Path(args.resources_apk)
            if "AndroidManifest.xml" not in read_archive(resource_file):
                raise ValueError("resources.apk must contain linked AndroidManifest.xml")
            put_payload(payload, "resources.apk", resource_file.read_bytes())
        if "dex/classes.dex" not in payload:
            raise ValueError("Plugin must contain dex/classes.dex")
        defined_classes = set()
        for name in payload:
            if name.startswith("dex/") and not DEX_NAME.fullmatch(name[4:]):
                raise ValueError("Unexpected DEX payload: " + name)
            if name.startswith("dex/"):
                classes = dex_classes(payload[name])
                if defined_classes.intersection(classes):
                    raise ValueError("Plugin contains duplicate classes across DEX files")
                defined_classes.update(classes)
        entry_descriptor = "L" + args.entry.replace(".", "/") + ";"
        if entry_descriptor not in defined_classes:
            raise ValueError("entryClass is absent from plugin DEX files: " + args.entry)
        for prefix in args.forbid_class_prefix:
            descriptor_prefix = "L" + prefix.replace(".", "/")
            if any(name.startswith(descriptor_prefix) for name in defined_classes):
                raise ValueError("Plugin bundles a forbidden host/shared class prefix: " + prefix)
        if args.icon_src:
            put_payload(payload, "icon.png", Path(args.icon_src).read_bytes())
        elif "icon.png" not in payload:
            put_payload(payload, "icon.png", default_icon(int(args.tint, 16)))
        raw_manifest = make_manifest(args, payload, config is not None)
        if len(raw_manifest) > MAX_MANIFEST_BYTES:
            raise ValueError("Plugin manifest exceeds the 1 MiB limit")
        manifest_file = work / "manifest.json"
        manifest_file.write_bytes(raw_manifest)
        signature = b""
        if config:
            signature_file = work / "signature.sig"
            sign(java_executable(args.java), config, manifest_file, signature_file)
            signature = signature_file.read_bytes()
        entries = dict(payload)
        entries.update({"manifest.json": raw_manifest, "signature.sig": signature})
        if len(entries) > MAX_ENTRIES or sum(map(len, entries.values())) > MAX_PAYLOAD_BYTES:
            raise ValueError("Signed plugin exceeds the package limit")
        descriptor, temp_name = tempfile.mkstemp(prefix=".ahup-", suffix=".tmp", dir=str(output.parent))
        os.close(descriptor)
        try:
            deterministic_zip(temp_name, entries)
            if os.path.getsize(temp_name) > MAX_PAYLOAD_BYTES:
                raise ValueError("Compressed plugin exceeds the 256 MiB limit")
            os.replace(temp_name, str(output))
        finally:
            if os.path.exists(temp_name):
                os.unlink(temp_name)
    print("Packaged %s (v3, %s)" % (output, "signed release" if config else "UNSIGNED DEBUG: production installation will reject"))


def parser():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("module_dir")
    ap.add_argument("source", help="AAR, plugin APK, or prepared payload directory")
    ap.add_argument("out")
    ap.add_argument("--id", required=True)
    ap.add_argument("--title", required=True)
    ap.add_argument("--summary", default="")
    ap.add_argument("--tint", default="0xFF607D8B")
    ap.add_argument("--version", default="1.0.0")
    ap.add_argument("--version-code", type=int, default=1)
    ap.add_argument("--author", default="AHUTong")
    ap.add_argument("--entry", required=True)
    ap.add_argument("--capabilities", default="NETWORK,PLUGIN_STORAGE")
    ap.add_argument("--dependency", type=dependency, action="append", default=[])
    ap.add_argument("--required", action="store_true")
    ap.add_argument("--home-card", action="store_true")
    ap.add_argument("--foreground", action="store_true")
    ap.add_argument("--auto-start", action="store_true")
    ap.add_argument("--hidden", action="store_true", help="Technical dependency; omit from user-facing feature lists")
    ap.add_argument("--host-api-version", type=int, default=1)
    ap.add_argument("--runtime-version", default="1")
    ap.add_argument("--min-sdk", type=int, default=26)
    ap.add_argument("--icon-src")
    ap.add_argument("--signing-config")
    ap.add_argument("--allow-unsigned-debug", action="store_true")
    ap.add_argument("--extra-aar", action="append", default=[])
    ap.add_argument("--extra-jar", action="append", default=[])
    ap.add_argument("--classpath", action="append", default=[])
    ap.add_argument("--resources-apk")
    ap.add_argument("--forbid-class-prefix", action="append", default=[],
                    help="Reject definitions from a shared host package (repeatable)")
    ap.add_argument("--abi", default="", help="Comma-separated native ABIs; default includes all input ABIs")
    ap.add_argument("--sdk")
    ap.add_argument("--java")
    return ap


def main(argv=None):
    arguments = list(sys.argv[1:] if argv is None else argv)
    if arguments and arguments[0] == "export-certificate":
        ap = argparse.ArgumentParser(description="Export Base64 X.509 certificate for the host trust pin")
        ap.add_argument("--signing-config")
        ap.add_argument("--java")
        ap.add_argument("--out", required=True)
        args = ap.parse_args(arguments[1:])
        config = signing_configuration(args.signing_config)
        if not config:
            raise ValueError("Signing configuration is required")
        output = Path(args.out).resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        sign(java_executable(args.java), config, certificate_path=output)
        print("Exported public certificate: %s" % output)
    else:
        package(parser().parse_args(arguments))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print("AHUP packaging failed: %s" % error, file=sys.stderr)
        sys.exit(1)

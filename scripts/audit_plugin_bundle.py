#!/usr/bin/env python3
"""Audit actual APK/AHUP artifacts: signatures, hashes, resource IDs and shared DEX ABI.

python scripts/audit_plugin_bundle.py --host host-release.apk --plugins dist/ahup \
    --source-root . --report build/plugin-artifact-audit.json
No Gradle, network, installation or credentials are needed.
"""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile

import package_ahup as package

SYSTEM_PREFIXES = ("Ljava/", "Ljavax/", "Landroid/", "Ldalvik/", "Lorg/json/", "Lorg/w3c/", "Lorg/xml/", "Lsun/")
SHARED_PREFIXES = ("Lkotlin/", "Lkotlinx/", "Landroidx/", "Lcom/ahu/ahutong/core/plugin/",
                   "Lcom/ahu/ahutong/ui/components/", "Lcom/ahu/ahutong/ui/theme/pack/",
                   "Lokhttp3/", "Lokio/", "Lretrofit2/", "Lcom/google/gson/", "Lcoil/", "Ltop/yukonga/", "Lcom/kyant/")
OBJECT_METHODS = {"toString()Ljava/lang/String;", "getClass()Ljava/lang/Class;", "hashCode()I",
                  "equals(Ljava/lang/Object;)Z", "clone()Ljava/lang/Object;", "finalize()V",
                  "wait()V", "wait(J)V", "wait(JI)V", "notify()V", "notifyAll()V"}
HOST_DENY_PREFIXES = tuple("Lcom/ahu/ahutong/" + name for name in (
    "plugin/", "feature/", "ui/screen/", "ui/state/", "ui/suggestion/", "data/adapter/",
    "data/calendar/", "data/crawler/", "data/mail/", "data/notice/", "data/repository/", "data/weather/",
    "data/AHURepository", "data/EvaluationRepository", "data/FreeClassroomGateway",
    "data/ProgramCompletionGateway", "data/dao/AHUCache", "sdk/RustSDK", "sdk/nativebridge/",
    "ui/theme/pack/MiuixComponentPack", "ui/theme/pack/RadiantComponentPack"))
# Shared, generation-scoped HTTP transport is a core contract implementation.
HOST_ALLOW_PREFIXES = ("Lcom/ahu/ahutong/data/crawler/net/",)
NATIVE_OWNERS = {"account": "account", "schedule": "schedule", "grades": "grade_exam",
                 "campus_card": "campus_card", "storage": "storage_migration"}
REQUIRED_NATIVE_DOMAINS = {"campus_card": "campus_card", "storage_migration": "storage"}


def descriptor_prefix(value):
    return value if value.startswith("L") and "/" in value else "L" + value.replace(".", "/")


def certificate_der(encoded):
    if encoded[:1] == b"0":
        return encoded
    text = encoded.replace(b"-----BEGIN CERTIFICATE-----", b"").replace(b"-----END CERTIFICATE-----", b"")
    return base64.b64decode(b"".join(text.split()), validate=True)


def apk_certificate_digests(output):
    # Build Tools 37 names the signing scheme; older versions number signers.
    values = re.findall(r"(?mi)^(?:Signer #\d+|V[\d.]+ Signer):? certificate SHA-256 digest:[ \t]*([0-9a-f]{64})[ \t]*\r?$", output)
    return list(dict.fromkeys(value.lower() for value in values))


def read_apk(path):
    """APK resources use case-sensitive ZIP names, including AAPT's shortened names.

    These entries stay in memory; AHUP's extracted payload still uses strict
    Windows-safe names. Applying that rule to res/0M.xml and res/0m.xml would
    incorrectly reject a valid optimized Android APK.
    """
    entries, total = {}, 0
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            name = info.filename
            if info.is_dir():
                continue
            if (name in entries or name.startswith("/") or "\\" in name or
                    any(part in ("", ".", "..") for part in name.split("/"))):
                raise ValueError("Unsafe or duplicate APK entry: " + name)
            if info.file_size > package.MAX_FILE_BYTES:
                raise ValueError("APK entry is oversized: " + name)
            total += info.file_size
            if total > package.MAX_PAYLOAD_BYTES:
                raise ValueError("APK content is oversized")
            entries[name] = archive.read(info)
    return entries


def host_signature(args, trusted_der):
    if args.apksigner_jar:
        signer = Path(args.apksigner_jar)
    else:
        sdk = package.sdk_directory(Path(args.source_root).resolve())
        choices = sorted((sdk / "build-tools").glob("*/lib/apksigner.jar"), key=lambda p: package.version_key(p.parents[1]))
        if not choices:
            raise ValueError("Android apksigner is unavailable; set --apksigner-jar")
        signer = choices[-1]
    result = subprocess.run([package.java_executable(args.java), "-jar", str(signer), "verify", "--print-certs", str(args.host)],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60)
    certificates = apk_certificate_digests(result.stdout.decode("utf-8", "replace"))
    return {"verified": result.returncode == 0 and bool(certificates),
            "certificateSha256": certificates,
            "matchesPluginTrustPin": hashlib.sha256(trusted_der).hexdigest() in certificates,
            "verifier": str(signer.resolve())}


def host_business_classes(classes, deny=HOST_DENY_PREFIXES, allow=HOST_ALLOW_PREFIXES):
    return sorted(name for name in classes if name.startswith(deny) and not name.startswith(allow))


def native_details(data):
    """Read ELF64 load alignment, SONAME and defined JNI exports from actual bytes."""
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a little-endian ELF64 native library")
    kind, machine = struct.unpack_from("<HH", data, 16)
    if kind != 3:
        raise ValueError("Native payload is not an ELF shared library")
    phoff, shoff = struct.unpack_from("<QQ", data, 32)
    phsize, phcount, shsize, shcount = struct.unpack_from("<HHHH", data, 54)
    alignments = []
    for index in range(phcount):
        offset = phoff + index * phsize
        if struct.unpack_from("<I", data, offset)[0] == 1:
            file_offset, virtual = struct.unpack_from("<QQ", data, offset + 8)
            alignment = struct.unpack_from("<Q", data, offset + 48)[0]
            if alignment == 0 or alignment & (alignment - 1) or file_offset % alignment != virtual % alignment:
                raise ValueError("Invalid ELF load segment alignment")
            alignments.append(alignment)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, shoff + index * shsize) for index in range(shcount)]
    def section_bytes(section):
        return data[section[4]:section[4] + section[5]]
    def string_at(strings, offset):
        end = strings.find(b"\0", offset)
        if offset >= len(strings) or end < 0:
            raise ValueError("Invalid ELF string table")
        return strings[offset:end].decode("utf-8")
    soname, exports, on_load = None, [], False
    for section in sections:
        if section[1] not in (6, 11):
            continue
        strings = section_bytes(sections[section[6]])
        content = section_bytes(section)
        for offset in range(0, len(content), section[9]):
            if section[1] == 6:
                tag, value = struct.unpack_from("<qQ", content, offset)
                if tag == 14:
                    soname = string_at(strings, value)
            else:
                name, info, _, defined, _, _ = struct.unpack_from("<IBBHQQ", content, offset)
                if defined and info >> 4 in (1, 2):
                    symbol = string_at(strings, name)
                    if symbol == "JNI_OnLoad":
                        on_load = True
                    if symbol.startswith("Java_"):
                        exports.append(symbol)
    if not alignments:
        raise ValueError("Native library has no load segments")
    return {"elfMachine": machine, "loadAlignments": alignments, "soname": soname,
            "jniOnLoad": on_load, "jniExports": sorted(exports)}


def audit_native(entries, identity, manifest, artifact_index):
    output, errors = [], []
    if identity in REQUIRED_NATIVE_DOMAINS:
        required = "lib/arm64-v8a/libahup_%s.so" % REQUIRED_NATIVE_DOMAINS[identity]
        if required not in entries:
            errors.append({"requiredNativeLibraryMissing": required})
    for path, content in entries.items():
        if not path.startswith("lib/"):
            continue
        parts = path.split("/")
        if len(parts) != 3 or not parts[2].endswith(".so"):
            errors.append({"invalidNativePath": path})
            continue
        abi, filename = parts[1:]
        row = dict(native_details(content), path=path, abi=abi, size=len(content),
                   sha256=hashlib.sha256(content).hexdigest())
        output.append(row)
        if abi not in manifest.get("abis", []):
            errors.append({"undeclaredNativeAbi": path})
        if row["elfMachine"] != {"arm64-v8a": 183, "x86_64": 62}.get(abi):
            errors.append({"nativeMachineMismatch": path})
        if min(row["loadAlignments"]) < 16384:
            errors.append({"nativeNot16KiBAligned": path})
        if filename == "libahutong_rs.so":
            errors.append({"legacyMonolithicNativeLibrary": path})
        if filename.startswith("libahup_"):
            domain = filename[len("libahup_"):-len(".so")]
            if NATIVE_OWNERS.get(domain) != identity:
                errors.append({"wrongNativeDomainOwner": path, "expectedOwner": NATIVE_OWNERS.get(domain)})
            index = artifact_index.get((domain, abi))
            if index is None or index["sha256"] != row["sha256"] or index["size"] != len(content):
                errors.append({"nativeBuildDigestMismatch": path})
            if row["soname"] != filename:
                errors.append({"nativeSonameMismatch": path, "soname": row["soname"]})
            if index:
                bridge = "Java_" + index["bridge"].replace(".", "_") + "_"
                marker = index["bridge"].replace(".", "/").encode("utf-8")
                # Rust registers JNI methods dynamically; only JNI_OnLoad is exported.
                registered = row["jniOnLoad"] and marker in content
                row["registeredJniBridge"] = index["bridge"] if registered else None
                named = row["jniExports"] and all(item.startswith(bridge) for item in row["jniExports"])
                other_bridges = [item["bridge"].replace(".", "/").encode("utf-8")
                                 for item in artifact_index.values() if item["bridge"] != index["bridge"]]
                if not (registered or named) or any(item in content for item in other_bridges) or b"com/ahu/ahutong/sdk/RustSDK" in content:
                    errors.append({"nativeBridgeOwnershipMismatch": path})
    if set(manifest.get("abis", [])) != {row["abi"] for row in output}:
        errors.append({"nativeAbiInventoryMismatch": manifest.get("abis", [])})
    return output, errors


class Dex:
    """Read definitions and references without relying on decompiler heuristics."""
    def __init__(self, data):
        package.dex_classes(data)
        self.data = data
        strings = []
        for i in range(self.u32(56)):
            offset = self.u32(self.u32(60) + 4 * i)
            _, offset = self.uleb(offset)
            end = data.find(b"\0", offset)
            if end < 0:
                raise ValueError("Truncated DEX string")
            strings.append(data[offset:end].decode("utf-8", "replace"))
        types = [strings[self.u32(self.u32(68) + i * 4)] for i in range(self.u32(64))]
        prototypes = []
        for i in range(self.u32(72)):
            offset = self.u32(76) + i * 12
            args_at = self.u32(offset + 8)
            args = [] if not args_at else [types[self.u16(args_at + 4 + j * 2)] for j in range(self.u32(args_at))]
            prototypes.append("(" + "".join(args) + ")" + types[self.u32(offset + 4)])
        self.method_refs = []
        for i in range(self.u32(88)):
            offset = self.u32(92) + i * 8
            self.method_refs.append((types[self.u16(offset)], strings[self.u32(offset + 4)] + prototypes[self.u16(offset + 2)]))
        self.field_refs = []
        for i in range(self.u32(80)):
            offset = self.u32(84) + i * 8
            self.field_refs.append((types[self.u16(offset)], strings[self.u32(offset + 4)] + ":" + types[self.u16(offset + 2)]))
        self.classes = {}
        for i in range(self.u32(96)):
            offset = self.u32(100) + i * 32
            name = types[self.u32(offset)]
            superclass = self.u32(offset + 8)
            interfaces_at = self.u32(offset + 12)
            parents = [] if superclass == 0xffffffff else [types[superclass]]
            if interfaces_at:
                parents += [types[self.u16(interfaces_at + 4 + j * 2)] for j in range(self.u32(interfaces_at))]
            methods, fields = set(), set()
            cursor = self.u32(offset + 24)
            if cursor:
                counts = []
                for _ in range(4):
                    count, cursor = self.uleb(cursor)
                    counts.append(count)
                for category, count in enumerate(counts):
                    member = 0
                    for _ in range(count):
                        delta, cursor = self.uleb(cursor)
                        member += delta
                        _, cursor = self.uleb(cursor)  # access flags
                        if category < 2:
                            fields.add(self.field_refs[member][1])
                        else:
                            _, cursor = self.uleb(cursor)  # code offset
                            methods.add(self.method_refs[member][1])
            self.classes[name] = {"parents": parents, "methods": methods, "fields": fields}

    def u16(self, offset):
        return struct.unpack_from("<H", self.data, offset)[0]

    def u32(self, offset):
        return struct.unpack_from("<I", self.data, offset)[0]

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            byte = self.data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
        raise ValueError("Invalid DEX LEB128")


def resource_packages(data):
    if len(data) < 12:
        raise ValueError("Truncated resource table")
    kind, header, size = struct.unpack_from("<HHI", data)
    if kind != 2 or size != len(data) or header < 12:
        raise ValueError("Invalid resource table")
    output = []
    offset = header
    while offset < size:
        kind, chunk_header, chunk_size = struct.unpack_from("<HHI", data, offset)
        if chunk_size < chunk_header or chunk_header < 8 or offset + chunk_size > size:
            raise ValueError("Invalid resource chunk")
        if kind == 0x200:
            if chunk_header < 268:
                raise ValueError("Invalid resource package header")
            identity = struct.unpack_from("<I", data, offset + 8)[0]
            name = data[offset + 12:offset + 268].decode("utf-16le").split("\0", 1)[0]
            output.append({"id": identity, "name": name})
        offset += chunk_size
    if len(output) != struct.unpack_from("<I", data, 8)[0]:
        raise ValueError("Resource package count mismatch")
    return output


def class_definition(data):
    """Read public platform stubs to resolve inherited Android/JDK method references."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid class file")
    position = 8
    def u16():
        nonlocal position
        value = struct.unpack_from(">H", data, position)[0]
        position += 2
        return value
    count = u16()
    pool = [None] * count
    index = 1
    while index < count:
        tag = data[position]
        position += 1
        if tag == 1:
            length = u16()
            pool[index] = data[position:position + length].decode("utf-8", "replace")
            position += length
        elif tag == 7:
            pool[index] = u16()
        else:
            size = {3: 4, 4: 4, 5: 8, 6: 8, 8: 2, 9: 4, 10: 4, 11: 4,
                    12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}.get(tag)
            if size is None:
                raise ValueError("Unknown constant pool tag")
            position += size
            if tag in (5, 6):
                index += 1
        index += 1
    def descriptor(index):
        return "L" + pool[pool[index]] + ";"
    u16()  # access flags
    name, parent = descriptor(u16()), u16()
    parents = [] if parent == 0 else [descriptor(parent)]
    parents += [descriptor(u16()) for _ in range(u16())]
    members = []
    for kind in ("fields", "methods"):
        found = set()
        for _ in range(u16()):
            u16()
            member, signature = pool[u16()], pool[u16()]
            found.add(member + (":" if kind == "fields" else "") + signature)
            for _ in range(u16()):
                u16()
                size = struct.unpack_from(">I", data, position)[0]
                position += 4 + size
        members.append(found)
    return name, {"parents": parents, "fields": members[0], "methods": members[1]}


def platform_definitions(android_jar):
    classes = {}
    with zipfile.ZipFile(android_jar) as archive:
        for name in archive.namelist():
            if name.endswith(".class"):
                identity, definition = class_definition(archive.read(name))
                classes[identity] = definition
    return classes


def definitions(entries, prefix=""):
    classes, dexes = {}, []
    for name, data in entries.items():
        if name.startswith(prefix) and package.DEX_NAME.fullmatch(name[len(prefix):]):
            dex = Dex(data)
            duplicates = set(classes) & set(dex.classes)
            if duplicates:
                raise ValueError("Duplicate classes inside archive: " + ", ".join(sorted(duplicates)[:10]))
            classes.update(dex.classes)
            dexes.append(dex)
    if not dexes:
        raise ValueError("Archive has no DEX")
    return classes, dexes


def verify_inventory(entries):
    raw = entries.get("manifest.json", b"")
    if not raw or len(raw) > package.MAX_MANIFEST_BYTES:
        raise ValueError("Missing or oversized manifest")
    manifest = json.loads(raw.decode("utf-8"))
    if manifest.get("formatVersion") != 3 or manifest.get("release") is not True or not entries.get("signature.sig"):
        raise ValueError("Expected a signed release AHUP v3 package")
    if manifest.get("signatureAlgorithm") != "SHA256withRSA":
        raise ValueError("Unsupported signature algorithm")
    if not package.PLUGIN_ID.fullmatch(manifest.get("id", "")):
        raise ValueError("Invalid plugin ID")
    listed = {}
    for item in manifest.get("files", []):
        name = package.safe_path(item["path"])
        if name in listed or name in package.RESERVED:
            raise ValueError("Duplicate or reserved inventory path")
        payload = entries.get(name)
        if payload is None or item["size"] != len(payload) or item["sha256"] != hashlib.sha256(payload).hexdigest():
            raise ValueError("Payload integrity failure: " + name)
        listed[name] = item
    if set(listed) != set(entries) - package.RESERVED:
        raise ValueError("Manifest inventory does not cover the exact payload")
    return manifest


def has_member(classes, owner, member, kind, seen=None):
    if owner == "Ljava/lang/Object;" and owner not in classes:
        return kind == "methods" and member in OBJECT_METHODS
    seen = set() if seen is None else seen
    if owner in seen:
        return False
    seen.add(owner)
    item = classes.get(owner)
    if item is None:
        return False
    if member in item[kind]:
        return True
    if member.startswith("<init>("):
        return False
    return any(has_member(classes, parent, member, kind, seen) for parent in item["parents"])


def expected_resource_ids(source_root):
    output = {}
    for path in (source_root / "plugins").glob("*/build.gradle.kts"):
        match = re.search(r'"--package-id"\s*,\s*"(0x[0-9A-Fa-f]+)"', path.read_text(encoding="utf-8-sig"))
        if match:
            output[path.parent.name] = int(match.group(1), 16)
    return output


def dependency_cycles(manifests):
    complete, active, findings = set(), [], []
    def visit(identity):
        if identity in active:
            findings.append(active[active.index(identity):] + [identity])
            return
        if identity in complete or identity not in manifests:
            return
        active.append(identity)
        for dependency in manifests[identity].get("dependencies", []):
            visit(dependency["id"])
        active.pop()
        complete.add(identity)
    for identity in manifests:
        visit(identity)
    return findings


def audit(args):
    host = read_apk(args.host)
    host_classes, _ = definitions(host)
    android_jar = args.android_jar
    if not android_jar:
        sdk = package.sdk_directory(Path(args.source_root).resolve())
        platforms = sorted((sdk / "platforms").glob("android-*/android.jar"), key=lambda p: package.version_key(p.parent))
        if not platforms:
            raise ValueError("Android SDK platform is missing; set --android-jar")
        android_jar = platforms[-1]
    platform_classes = platform_definitions(android_jar)
    pin = Path(args.certificate).read_bytes() if args.certificate else (
        host.get("assets/ahup_trusted_certificate.der") or host.get("assets/ahup_trusted_pubkey.txt"))
    if not pin:
        raise ValueError("Host APK has no AHUP trust pin; use --certificate only for a separate trusted pin")
    expected_ids = expected_resource_ids(Path(args.source_root))
    native_index = Path(args.native_index) if args.native_index else Path(args.source_root) / "native/build/artifacts.json"
    native_artifacts = {}
    if native_index.is_file():
        native_artifacts = {(item["domain"], item["abi"]): item
                            for item in json.loads(native_index.read_text(encoding="utf-8"))["artifacts"]}
    plugins, report = {}, {"host": str(Path(args.host).resolve()), "hostClassCount": len(host_classes),
                          "hostSha256": hashlib.sha256(Path(args.host).read_bytes()).hexdigest(),
                          "platformJar": str(Path(android_jar).resolve()), "plugins": [], "errors": [],
                          "certificateSource": str(args.certificate) if args.certificate else "host APK asset",
                          "trustCertificateSha256": hashlib.sha256(certificate_der(pin)).hexdigest()}
    if args.verify_host_signature:
        report["hostSignature"] = host_signature(args, certificate_der(pin))
        if not report["hostSignature"]["verified"] or not report["hostSignature"]["matchesPluginTrustPin"]:
            report["errors"].append({"hostSignatureOrTrustPinMismatch": report["hostSignature"]})
    forbidden_host = host_business_classes(host_classes,
        HOST_DENY_PREFIXES + tuple(descriptor_prefix(item) for item in args.host_deny_prefix),
        HOST_ALLOW_PREFIXES + tuple(descriptor_prefix(item) for item in args.host_allow_prefix))
    report["hostBusinessClasses"] = forbidden_host
    if forbidden_host:
        report["errors"].append({"hostContainsBusinessImplementations": forbidden_host})
    report["hostNativeLibraries"] = sorted(name for name in host if name.startswith("lib/"))
    report["hostResources"] = resource_packages(host["resources.arsc"]) if "resources.arsc" in host else []
    if any(item["id"] != 0x7f for item in report["hostResources"]):
        report["errors"].append({"unexpectedHostResourceId": report["hostResources"]})
    if any(Path(name).name.startswith("libahup_") or Path(name).name == "libahutong_rs.so"
           for name in report["hostNativeLibraries"]):
        report["errors"].append({"hostContainsBusinessNativeLibraries": report["hostNativeLibraries"]})
    with tempfile.TemporaryDirectory(prefix="ahup-audit-") as temporary:
        temporary = Path(temporary)
        certificate = temporary / "trusted.cert"
        certificate.write_bytes(pin)
        signature_args = []
        for path in sorted(Path(args.plugins).glob("*.ahup")):
            entries = package.read_archive(path)
            manifest = verify_inventory(entries)
            identity = manifest["id"]
            if identity in plugins:
                raise ValueError("Multiple package versions for one plugin: " + identity)
            classes, dexes = definitions(entries, "dex/")
            if "L" + manifest["entryClass"].replace(".", "/") + ";" not in classes:
                raise ValueError("Missing entry class in " + identity)
            packages = []
            if "resources.apk" in entries:
                resources = read_apk(io.BytesIO(entries["resources.apk"]))
                if any(package.DEX_NAME.fullmatch(name) or name.startswith("lib/") for name in resources):
                    raise ValueError("Executable code hidden in resources.apk: " + identity)
                if "resources.arsc" in resources:
                    packages = resource_packages(resources["resources.arsc"])
            row = {"id": identity, "path": str(path.resolve()), "versionCode": manifest["versionCode"],
                   "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "classCount": len(classes),
                   "dexCount": len(dexes), "resources": packages, "abis": manifest.get("abis", []),
                   "entryClass": manifest["entryClass"], "manifestSha256": hashlib.sha256(entries["manifest.json"]).hexdigest(),
                   "dependencies": manifest.get("dependencies", []), "sharedAbi": [], "dependencyAbi": [], "errors": []}
            row["nativeLibraries"], native_errors = audit_native(entries, identity, manifest, native_artifacts)
            row["errors"].extend(native_errors)
            if manifest.get("hostApiVersion") != args.host_api_version or manifest.get("runtimeVersion") != args.runtime_version:
                row["errors"].append({"incompatibleHostRuntime": [manifest.get("hostApiVersion"), manifest.get("runtimeVersion")]})
            duplicates = set(classes) & set(host_classes)
            forbidden = {name for name in classes if name.startswith(SHARED_PREFIXES)}
            row["duplicateHostClasses"] = sorted(duplicates)
            row["generatedClassAliases"] = sorted(name for name in duplicates if re.search(r"/(?:R(?:\$[^;]+)?|BuildConfig);$", name))
            if duplicates or forbidden:
                row["errors"].append({"duplicateSharedClasses": sorted(duplicates | forbidden)})
            for resource in packages:
                if resource["id"] < 0x80 or resource["id"] > 0xfe or resource["id"] != expected_ids.get(identity):
                    row["errors"].append({"unexpectedResourceId": resource, "expected": expected_ids.get(identity)})
            folder = temporary / identity
            folder.mkdir()
            manifest_path, signature_path = folder / "manifest.json", folder / "signature.sig"
            manifest_path.write_bytes(entries["manifest.json"])
            signature_path.write_bytes(entries["signature.sig"])
            signature_args += [str(manifest_path), str(signature_path)]
            plugins[identity] = {"manifest": manifest, "classes": classes, "dexes": dexes, "row": row}
            report["plugins"].append(row)
        if not plugins:
            raise ValueError("No AHUP packages found")
        cycles = dependency_cycles({identity: item["manifest"] for identity, item in plugins.items()})
        if cycles:
            report["errors"].append({"dependencyCycles": cycles})
        if args.expected_count is not None and len(plugins) != args.expected_count:
            report["errors"].append({"pluginCountMismatch": {"expected": args.expected_count, "actual": len(plugins)}})
        if args.require_all:
            missing = sorted(set(expected_ids) - set(plugins))
            extra = sorted(set(plugins) - set(expected_ids))
            if missing or extra:
                report["errors"].append({"pluginCatalogMismatch": {"missing": missing, "unexpected": extra}})
        for name in ("assets/bootstrap/account.ahup", "assets/migrations/storage_migration.ahup"):
            identity = Path(name).stem
            if name in host and identity in plugins:
                if hashlib.sha256(host[name]).hexdigest() != plugins[identity]["row"]["sha256"]:
                    report["errors"].append({"embeddedPluginDoesNotMatchDelivery": name})
        resource_owners = {}
        class_owners = {}
        for identity, plugin in plugins.items():
            for name in plugin["classes"]:
                class_owners.setdefault(name, []).append(identity)
            for resource in plugin["row"]["resources"]:
                previous = resource_owners.setdefault(resource["id"], identity)
                if previous != identity:
                    plugin["row"]["errors"].append({"resourceIdCollision": previous})
        report["crossPluginClasses"] = {name: owners for name, owners in class_owners.items() if len(owners) > 1}
        # Private classes in isolated sibling loaders may share names; dependencies must never shadow them.
        verified = subprocess.run([package.java_executable(args.java), str(package.SIGNER), "verify", str(certificate)] + signature_args,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if verified.returncode:
            raise ValueError("One or more plugin signatures do not match the host trust pin")
        for identity, plugin in plugins.items():
            available = dict(platform_classes)
            available.update(host_classes)
            available.update(plugin["classes"])
            dependency_classes = set(plugin["classes"])
            pending = [(identity, dependency) for dependency in plugin["manifest"].get("dependencies", [])]
            visited = {identity}
            while pending:
                owner, dependency = pending.pop()
                target = plugins.get(dependency["id"])
                if target is None:
                    plugin["row"]["errors"].append({"missingDependency": dependency["id"]})
                    continue
                version = target["manifest"]["versionCode"]
                if not dependency["minVersion"] <= version <= dependency.get("maxVersion", 2 ** 63 - 1):
                    plugin["row"]["errors"].append({"incompatibleDependency": dependency})
                if dependency["id"] in visited:
                    continue
                visited.add(dependency["id"])
                shadowed = dependency_classes & set(target["classes"])
                if shadowed:
                    plugin["row"]["errors"].append({"duplicateDependencyClasses": sorted(shadowed)})
                available.update(target["classes"])
                dependency_classes.update(target["classes"])
                pending += [(dependency["id"], item) for item in target["manifest"].get("dependencies", [])]
            missing, abi, dependency_abi = set(), set(), set()
            for definition in plugin["classes"].values():
                missing.update(parent for parent in definition["parents"] if parent not in available)
            for dex in plugin["dexes"]:
                for kind, references in (("methods", dex.method_refs), ("fields", dex.field_refs)):
                    for owner, member in references:
                        if owner.startswith(SYSTEM_PREFIXES) or owner.startswith("["):
                            continue
                        symbol = owner + "->" + member
                        if owner in host_classes:
                            abi.add(symbol)
                            if not has_member(available, owner, member, kind):
                                missing.add(symbol)
                        elif owner not in available:
                            missing.add(symbol)
                        elif owner not in plugin["classes"] and owner not in platform_classes:
                            dependency_abi.add(symbol)
                            if not has_member(available, owner, member, kind):
                                missing.add(symbol)
            plugin["row"]["sharedAbi"] = sorted(abi)
            plugin["row"]["dependencyAbi"] = sorted(dependency_abi)
            if missing:
                plugin["row"]["errors"].append({"unresolvedAbi": sorted(missing)})
            plugin["row"]["signatureVerified"] = True
            report["errors"] += [{"plugin": identity, "detail": error} for error in plugin["row"]["errors"]]
    destination = Path(args.report)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Audited %d plugins against %d host classes; %d findings. Report: %s" %
          (len(plugins), len(host_classes), len(report["errors"]), destination.resolve()))
    return 1 if report["errors"] else 0


def main(default_expected_count=None, require_all=False, verify_host=False):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True)
    parser.add_argument("--plugins", required=True)
    parser.add_argument("--certificate")
    parser.add_argument("--source-root", default=".")
    parser.add_argument("--report", required=True)
    parser.add_argument("--java")
    parser.add_argument("--verify-host-signature", action="store_true", default=verify_host)
    parser.add_argument("--apksigner-jar")
    parser.add_argument("--android-jar")
    parser.add_argument("--native-index", help="Native build digest/JNI ownership index; default native/build/artifacts.json")
    parser.add_argument("--expected-count", type=int, default=default_expected_count)
    parser.add_argument("--require-all", action="store_true", default=require_all,
                        help="Require every source plugin's resource-ID catalog entry")
    parser.add_argument("--host-deny-prefix", action="append", default=[])
    parser.add_argument("--host-allow-prefix", action="append", default=[])
    parser.add_argument("--host-api-version", type=int, default=1)
    parser.add_argument("--runtime-version", default="1")
    return audit(parser.parse_args())


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, KeyError, IndexError, OSError, zipfile.BadZipFile, struct.error) as error:
        print("Artifact audit failed: %s" % error)
        raise SystemExit(1)

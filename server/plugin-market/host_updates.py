"""Signed, immutable updates for the plugin host; never the legacy monolith feed."""
import base64
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

from manifest import MarketError, inspect_package, json_object, plugin_id, positive_int
from store import atomic_json, utc_now

HOST_PRODUCT = "ahutong-plugin-host"
HOST_MARKER = "assets/plugin-host.json"
MAX_APK_BYTES = 512 * 1024 * 1024


def apk_certificate_digests(output):
    # SDK 36 labels signers by ordinal; SDK 37 labels signature schemes. A source
    # stamp certificate is not an APK signing identity and must never be accepted.
    return set(value.lower() for value in re.findall(
        r"(?mi)^(?:Signer #\d+|V[\d.]+ Signer):? certificate SHA-256 digest:[ \t]*([0-9a-f]{64})[ \t]*\r?$",
        output))


def packaging_helpers():
    path = Path(__file__).resolve().parents[2] / "scripts/package_ahup.py"
    spec = importlib.util.spec_from_file_location("ahup_packaging_for_market", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class MetadataSigner:
    def __init__(self, signing_config=None, private_key=None, java=None, openssl="openssl", key_pass_env=None):
        if bool(signing_config) == bool(private_key):
            raise MarketError("Choose exactly one signing configuration or PEM private key")
        self.signing_config, self.private_key = signing_config, private_key
        self.java, self.openssl, self.key_pass_env = java, openssl, key_pass_env

    def __call__(self, raw):
        with tempfile.TemporaryDirectory(prefix="ahu-host-sign-") as directory:
            manifest = Path(directory) / "metadata.json"
            signature = Path(directory) / "signature.sig"
            manifest.write_bytes(raw)
            if self.signing_config:
                helper = packaging_helpers()
                config = helper.signing_configuration(self.signing_config)
                helper.sign(helper.java_executable(self.java), config, manifest, signature)
            else:
                arguments = [self.openssl, "dgst", "-sha256", "-sign", str(self.private_key)]
                if self.key_pass_env:
                    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", self.key_pass_env):
                        raise MarketError("Invalid private-key passphrase environment variable name")
                    arguments += ["-passin", "env:" + self.key_pass_env]
                arguments += ["-out", str(signature), str(manifest)]
                result = subprocess.run(arguments, capture_output=True, timeout=30)
                if result.returncode:
                    raise MarketError("Host metadata signing failed; verify the signing configuration")
            return signature.read_bytes()


class AndroidApkVerifier:
    """Read actual APK identity and verify its signer using official SDK tools."""
    def __init__(self, fingerprints, sdk=None, java=None):
        helper = packaging_helpers()
        self.java = helper.java_executable(java)
        root = helper.sdk_directory(Path(__file__).resolve().parents[2], sdk)
        directories = [path for path in (root / "build-tools").iterdir() if path.is_dir()]
        tools = max(directories, key=helper.version_key)
        self.aapt = tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
        self.apksigner = tools / "lib/apksigner.jar"
        self.fingerprints = set(fingerprints)

    def __call__(self, path):
        identity = subprocess.run([str(self.aapt), "dump", "badging", str(path)], capture_output=True, text=True, encoding="utf-8", timeout=60)
        signed = subprocess.run([self.java, "-jar", str(self.apksigner), "verify", "--print-certs", str(path)], capture_output=True, text=True, timeout=60)
        if identity.returncode or signed.returncode:
            raise MarketError("APK identity or Android signature verification failed")
        package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", identity.stdout, re.M)
        minimum = re.search(r"^(?:sdkVersion|minSdkVersion):'([0-9]+)'", identity.stdout, re.M)
        fingerprints = apk_certificate_digests(signed.stdout)
        if not package or not minimum or not fingerprints or not fingerprints.issubset(self.fingerprints):
            raise MarketError("APK package, minSdk or trusted certificate does not match")
        return {"packageName": package[1], "versionCode": int(package[2]), "versionName": package[3],
                "minSdk": int(minimum[1]), "apkCertificateSha256": sorted(fingerprints)}


class HostUpdateStore:
    def __init__(self, package_store):
        self.store = package_store
        self.root = package_store.root / "hosts"
        self.root.mkdir(parents=True, exist_ok=True)

    def publish(self, source, signer, verifier, apk_verifier, package_name="com.ahu.ahutong", required=None, release_notes=""):
        if len(release_notes.encode("utf-8")) > 64 * 1024:
            raise MarketError("Release notes exceed 64 KiB")
        with self.store.writer_lock():
            with tempfile.TemporaryDirectory(prefix=".host-publish-", dir=str(self.store.root)) as temporary:
                staged = Path(temporary) / "version"
                staged.mkdir()
                apk = staged / "host.apk"
                if Path(source).stat().st_size > MAX_APK_BYTES:
                    raise MarketError("Host APK exceeds 512 MiB")
                shutil.copyfile(source, apk)
                with apk.open("r+b") as stream:
                    os.fsync(stream.fileno())
                try:
                    with zipfile.ZipFile(apk) as archive:
                        if archive.namelist().count(HOST_MARKER) != 1 or archive.getinfo(HOST_MARKER).file_size > 16384:
                            raise MarketError("APK is not an identified plugin host")
                        marker = json_object(archive.read(HOST_MARKER))
                        if type(marker.get("schemaVersion")) is not int or marker["schemaVersion"] != 1 or marker.get("product") != HOST_PRODUCT:
                            raise MarketError("Legacy/unknown APK cannot be published as the plugin host")
                        host_api = positive_int(marker.get("hostApiVersion"), "hostApiVersion")
                        runtime = marker.get("runtimeVersion")
                        if not isinstance(runtime, str) or not runtime:
                            raise MarketError("Host runtimeVersion is required")
                        required = required if required is not None else marker.get("requiredPluginVersions", {})
                        if not isinstance(required, dict) or not required or len(required) > 128:
                            raise MarketError("requiredPluginVersions must contain bootstrap plugin versions")
                        bootstrap_bytes = 0
                        foundation_manifests = {}
                        bootstrap_mode = marker.get("bootstrapMode", "embedded")
                        if bootstrap_mode not in ("embedded", "market"):
                            raise MarketError("Unknown bootstrap mode")
                        if bootstrap_mode == "market":
                            if any(name.endswith(".ahup") for name in archive.namelist()):
                                raise MarketError("Minimal native host must not embed AHUP packages")
                            initialization = json_object(archive.read("assets/initialization.json"))
                            if (set(initialization.get("required", [])) != set(required)
                                    or initialization.get("hostApiVersion") != host_api
                                    or initialization.get("runtimeVersion") != runtime):
                                raise MarketError("Native initialization and signed host marker disagree")
                        for identifier, version in required.items():
                            plugin_id(identifier)
                            positive_int(version, "required plugin versionCode")
                            if bootstrap_mode == "market":
                                records = [item for item in self.store.records(identifier) if item["manifest"]["versionCode"] == version]
                                if len(records) != 1:
                                    raise MarketError("Required native foundation is not published")
                                packaged, record = self.store.package(identifier, version, records[0]["sha256"])
                                if inspect_package(packaged, verifier)["sha256"] != record["sha256"]:
                                    raise MarketError("Published foundation digest changed")
                            else:
                                name = "assets/bootstrap/%s.ahup" % identifier
                                if archive.namelist().count(name) != 1 or archive.getinfo(name).file_size > 256 * 1024 * 1024:
                                    raise MarketError("Required bootstrap plugin is missing from the APK")
                                bootstrap_bytes += archive.getinfo(name).file_size
                                if bootstrap_bytes > 256 * 1024 * 1024:
                                    raise MarketError("Bootstrap payload exceeds 256 MiB")
                                packaged = Path(temporary) / (identifier + ".ahup")
                                with archive.open(name) as incoming, packaged.open("wb") as output:
                                    shutil.copyfileobj(incoming, output, 1024 * 1024)
                            bootstrap = inspect_package(packaged, verifier)["manifest"]
                            foundation_manifests[identifier] = bootstrap
                            if (bootstrap["id"] != identifier or bootstrap["versionCode"] != version
                                    or bootstrap["hostApiVersion"] != host_api or bootstrap["runtimeVersion"] != runtime):
                                raise MarketError("Bootstrap plugin does not match the signed host compatibility metadata")
                        if bootstrap_mode == "market":
                            for bootstrap in foundation_manifests.values():
                                for dependency in bootstrap["dependencies"]:
                                    selected = required.get(dependency["id"])
                                    if selected is None or not dependency["minVersion"] <= selected <= dependency.get("maxVersion", 2147483647):
                                        raise MarketError("Native foundation dependency closure is incomplete or incompatible")
                except (zipfile.BadZipFile, KeyError, UnicodeError) as error:
                    raise MarketError("Host APK has no valid plugin-host marker/bootstrap") from error
                identity = apk_verifier(apk)
                if identity["packageName"] != package_name:
                    raise MarketError("APK packageName does not match the configured application")
                version = positive_int(identity["versionCode"], "versionCode")
                minimum = positive_int(identity["minSdk"], "minSdk")
                destination = self.root / str(version)
                if destination.exists():
                    raise MarketError("Host version is immutable; increase the APK versionCode", "version_exists", 409)
                digest = hashlib.sha256()
                with apk.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                sha256 = digest.hexdigest()
                metadata = {"schemaVersion": 1, "product": HOST_PRODUCT, "packageName": package_name,
                            "versionCode": version, "versionName": identity["versionName"],
                            "hostApiVersion": host_api, "runtimeVersion": runtime, "minSdk": minimum,
                            "sha256": sha256, "size": apk.stat().st_size,
                            "url": "/v1/host/packages/%s/%s.apk" % (version, sha256),
                            "releaseNotes": release_notes, "requiredPluginVersions": required,
                            "bootstrapMode": bootstrap_mode,
                            "apkCertificateSha256": identity.get("apkCertificateSha256", [])}
                raw = json.dumps(metadata, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
                signature = signer(raw)
                fingerprint = verifier(raw, signature)
                (staged / "metadata.json").write_bytes(raw)
                (staged / "signature.sig").write_bytes(signature)
                for path in (staged / "metadata.json", staged / "signature.sig"):
                    with path.open("r+b") as stream:
                        os.fsync(stream.fileno())
                atomic_json(staged / "record.json", {"publishedAt": utc_now(), "sha256": sha256,
                                                     "signerCertificateSha256": fingerprint})
                os.rename(str(staged), str(destination))
                return self.response(destination)

    def response(self, directory):
        raw = (directory / "metadata.json").read_bytes()
        record = json_object((directory / "record.json").read_bytes())
        return {"schemaVersion": 1, "available": True, "signedMetadata": base64.b64encode(raw).decode("ascii"),
                "signature": base64.b64encode((directory / "signature.sig").read_bytes()).decode("ascii"),
                "metadata": json_object(raw), "signerCertificateSha256": record["signerCertificateSha256"]}

    def update(self, version, host_api, runtime, sdk=None):
        if type(version) is not int or version < 0 or version > 2147483647:
            raise MarketError("Invalid current versionCode")
        positive_int(host_api, "hostApiVersion")
        if not isinstance(runtime, str) or not runtime:
            raise MarketError("runtimeVersion is required")
        if sdk is not None:
            positive_int(sdk, "sdk")
        candidates = []
        for path in self.root.glob("*/metadata.json"):
            if (path.parent / "withdrawal.json").exists():
                continue
            metadata = json_object(path.read_bytes())
            if (metadata["versionCode"] > version and metadata["hostApiVersion"] == host_api
                    and metadata["runtimeVersion"] == runtime and (sdk is None or metadata["minSdk"] <= sdk)):
                candidates.append((metadata["versionCode"], path.parent))
        return self.response(max(candidates)[1]) if candidates else {"schemaVersion": 1, "available": False}

    def package(self, version, sha256):
        positive_int(version, "versionCode")
        directory = self.root / str(version)
        if not (directory / "metadata.json").is_file():
            raise MarketError("Host APK not found", "not_found", 404)
        if (directory / "withdrawal.json").exists():
            raise MarketError("Host APK withdrawn", "withdrawn", 410)
        metadata = json_object((directory / "metadata.json").read_bytes())
        if metadata["sha256"] != sha256:
            raise MarketError("Host APK not found", "not_found", 404)
        path = directory / "host.apk"
        if not path.is_file() or path.stat().st_size != metadata["size"]:
            raise MarketError("Host APK storage is incomplete", "storage_corrupt", 503)
        return path, metadata

    def withdraw(self, version, reason):
        positive_int(version, "versionCode")
        if not isinstance(reason, str) or not reason.strip():
            raise MarketError("Withdrawal reason is required")
        with self.store.writer_lock():
            directory = self.root / str(version)
            if not (directory / "metadata.json").is_file():
                raise MarketError("Host version not found", "not_found", 404)
            if (directory / "withdrawal.json").exists():
                raise MarketError("Host version already withdrawn", "already_withdrawn", 409)
            record = {"versionCode": version, "reason": reason[:2048], "withdrawnAt": utc_now()}
            atomic_json(directory / "withdrawal.json", record)
            return record

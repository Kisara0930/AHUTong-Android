"""AHUP v3 validation. No code from a submitted package is executed."""
import hashlib
import json
import re
import stat
import subprocess
import tempfile
import zipfile
from pathlib import Path, PurePosixPath


ID_PATTERN = re.compile(r"[a-z][a-z0-9_]{1,63}\Z")
SHA256_PATTERN = re.compile(r"[a-f0-9]{64}\Z")
MAX_PACKAGE_BYTES = 256 * 1024 * 1024
MAX_UNPACKED_BYTES = 256 * 1024 * 1024
MAX_FILE_BYTES = 128 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
MAX_ENTRIES = 4096
INT_MAX = 2147483647
KNOWN_ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}


class MarketError(ValueError):
    def __init__(self, message, code="invalid_request", status=400):
        super().__init__(message)
        self.code = code
        self.status = status


def plugin_id(value):
    if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
        raise MarketError("Invalid plugin id")
    return value


def positive_int(value, field):
    if type(value) is not int or value < 1 or value > INT_MAX:
        raise MarketError("%s must be a positive 32-bit integer" % field)
    return value


def json_object(raw):
    def unique_keys(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise MarketError("Duplicate JSON key: %s" % key)
            result[key] = value
        return result
    try:
        value = json.loads(raw, object_pairs_hook=unique_keys,
                           parse_constant=lambda value: (_ for _ in ()).throw(
                               MarketError("Non-finite JSON value")))
    except (ValueError, UnicodeError) as error:
        raise MarketError("Invalid JSON: %s" % error) from error
    if not isinstance(value, dict):
        raise MarketError("JSON body must be an object")
    return value


def safe_path(value):
    if (not isinstance(value, str) or not value or "\\" in value or ":" in value
            or "\x00" in value or value.startswith("/")
            or any(part in ("", ".", "..") for part in value.split("/"))
            or str(PurePosixPath(value)) != value):
        raise MarketError("Unsafe package path")
    return value


def validate_manifest(manifest):
    if type(manifest.get("formatVersion")) is not int or manifest["formatVersion"] != 3:
        raise MarketError("Only AHUP formatVersion 3 is accepted")
    plugin_id(manifest.get("id"))
    for field in ("versionCode", "hostApiVersion", "minSdk"):
        positive_int(manifest.get(field), field)
    for field in ("title", "versionName", "runtimeVersion", "entryClass"):
        value = manifest.get(field)
        if not isinstance(value, str) or not value.strip() or len(value) > 1024:
            raise MarketError("Missing or invalid %s" % field)
    for field in ("summary", "author"):
        if field in manifest and not isinstance(manifest[field], str):
            raise MarketError("Invalid %s" % field)
    if "required" in manifest and type(manifest["required"]) is not bool:
        raise MarketError("required must be boolean")
    if manifest.get("release", True) is not True:
        raise MarketError("Development packages cannot be published")
    for field in ("abis", "capabilities"):
        values = manifest.get(field)
        if (not isinstance(values, list) or len(values) != len(set(map(str, values)))
                or any(not isinstance(value, str) or not value for value in values)):
            raise MarketError("%s must be an array of distinct strings" % field)
    if not set(manifest["abis"]).issubset(KNOWN_ABIS):
        raise MarketError("Unknown Android ABI")
    dependencies = manifest.get("dependencies")
    if not isinstance(dependencies, list) or len(dependencies) > 128:
        raise MarketError("dependencies must be an array (maximum 128)")
    seen = set()
    for dependency in dependencies:
        if not isinstance(dependency, dict):
            raise MarketError("Invalid dependency")
        dependency_id = plugin_id(dependency.get("id"))
        if dependency_id == manifest["id"] or dependency_id in seen:
            raise MarketError("Self or duplicate dependency")
        seen.add(dependency_id)
        minimum = positive_int(dependency.get("minVersion"), "minVersion")
        maximum = positive_int(dependency.get("maxVersion", INT_MAX), "maxVersion")
        if minimum > maximum:
            raise MarketError("Invalid dependency version range")
    files = manifest.get("files")
    if not isinstance(files, list) or not files or len(files) > MAX_ENTRIES - 2:
        raise MarketError("files must describe every payload file")
    seen_files = set()
    for item in files:
        if not isinstance(item, dict):
            raise MarketError("Invalid files entry")
        path = safe_path(item.get("path"))
        if path in seen_files or path in ("manifest.json", "signature.sig"):
            raise MarketError("Duplicate or reserved payload path")
        seen_files.add(path)
        if not isinstance(item.get("sha256"), str) or not SHA256_PATTERN.fullmatch(item["sha256"]):
            raise MarketError("Invalid SHA-256")
        if type(item.get("size")) is not int or item["size"] < 0 or item["size"] > MAX_FILE_BYTES:
            raise MarketError("Invalid payload size")
    if not any(re.fullmatch(r"dex/classes(?:[2-9]|[1-9][0-9]+)?\.dex", item) for item in seen_files):
        raise MarketError("Package must contain dex/classes.dex or secondary DEX files")
    if "dex/classes.dex" not in seen_files:
        raise MarketError("Package must contain dex/classes.dex")
    return manifest


class CertificateVerifier:
    """Trust only configured X.509 certificates; package certificates confer no trust."""
    def __init__(self, certificates, openssl="openssl"):
        self.openssl = str(openssl)
        self.public_keys = []
        self.fingerprints = []
        if not certificates:
            raise MarketError("At least one trusted certificate is required")
        for certificate in certificates:
            path = Path(certificate).resolve(strict=True)
            raw = path.read_bytes()
            inform = "PEM" if raw.startswith(b"-----BEGIN") else "DER"
            public_key = self._run(["x509", "-in", str(path), "-inform", inform,
                                    "-pubkey", "-noout"])
            der = self._run(["x509", "-in", str(path), "-inform", inform, "-outform", "DER"])
            self.public_keys.append(public_key)
            self.fingerprints.append(hashlib.sha256(der).hexdigest())

    def _run(self, arguments):
        try:
            result = subprocess.run([self.openssl] + arguments, capture_output=True,
                                    check=True, timeout=15)
            return result.stdout
        except (OSError, subprocess.SubprocessError) as error:
            raise MarketError("OpenSSL certificate operation failed") from error

    def __call__(self, manifest_bytes, signature):
        with tempfile.TemporaryDirectory(prefix="ahup-verify-") as temporary:
            directory = Path(temporary)
            (directory / "manifest").write_bytes(manifest_bytes)
            (directory / "signature").write_bytes(signature)
            for public_key, fingerprint in zip(self.public_keys, self.fingerprints):
                (directory / "public.pem").write_bytes(public_key)
                try:
                    result = subprocess.run(
                        [self.openssl, "dgst", "-sha256", "-verify", str(directory / "public.pem"),
                         "-signature", str(directory / "signature"), str(directory / "manifest")],
                        capture_output=True, timeout=15)
                except (OSError, subprocess.SubprocessError) as error:
                    raise MarketError("Signature verifier unavailable", "verifier_unavailable", 503) from error
                if result.returncode == 0:
                    return fingerprint
        raise MarketError("Package signature is not trusted", "invalid_signature")


def inspect_package(path, verifier):
    """Validate the exact ZIP payload and signature before any publication."""
    path = Path(path)
    size = path.stat().st_size
    if size > MAX_PACKAGE_BYTES:
        raise MarketError("Package too large")
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            names = [entry.filename for entry in entries]
            if len(entries) > MAX_ENTRIES or len(names) != len(set(names)):
                raise MarketError("Too many entries or duplicate archive paths")
            total = 0
            for entry in entries:
                safe_path(entry.filename)
                mode = entry.external_attr >> 16
                if entry.is_dir() or stat.S_ISLNK(mode) or (entry.flag_bits & 1):
                    raise MarketError("Directory, symlink and encrypted entries are forbidden")
                total += entry.file_size
                if entry.file_size > MAX_FILE_BYTES:
                    raise MarketError("Package file too large")
                if total > MAX_UNPACKED_BYTES:
                    raise MarketError("Unpacked package too large")
            if not {"manifest.json", "signature.sig"}.issubset(names):
                raise MarketError("Package requires manifest.json and signature.sig")
            if archive.getinfo("manifest.json").file_size > MAX_MANIFEST_BYTES:
                raise MarketError("Manifest too large")
            if not 128 <= archive.getinfo("signature.sig").file_size <= 1024:
                raise MarketError("Invalid RSA signature size")
            manifest_raw = archive.read("manifest.json")
            manifest = validate_manifest(json_object(manifest_raw.decode("utf-8")))
            fingerprint = verifier(manifest_raw, archive.read("signature.sig"))
            expected = {item["path"] for item in manifest["files"]}
            if expected != set(names) - {"manifest.json", "signature.sig"}:
                raise MarketError("Signed file list does not match package contents")
            for item in manifest["files"]:
                if archive.getinfo(item["path"]).file_size != item["size"]:
                    raise MarketError("Payload size mismatch: %s" % item["path"])
                digest = hashlib.sha256()
                with archive.open(item["path"]) as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                if digest.hexdigest() != item["sha256"]:
                    raise MarketError("Payload hash mismatch: %s" % item["path"])
    except (zipfile.BadZipFile, KeyError, UnicodeError, RuntimeError) as error:
        raise MarketError("Invalid AHUP archive") from error
    with path.open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"manifest": manifest, "sha256": digest.hexdigest(), "size": size,
            "signerCertificateSha256": fingerprint}

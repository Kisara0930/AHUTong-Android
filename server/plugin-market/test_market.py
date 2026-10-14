"""Contract, security, solver and HTTP integration tests using real RSA signatures."""
import hashlib
import json
import shutil
import subprocess
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from app import MarketServer
from manifest import CertificateVerifier, MarketError, inspect_package
from store import Client, PackageStore


CLIENT = {"hostApiVersion": 1, "runtimeVersion": "1", "sdk": 35, "abis": ["arm64-v8a"]}
QUERY = "?hostApiVersion=1&runtimeVersion=1&sdk=35&abis=arm64-v8a"


class MarketTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.openssl = shutil.which("openssl")
        if not cls.openssl:
            raise RuntimeError("OpenSSL is required to run actual signature verification tests")
        cls.keys = tempfile.TemporaryDirectory(prefix="ahup-market-test-keys-")
        cls.key_dir = Path(cls.keys.name)
        for name in ("official", "untrusted"):
            subprocess.run([cls.openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes",
                            "-keyout", str(cls.key_dir / (name + ".key")),
                            "-out", str(cls.key_dir / (name + ".pem")), "-days", "1",
                            "-subj", "/CN=AHUP Local Test " + name],
                           check=True, capture_output=True)
        cls.verifier = CertificateVerifier([cls.key_dir / "official.pem"], cls.openssl)

    @classmethod
    def tearDownClass(cls):
        cls.keys.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="ahup-market-test-")
        self.root = Path(self.temporary.name)
        self.store = PackageStore(self.root / "store", self.verifier)

    def tearDown(self):
        self.temporary.cleanup()

    def package(self, identifier="schedule", version=1, dependencies=None,
                overrides=None, key="official", payload=None):
        payload = payload or {"dex/classes.dex": b"dex\n035\0unit-test-payload", "icon.png": b"icon"}
        manifest = {"formatVersion": 3, "id": identifier, "title": "课表", "summary": "test",
                    "author": "AHUTong", "versionCode": version, "versionName": "1.0.%s" % version,
                    "hostApiVersion": 1, "runtimeVersion": "1", "minSdk": 26,
                    "entryClass": "com.ahu.Schedule", "required": False,
                    "abis": [], "capabilities": ["NETWORK"], "dependencies": dependencies or [],
                    "files": [{"path": path, "sha256": hashlib.sha256(raw).hexdigest(), "size": len(raw)}
                              for path, raw in sorted(payload.items())]}
        manifest.update(overrides or {})
        raw = json.dumps(manifest, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        manifest_file = self.root / "sign-manifest"
        signature_file = self.root / "sign-signature"
        manifest_file.write_bytes(raw)
        subprocess.run([self.openssl, "dgst", "-sha256", "-sign", str(self.key_dir / (key + ".key")),
                        "-out", str(signature_file), str(manifest_file)], check=True, capture_output=True)
        path = self.root / (identifier + "-" + str(version) + ".ahup")
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("manifest.json", raw)
            archive.writestr("signature.sig", signature_file.read_bytes())
            for name, content in payload.items():
                archive.writestr(name, content)
        return path

    def rewrite(self, path, replacements=None, extras=None):
        with zipfile.ZipFile(path) as archive:
            content = {name: archive.read(name) for name in archive.namelist()}
        content.update(replacements or {})
        content.update(extras or {})
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, raw in content.items():
                archive.writestr(name, raw)

    def publish(self, identifier, version=1, dependencies=None, overrides=None):
        return self.store.publish(self.package(identifier, version, dependencies, overrides))

    def request(self, requested, **kwargs):
        return dict(CLIENT, requested=requested, **kwargs)

    def test_verifies_unicode_manifest_and_rejects_untrusted_signer(self):
        record = inspect_package(self.package(), self.verifier)
        self.assertEqual("课表", record["manifest"]["title"])
        self.assertEqual(64, len(record["signerCertificateSha256"]))
        with self.assertRaisesRegex(MarketError, "not trusted"):
            inspect_package(self.package(key="untrusted"), self.verifier)

    def test_manifest_tampering_invalidates_signature(self):
        package = self.package()
        with zipfile.ZipFile(package) as archive:
            manifest = json.loads(archive.read("manifest.json"))
        manifest["capabilities"].append("CAMERA")
        self.rewrite(package, {"manifest.json": json.dumps(manifest).encode()})
        with self.assertRaisesRegex(MarketError, "not trusted"):
            inspect_package(package, self.verifier)

    def test_payload_tampering_is_rejected(self):
        package = self.package()
        self.rewrite(package, {"icon.png": b"ICON"})
        with self.assertRaisesRegex(MarketError, "hash mismatch"):
            inspect_package(package, self.verifier)

    def test_unlisted_files_traversal_and_duplicate_zip_paths_are_rejected(self):
        for name in ("unexpected.dex", "../escape", "/absolute", "lib\\escape"):
            package = self.package()
            self.rewrite(package, extras={name: b"extra"})
            with self.subTest(name=name), self.assertRaises(MarketError):
                inspect_package(package, self.verifier)
        package = self.package()
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(package, "a") as archive:
                archive.writestr("icon.png", b"another")
        with self.assertRaisesRegex(MarketError, "duplicate"):
            inspect_package(package, self.verifier)

    def test_debug_packages_and_invalid_ids_never_publish(self):
        with self.assertRaisesRegex(MarketError, "Development"):
            self.store.publish(self.package(overrides={"release": False}))
        with self.assertRaisesRegex(MarketError, "Invalid plugin id"):
            self.store.publish(self.package(overrides={"id": "../escape"}))
        self.assertEqual([], self.store.records())

    def test_version_is_immutable_even_after_withdrawal(self):
        package = self.package()
        record = self.store.publish(package)
        original, metadata = self.store.package("schedule", 1, record["download"]["sha256"])
        original_bytes = original.read_bytes()
        with self.assertRaisesRegex(MarketError, "immutable"):
            self.store.publish(package)
        self.assertEqual(original_bytes, original.read_bytes())
        self.store.withdraw("schedule", 1, "Broken release")
        with self.assertRaisesRegex(MarketError, "immutable"):
            self.store.publish(package)
        self.assertEqual([], self.store.records())
        self.assertEqual("Broken release", self.store.records(include_withdrawn=True)[0]["withdrawal"]["reason"])

    def test_compatibility_filters_host_runtime_sdk_and_abi(self):
        self.publish("schedule", 1)
        self.publish("schedule", 2, overrides={"hostApiVersion": 2})
        self.publish("schedule", 3, overrides={"runtimeVersion": "2"})
        self.publish("schedule", 4, overrides={"minSdk": 36})
        self.publish("schedule", 5, overrides={"abis": ["x86_64"]})
        catalog = self.store.catalog(Client(CLIENT))
        self.assertEqual([1], [item["manifest"]["versionCode"] for item in catalog["plugins"]])

    def test_solver_backtracks_incompatible_latest_versions(self):
        self.publish("account", 1)
        self.publish("account", 2)
        self.publish("schedule", 1, [{"id": "account", "minVersion": 1, "maxVersion": 1}])
        self.publish("schedule", 2, [{"id": "account", "minVersion": 2}])
        self.publish("grades", 1, [{"id": "account", "minVersion": 1, "maxVersion": 1}])
        result = self.store.resolve(self.request(["schedule", "grades"]))
        versions = {item["manifest"]["id"]: item["manifest"]["versionCode"] for item in result["plugins"]}
        self.assertEqual({"account": 1, "schedule": 1, "grades": 1}, versions)
        self.assertEqual("account", result["plugins"][0]["manifest"]["id"])

    def test_cycles_missing_dependencies_and_downgrades_fail(self):
        self.publish("schedule", 1, [{"id": "account", "minVersion": 1}])
        with self.assertRaisesRegex(MarketError, "No compatible"):
            self.store.resolve(self.request(["schedule"]))
        self.publish("account", 1, [{"id": "schedule", "minVersion": 1}])
        with self.assertRaisesRegex(MarketError, "No compatible"):
            self.store.resolve(self.request(["schedule"]))
        self.publish("weather", 1)
        with self.assertRaisesRegex(MarketError, "No compatible"):
            self.store.resolve(self.request(["weather"], installed={"weather": 2}))

    def test_updates_return_complete_plan_and_only_changed_downloads(self):
        self.publish("account", 1)
        self.publish("schedule", 2, [{"id": "account", "minVersion": 1}])
        result = self.store.resolve(dict(CLIENT, installed={"account": 1, "schedule": 1}), updates=True)
        self.assertEqual(2, len(result["plugins"]))
        self.assertEqual(["schedule"], [item["manifest"]["id"] for item in result["downloads"]])

    def test_installing_feature_preserves_dependencies_of_other_installed_features(self):
        self.publish("account", 1)
        self.publish("account", 2)
        self.publish("grades", 1, [{"id": "account", "minVersion": 1, "maxVersion": 1}])
        self.publish("schedule", 1, [{"id": "account", "minVersion": 1, "maxVersion": 1}])
        self.publish("schedule", 2, [{"id": "account", "minVersion": 2}])
        result = self.store.resolve(self.request(["schedule"], installed={"account": 1, "grades": 1}))
        versions = {item["manifest"]["id"]: item["manifest"]["versionCode"] for item in result["plugins"]}
        self.assertEqual({"account": 1, "grades": 1, "schedule": 1}, versions)

    def test_required_plugins_and_writer_lock(self):
        self.publish("account", 1)
        required = PackageStore(self.store.root, self.verifier, required=["account"])
        self.assertEqual("account", required.resolve(self.request([]))["plugins"][0]["manifest"]["id"])
        with self.store.writer_lock():
            with self.assertRaisesRegex(MarketError, "busy"):
                self.publish("schedule", 1)

    def test_http_catalog_resolution_range_etag_and_withdrawal(self):
        published = self.publish("schedule", 1)
        server = MarketServer(("127.0.0.1", 0), self.store)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:%s" % server.server_address[1]
        try:
            with urllib.request.urlopen(base + "/v1/plugins" + QUERY) as response:
                catalog = json.load(response)
            self.assertEqual("schedule", catalog["plugins"][0]["manifest"]["id"])
            with urllib.request.urlopen(base + "/v1/plugins/schedule" + QUERY) as response:
                self.assertEqual(1, len(json.load(response)["versions"]))
            request = urllib.request.Request(base + "/v1/resolve", json.dumps(self.request(["schedule"])).encode(),
                                             {"Content-Type": "application/json"})
            with urllib.request.urlopen(request) as response:
                self.assertEqual(1, len(json.load(response)["downloads"]))
            url = base + published["download"]["url"]
            with urllib.request.urlopen(urllib.request.Request(url, headers={"Range": "bytes=5-14"})) as response:
                self.assertEqual(206, response.status)
                self.assertEqual(10, len(response.read()))
                etag = response.headers["ETag"]
            with self.assertRaises(urllib.error.HTTPError) as unchanged:
                urllib.request.urlopen(urllib.request.Request(url, headers={"If-None-Match": etag}))
            self.assertEqual(304, unchanged.exception.code)
            with self.assertRaises(urllib.error.HTTPError) as invalid_range:
                urllib.request.urlopen(urllib.request.Request(url, headers={"Range": "bytes=999999-"}))
            self.assertEqual(416, invalid_range.exception.code)
            self.store.withdraw("schedule", 1, "Integration withdrawal")
            with self.assertRaises(urllib.error.HTTPError) as withdrawn:
                urllib.request.urlopen(url)
            self.assertEqual(410, withdrawn.exception.code)
            with self.assertRaises(urllib.error.HTTPError) as conflict:
                urllib.request.urlopen(request)
            self.assertEqual(409, conflict.exception.code)
            with self.assertRaises(urllib.error.HTTPError) as no_context:
                urllib.request.urlopen(base + "/v1/plugins")
            self.assertEqual(400, no_context.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()

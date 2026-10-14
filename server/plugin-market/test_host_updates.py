"""Host-feed tests use real RSA/bootstraps and a fixture APK identity reader."""
import base64
import json
import threading
import unittest
import urllib.error
import urllib.request
import zipfile
from unittest.mock import patch

import test_market as fixtures
from app import MarketServer
from host_updates import HOST_MARKER, HOST_PRODUCT, HostUpdateStore, MetadataSigner, AndroidApkVerifier, apk_certificate_digests
from manifest import MarketError


class ApkIdentityTests(unittest.TestCase):
    def test_sdk36_and_sdk37_signer_formats_exclude_source_stamp(self):
        digest = "ab" * 32
        for label in ("Signer #1", "V2 Signer:", "V3.1 Signer:"):
            self.assertEqual({digest}, apk_certificate_digests(label + " certificate SHA-256 digest: " + digest + "\r\n"))
        self.assertEqual(set(), apk_certificate_digests("Source Stamp Signer: certificate SHA-256 digest: " + digest))

    def test_verified_sdk37_identity_still_requires_the_trusted_apk_signer(self):
        verifier = AndroidApkVerifier.__new__(AndroidApkVerifier)
        verifier.aapt, verifier.java, verifier.apksigner = "aapt2", "java", "apksigner.jar"
        verifier.fingerprints = {"ab" * 32}
        identity = type("Result", (), {"returncode": 0, "stdout": "package: name='com.ahu.ahutong' versionCode='100' versionName='4.0.0'\nminSdkVersion:'26'\n"})()
        signed = type("Result", (), {"returncode": 0, "stdout": "V2 Signer: certificate SHA-256 digest: " + "ab" * 32})()
        with patch("host_updates.subprocess.run", side_effect=[identity, signed]):
            self.assertEqual(100, verifier("fixture.apk")["versionCode"])
        signed.stdout = "V2 Signer: certificate SHA-256 digest: " + "cd" * 32
        with patch("host_updates.subprocess.run", side_effect=[identity, signed]), self.assertRaises(MarketError):
            verifier("fixture.apk")


class HostUpdateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures.MarketTests.setUpClass()

    @classmethod
    def tearDownClass(cls):
        fixtures.MarketTests.tearDownClass()

    def setUp(self):
        self.fixture = fixtures.MarketTests()
        self.fixture.setUp()
        self.root = self.fixture.root
        self.hosts = HostUpdateStore(self.fixture.store)
        self.signer = MetadataSigner(private_key=fixtures.MarketTests.key_dir / "official.key", openssl=fixtures.MarketTests.openssl)
        self.identity = {"packageName": "com.ahu.ahutong", "versionCode": 100, "versionName": "4.0.0",
                         "minSdk": 26, "apkCertificateSha256": self.fixture.verifier.fingerprints}

    def tearDown(self):
        self.fixture.tearDown()

    def apk(self, marker=True):
        bootstrap = self.fixture.package("account")
        path = self.root / "host.apk"
        with zipfile.ZipFile(path, "w") as archive:
            if marker:
                archive.writestr(HOST_MARKER, json.dumps({"schemaVersion": 1, "product": HOST_PRODUCT,
                    "hostApiVersion": 1, "runtimeVersion": "1", "requiredPluginVersions": {"account": 1}}))
            archive.writestr("assets/bootstrap/account.ahup", bootstrap.read_bytes())
            archive.writestr("classes.dex", b"fixture identity supplied by test-only verifier")
        return path

    def publish(self, path=None, required=None):
        return self.hosts.publish(path or self.apk(), self.signer, self.fixture.verifier,
                                  lambda apk: self.identity, required=required, release_notes="完整宿主更新\n保留选择的组件")

    def test_exact_signed_metadata_and_complete_bootstrap_requirements(self):
        response = self.publish()
        raw = base64.b64decode(response["signedMetadata"])
        signature = base64.b64decode(response["signature"])
        self.fixture.verifier(raw, signature)
        metadata = json.loads(raw)
        self.assertEqual(response["metadata"], metadata)
        self.assertEqual({"account": 1}, metadata["requiredPluginVersions"])
        self.assertEqual(HOST_PRODUCT, metadata["product"])
        with self.assertRaisesRegex(MarketError, "not trusted"):
            self.fixture.verifier(raw.replace(b'"versionCode":100', b'"versionCode":101'), signature)

    def test_old_monolith_and_mismatched_bootstrap_never_enter_host_feed(self):
        with self.assertRaises(MarketError):
            self.publish(self.apk(marker=False))
        with self.assertRaisesRegex(MarketError, "Bootstrap plugin does not match"):
            self.publish(required={"account": 2})
        self.assertEqual({"schemaVersion": 1, "available": False}, self.hosts.update(0, 1, "1"))

    def test_apk_identity_and_family_filters(self):
        self.identity["packageName"] = "other.package"
        with self.assertRaisesRegex(MarketError, "packageName"):
            self.publish()
        self.identity["packageName"] = "com.ahu.ahutong"
        self.publish()
        self.assertFalse(self.hosts.update(100, 1, "1")["available"])
        self.assertFalse(self.hosts.update(0, 2, "1")["available"])
        self.assertFalse(self.hosts.update(0, 1, "2")["available"])
        self.assertFalse(self.hosts.update(0, 1, "1", 25)["available"])
        self.assertTrue(self.hosts.update(99, 1, "1", 26)["available"])

    def test_immutable_version_and_withdrawal(self):
        response = self.publish()
        with self.assertRaisesRegex(MarketError, "immutable"):
            self.publish()
        self.hosts.withdraw(100, "fixture rollback")
        self.assertFalse(self.hosts.update(99, 1, "1")["available"])
        with self.assertRaises(MarketError) as withdrawn:
            self.hosts.package(100, response["metadata"]["sha256"])
        self.assertEqual(410, withdrawn.exception.status)
        with self.assertRaisesRegex(MarketError, "immutable"):
            self.publish()

    def test_http_signed_update_and_range_download(self):
        published = self.publish()
        server = MarketServer(("127.0.0.1", 0), self.fixture.store)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:%s" % server.server_address[1]
        try:
            with urllib.request.urlopen(base + "/v1/host/update?versionCode=99&hostApiVersion=1&runtimeVersion=1&sdk=35") as response:
                update = json.load(response)
            self.fixture.verifier(base64.b64decode(update["signedMetadata"]), base64.b64decode(update["signature"]))
            self.assertEqual(100, update["metadata"]["versionCode"])
            self.assertEqual(1, update["schemaVersion"])
            request = urllib.request.Request(base + update["metadata"]["url"], headers={"Range": "bytes=0-7"})
            with urllib.request.urlopen(request) as response:
                self.assertEqual(206, response.status)
                self.assertEqual("application/vnd.android.package-archive", response.headers["Content-Type"])
                self.assertEqual(8, len(response.read()))
            with urllib.request.urlopen(base + "/v1/host/update?versionCode=100&hostApiVersion=1&runtimeVersion=1") as response:
                self.assertEqual({"schemaVersion": 1, "available": False}, json.load(response))
            self.hosts.withdraw(100, "withdrawn")
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(base + published["metadata"]["url"])
            self.assertEqual(410, error.exception.code)
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=3)


if __name__ == "__main__":
    unittest.main()

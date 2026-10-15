"""Protocol, path isolation, resource preservation and real RSA signing tests."""
import argparse
import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).resolve().parents[1] / "package_ahup.py"
spec = importlib.util.spec_from_file_location("package_ahup", str(SCRIPT))
pack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pack)


def dex_fixture(descriptor="Lexample/Plugin;"):
    # Minimal table fixture: tests parsing definitions, not Android execution.
    text = descriptor.encode("ascii")
    data = bytearray(152 + len(text) + 2)
    data[:8] = b"dex\n039\0"
    for offset, value in ((32, len(data)), (36, 112), (56, 1), (60, 112),
                          (64, 1), (68, 116), (96, 1), (100, 120), (112, 152)):
        struct.pack_into("<I", data, offset, value)
    data[152] = len(text)
    data[153:153 + len(text)] = text
    return bytes(data)


class PackageTests(unittest.TestCase):
    def test_linked_apk_resources_preserve_case_while_outer_payload_stays_windows_safe(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "linked.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("res/9n.9.png", b"one")
                archive.writestr("res/9N.9.png", b"two")
            self.assertEqual(2, len(pack.read_archive(path, android_apk=True)))
            with self.assertRaisesRegex(ValueError, "Duplicate"):
                pack.read_archive(path)
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payload = self.root / "payload"
        (self.payload / "dex").mkdir(parents=True)
        (self.payload / "dex/classes.dex").write_bytes(dex_fixture())
        self.output = self.root / "plugin.ahup"
        self.env = patch.dict(os.environ, {}, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)

    def args(self, *extra):
        return pack.parser().parse_args([str(self.root), str(self.payload), str(self.output),
                                        "--id", "example_plugin", "--title", "示例", "--entry", "example.Plugin"] + list(extra))

    def archive(self, name, entries):
        path = self.root / name
        pack.deterministic_zip(path, entries)
        return path

    def test_release_without_signing_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "Release signing"):
            pack.package(self.args())
        self.assertFalse(self.output.exists())

    def test_unsigned_debug_explicit_and_hashes_every_payload(self):
        (self.payload / "assets").mkdir()
        (self.payload / "assets/settings.json").write_bytes(b"{}")
        pack.package(self.args("--allow-unsigned-debug"))
        entries = pack.read_archive(self.output)
        manifest = json.loads(entries["manifest.json"])
        self.assertEqual(manifest["formatVersion"], 3)
        self.assertFalse(manifest["release"])
        self.assertEqual(entries["signature.sig"], b"")
        files = {row["path"]: row for row in manifest["files"]}
        self.assertEqual(set(files), set(entries) - {"manifest.json", "signature.sig"})
        for name, row in files.items():
            self.assertEqual(row["sha256"], hashlib.sha256(entries[name]).hexdigest())
            self.assertEqual(row["size"], len(entries[name]))

    def test_reproducible_unsigned_archive(self):
        pack.package(self.args("--allow-unsigned-debug"))
        first = self.output.read_bytes()
        pack.package(self.args("--allow-unsigned-debug"))
        self.assertEqual(first, self.output.read_bytes())

    def test_rejects_unsafe_paths(self):
        for name in ("../x", "/x", "a/../x", "a\\x", "C:/x", "x:y", "a//b", "a/./b", "a.", "a ", "NUL.so"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                pack.safe_path(name)

    def test_rejects_duplicate_zip_entries(self):
        path = self.root / "duplicate.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("File.dex", b"one")
            archive.writestr("file.dex", b"two")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            pack.read_archive(path)

    def test_rejects_symlink_zip_entries(self):
        path = self.root / "symlink.zip"
        with zipfile.ZipFile(path, "w") as archive:
            entry = zipfile.ZipInfo("assets/link")
            entry.external_attr = 0o120777 << 16
            archive.writestr(entry, b"../outside")
        with self.assertRaisesRegex(ValueError, "Symlink"):
            pack.read_archive(path)

    def test_rejects_unpacked_size_limit(self):
        path = self.archive("big.zip", {"a": b"1234", "b": b"5678"})
        with patch.object(pack, "MAX_PAYLOAD_BYTES", 7), self.assertRaisesRegex(ValueError, "size limit"):
            pack.read_archive(path)

    def test_apk_preserves_multidex_resources_assets_native(self):
        apk = self.archive("plugin.apk", {
            "classes.dex": dex_fixture(), "classes2.dex": dex_fixture("Lexample/Second;"),
            "AndroidManifest.xml": b"manifest", "resources.arsc": b"table", "res/drawable/a.xml": b"xml",
            "assets/data.json": b"{}", "lib/arm64-v8a/libsample.so": b"ELF", "META-INF/CERT.RSA": b"old",
            "META-INF/services/example.Service": b"example.Implementation"})
        args = self.args("--allow-unsigned-debug")
        args.source = str(apk)
        pack.package(args)
        entries = pack.read_archive(self.output)
        self.assertIn("dex/classes2.dex", entries)
        self.assertEqual(json.loads(entries["manifest.json"])["abis"], ["arm64-v8a"])
        with zipfile.ZipFile(io.BytesIO(entries["resources.apk"])) as resources:
            self.assertEqual(set(resources.namelist()), {"AndroidManifest.xml", "resources.arsc", "res/drawable/a.xml", "assets/data.json", "META-INF/services/example.Service"})
            table = resources.getinfo("resources.arsc")
            self.assertEqual(table.compress_type, zipfile.ZIP_STORED)
            self.assertEqual((table.header_offset + 30 + len(table.filename) + len(table.extra)) % 4, 0)

    def test_native_abi_filter_is_explicit(self):
        aar = self.archive("native.aar", {"classes.jar": b"jar", "jni/arm64-v8a/libx.so": b"a", "jni/x86_64/libx.so": b"b"})
        payload, jars = {}, []
        pack.gather_aar(aar, payload, jars, self.root, {"arm64-v8a"})
        self.assertEqual(set(payload), {"lib/arm64-v8a/libx.so"})

    def test_aar_embedded_jars_assets_and_native_preserved(self):
        aar = self.archive("dep.aar", {"classes.jar": b"classes", "libs/helper.jar": b"helper",
                                       "assets/config": b"data", "jni/x86_64/libdep.so": b"native"})
        payload, jars = {}, []
        self.assertFalse(pack.gather_aar(aar, payload, jars, self.root, set()))
        self.assertEqual([p.read_bytes() for p in jars], [b"classes", b"helper"])
        self.assertEqual(payload, {"assets/config": b"data", "lib/x86_64/libdep.so": b"native"})

    def test_resource_aar_without_linked_resources_fails(self):
        aar = self.archive("resource.aar", {"classes.jar": b"classes", "res/values/strings.xml": b"xml"})
        args = self.args("--allow-unsigned-debug")
        args.source = str(aar)
        with self.assertRaisesRegex(ValueError, "linked"):
            pack.package(args)

    def test_conflicting_assets_fail_instead_of_last_writer_wins(self):
        payload = {}
        pack.put_payload(payload, "assets/config", b"one")
        pack.put_payload(payload, "assets/config", b"one")
        with self.assertRaisesRegex(ValueError, "Conflicting"):
            pack.put_payload(payload, "assets/config", b"two")

    def test_rejects_entry_class_missing_from_dex(self):
        args = self.args("--allow-unsigned-debug")
        args.entry = "other.Plugin"
        with self.assertRaisesRegex(ValueError, "entryClass is absent"):
            pack.package(args)

    def test_rejects_shared_runtime_definitions(self):
        with self.assertRaisesRegex(ValueError, "forbidden host/shared"):
            pack.package(self.args("--allow-unsigned-debug", "--forbid-class-prefix", "example."))

    def test_rejects_duplicate_class_definitions(self):
        (self.payload / "dex/classes2.dex").write_bytes(dex_fixture())
        with self.assertRaisesRegex(ValueError, "duplicate classes"):
            pack.package(self.args("--allow-unsigned-debug"))

    def test_corrupt_dex_does_not_replace_previous_package(self):
        self.output.write_bytes(b"previous")
        (self.payload / "dex/classes.dex").write_bytes(b"invalid")
        with self.assertRaisesRegex(ValueError, "Invalid DEX"):
            pack.package(self.args("--allow-unsigned-debug"))
        self.assertEqual(self.output.read_bytes(), b"previous")

    def test_dependency_versions_are_inclusive_codes(self):
        self.assertEqual(pack.dependency("campus_auth:2:4"), {"id": "campus_auth", "minVersion": 2, "maxVersion": 4})
        for invalid in ("a:1", "plugin:0", "plugin:3:1", "plugin:x", "../auth:1"):
            with self.subTest(invalid=invalid), self.assertRaises(argparse.ArgumentTypeError):
                pack.dependency(invalid)

    def test_self_dependency_rejected(self):
        with self.assertRaisesRegex(ValueError, "self-referential"):
            pack.package(self.args("--allow-unsigned-debug", "--dependency", "example_plugin:1"))

    def test_technical_package_visibility_and_lifecycle_metadata_are_signed(self):
        args = self.args("--allow-unsigned-debug", "--hidden", "--auto-start", "--foreground", "--home-card")
        pack.package(args)
        manifest = json.loads(pack.read_archive(self.output)["manifest.json"])
        self.assertFalse(manifest["userVisible"])
        self.assertTrue(manifest["autoStart"])
        self.assertTrue(manifest["foreground"])
        self.assertTrue(manifest["homeCard"])
        self.assertTrue(json.loads(pack.make_manifest(self.args(), {"dex/classes.dex": dex_fixture()}, True))["userVisible"])

    def test_aio_configuration_reads_only_keystore_and_resolves_relative_path(self):
        keystore = self.root / "key.jks"
        keystore.write_bytes(b"fixture")
        config_file = self.root / "config.json"
        config_file.write_text(json.dumps({"keystore": {"path": "key.jks", "store_password": "test-store",
                                                       "key_alias": "test", "key_password": "test-key"},
                                           "server": {"password": "unrelated-must-not-copy"}}), encoding="utf-8")
        config = pack.signing_configuration(str(config_file))
        self.assertEqual(config["keystore"], str(keystore))
        self.assertNotIn("server", config)

    def test_credentials_go_to_stdin_not_process_arguments(self):
        config = {"keystore": "file.jks", "storePassword": "secret-one", "keyAlias": "ahup",
                  "keyPassword": "secret-two", "storeType": "JKS"}
        with patch.object(pack.subprocess, "run") as run:
            run.return_value.returncode = 0
            pack.sign("java", config, self.root / "manifest", self.root / "sig")
            arguments = run.call_args[0][0]
            self.assertNotIn("secret-one", " ".join(arguments))
            self.assertNotIn("secret-two", " ".join(arguments))
            fields = [base64.b64decode(line).decode("utf-8") for line in run.call_args[1]["input"].splitlines()]
            self.assertEqual(fields[1], "secret-one")
            self.assertEqual(fields[3], "secret-two")


class SigningIntegrationTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("java") and shutil.which("keytool"), "JDK required")
    def test_real_rsa_sign_verify_and_metadata_tamper(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            keystore = root / "test.jks"
            environment = dict(os.environ, AHUP_TEST_PASSWORD="temporary-test-only")
            generated = subprocess.run([shutil.which("keytool"), "-genkeypair", "-noprompt", "-alias", "test",
                                        "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=AHUP Test",
                                        "-storetype", "JKS", "-keystore", str(keystore),
                                        "-storepass:env", "AHUP_TEST_PASSWORD", "-keypass:env", "AHUP_TEST_PASSWORD"],
                                       env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            self.assertEqual(generated.returncode, 0, "Test key generation failed")
            config = {"keystore": str(keystore), "storePassword": environment["AHUP_TEST_PASSWORD"],
                      "keyPassword": environment["AHUP_TEST_PASSWORD"], "keyAlias": "test", "storeType": "JKS"}
            manifest, signature, certificate = root / "manifest.json", root / "signature.sig", root / "cert"
            manifest.write_bytes(b'{"id":"example","versionCode":1}')
            pack.sign(shutil.which("java"), config, manifest, signature)
            pack.sign(shutil.which("java"), config, certificate_path=certificate)
            verifier = root / "Verify.java"
            verifier.write_text('''import java.nio.file.*; import java.security.*; import java.security.cert.*; import java.util.*;
class Verify { public static void main(String[] a) throws Exception {
byte[] c = Base64.getDecoder().decode(Files.readString(Path.of(a[2])).trim());
var cert = CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(c));
var verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(cert.getPublicKey());
verifier.update(Files.readAllBytes(Path.of(a[0])));
System.exit(verifier.verify(Files.readAllBytes(Path.of(a[1]))) ? 0 : 2); }}''', encoding="utf-8")
            command = [shutil.which("java"), str(verifier), str(manifest), str(signature), str(certificate)]
            self.assertEqual(subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode, 0)
            production_verify = [shutil.which("java"), str(pack.SIGNER), "verify", str(certificate), str(manifest), str(signature)]
            self.assertEqual(subprocess.run(production_verify, stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode, 0)
            certificate.write_bytes(base64.b64decode(certificate.read_text().strip()))
            self.assertEqual(subprocess.run(production_verify, stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode, 0)
            manifest.write_bytes(b'{"id":"example","versionCode":2}')
            self.assertNotEqual(subprocess.run(production_verify, stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode, 0)


if __name__ == "__main__":
    unittest.main()

import hashlib
import base64
import importlib.util
import json
import io
from pathlib import Path
import struct
import sys
import unittest
import zipfile

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
import audit_plugin_bundle as audit


class ArtifactAuditTests(unittest.TestCase):
    def test_only_empty_r8_framework_factory_stubs_are_classified_as_platform_placeholders(self):
        name = "Landroid/app/AppComponentFactory;"
        stub = {"parents":["Ljava/lang/Object;"],"methods":{"<clinit>()V"},"fields":set()}
        self.assertTrue(audit.framework_factory_stub(name, stub, {name:{}}))
        self.assertFalse(audit.framework_factory_stub("Landroidx/RealRuntime;", stub, {name:{}}))
        self.assertFalse(audit.framework_factory_stub(name, dict(stub, methods={"<clinit>()V","instantiateActivity()V"}), {name:{}}))
        self.assertFalse(audit.framework_factory_stub(name, dict(stub, fields={"runtime:Ljava/lang/Object;"}), {name:{}}))
        self.assertFalse(audit.framework_factory_stub(name, stub, {}))

    def test_aapt_resource_names_remain_case_sensitive_inside_apk(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as archive:
            archive.writestr("res/0M.xml", b"first Android resource")
            archive.writestr("res/0m.xml", b"another Android resource")
            archive.writestr("res/aux.xml", b"never extracted to a Windows path")
        self.assertEqual(len(audit.read_apk(io.BytesIO(stream.getvalue()))), 3)
        with self.assertRaises(ValueError):
            audit.package.read_archive(io.BytesIO(stream.getvalue()))

    def test_both_numbered_and_scheme_named_apk_signers_are_parsed(self):
        digest = "ab" * 32
        self.assertEqual(audit.apk_certificate_digests("Signer #1 certificate SHA-256 digest: " + digest + "\r\n"), [digest])
        self.assertEqual(audit.apk_certificate_digests("V2 Signer: certificate SHA-256 digest: " + digest + "\r\n"), [digest])
        self.assertEqual(audit.apk_certificate_digests("V3.1 Signer: certificate SHA-256 digest: " + digest + "\n"), [digest])
        self.assertEqual(audit.apk_certificate_digests("Source Stamp Signer: certificate SHA-256 digest: " + digest), [])

    def test_trust_pin_has_one_digest_across_der_base64_and_pem_forms(self):
        der = b"\x30\x06fixture"
        self.assertEqual(audit.certificate_der(der), der)
        self.assertEqual(audit.certificate_der(base64.b64encode(der) + b"\n"), der)
        pem = b"-----BEGIN CERTIFICATE-----\n" + base64.b64encode(der) + b"\n-----END CERTIFICATE-----"
        self.assertEqual(audit.certificate_der(pem), der)

    def native_library(self):
        data = bytearray(768)
        data[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<HH", data, 16, 3, 183)
        struct.pack_into("<QQ", data, 32, 64, 128)
        struct.pack_into("<HHHH", data, 54, 56, 1, 64, 4)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 768, 768, 16384)
        jni = b"Java_com_ahu_ahutong_sdk_nativebridge_CampusCardNative_balance"
        strings = b"\0" + jni + b"\0libahup_campus_card.so\0"
        data[384:384 + len(strings)] = strings
        struct.pack_into("<IIQQQQIIQQ", data, 192, 0, 3, 0, 0, 384, len(strings), 0, 0, 1, 0)
        struct.pack_into("<IIQQQQIIQQ", data, 256, 0, 11, 0, 0, 512, 48, 1, 0, 8, 24)
        struct.pack_into("<IBBHQQ", data, 536, 1, 0x12, 0, 1, 1, 1)
        struct.pack_into("<IIQQQQIIQQ", data, 320, 0, 6, 0, 0, 600, 32, 1, 0, 8, 16)
        struct.pack_into("<qQ", data, 600, 14, len(jni) + 2)
        return bytes(data)

    def table(self, package_id):
        name = "example.plugin".encode("utf-16le").ljust(256, b"\0")
        chunk = struct.pack("<HHII", 0x200, 288, 288, package_id) + name + bytes(20)
        return struct.pack("<HHII", 2, 12, 12 + len(chunk), 1) + chunk

    def test_reads_actual_android_resource_package_id_without_text_dump(self):
        self.assertEqual(audit.resource_packages(self.table(0x8f)), [{"id": 0x8f, "name": "example.plugin"}])

    def test_invalid_resource_chunk_and_package_count_are_rejected(self):
        table = bytearray(self.table(0x8f))
        struct.pack_into("<I", table, 16, len(table) * 2)
        with self.assertRaises(ValueError):
            audit.resource_packages(table)
        table = bytearray(self.table(0x8f))
        struct.pack_into("<I", table, 8, 2)
        with self.assertRaises(ValueError):
            audit.resource_packages(table)

    def test_resolves_inherited_abi_and_rejects_missing_overloads(self):
        classes = {
            "Lshared/Base;": {"parents": ["Ljava/lang/Object;"], "methods": {"fetch(I)Ljava/lang/String;", "<init>()V"}, "fields": {"VERSION:I"}},
            "Lshared/Child;": {"parents": ["Lshared/Base;"], "methods": set(), "fields": set()}
        }
        self.assertTrue(audit.has_member(classes, "Lshared/Child;", "fetch(I)Ljava/lang/String;", "methods"))
        self.assertTrue(audit.has_member(classes, "Lshared/Child;", "VERSION:I", "fields"))
        self.assertFalse(audit.has_member(classes, "Lshared/Child;", "fetch(J)Ljava/lang/String;", "methods"))
        self.assertFalse(audit.has_member(classes, "Lshared/Child;", "<init>()V", "methods"))

    def test_payload_changes_and_unlisted_files_fail_inventory(self):
        payload = b"verified payload"
        manifest = {"formatVersion": 3, "id": "example", "release": True,
                    "signatureAlgorithm": "SHA256withRSA", "files": [
                        {"path": "dex/classes.dex", "size": len(payload), "sha256": hashlib.sha256(payload).hexdigest()}]}
        entries = {"manifest.json": json.dumps(manifest).encode(), "signature.sig": b"fixture", "dex/classes.dex": payload}
        self.assertEqual(audit.verify_inventory(entries)["id"], "example")
        with self.assertRaises(ValueError):
            audit.verify_inventory(dict(entries, **{"dex/classes.dex": b"tampered"}))
        with self.assertRaises(ValueError):
            audit.verify_inventory(dict(entries, **{"dex/classes2.dex": b"injected"}))

    def test_host_business_rules_keep_only_the_explicit_shared_http_exception(self):
        classes = {"Lcom/ahu/ahutong/data/crawler/net/RequestExecutor;",
                   "Lcom/ahu/ahutong/data/crawler/api/ycard/YcardApi;",
                   "Lcom/ahu/ahutong/plugin/account/AccountPlugin;",
                   "Lcom/ahu/ahutong/core/plugin/PluginMeta;"}
        self.assertEqual(audit.host_business_classes(classes), sorted([
            "Lcom/ahu/ahutong/data/crawler/api/ycard/YcardApi;",
            "Lcom/ahu/ahutong/plugin/account/AccountPlugin;"]))

    def test_native_domain_owner_digest_soname_and_jni_are_verified(self):
        data = self.native_library()
        entries = {"lib/arm64-v8a/libahup_campus_card.so": data}
        manifest = {"abis": ["arm64-v8a"]}
        index = {("campus_card", "arm64-v8a"): {
            "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
            "bridge": "com.ahu.ahutong.sdk.nativebridge.CampusCardNative"}}
        rows, errors = audit.audit_native(entries, "campus_card", manifest, index)
        self.assertEqual(errors, [])
        self.assertEqual(rows[0]["soname"], "libahup_campus_card.so")
        self.assertEqual(rows[0]["loadAlignments"], [16384])
        _, errors = audit.audit_native(entries, "account", manifest, index)
        self.assertTrue(any("wrongNativeDomainOwner" in error for error in errors))
        index[("campus_card", "arm64-v8a")]["sha256"] = "wrong"
        _, errors = audit.audit_native(entries, "campus_card", manifest, index)
        self.assertTrue(any("nativeBuildDigestMismatch" in error for error in errors))

    def test_four_kib_native_load_segments_are_reported(self):
        data = bytearray(self.native_library())
        struct.pack_into("<Q", data, 112, 4096)
        _, errors = audit.audit_native({"lib/arm64-v8a/libtest.so": bytes(data)}, "example", {"abis": ["arm64-v8a"]}, {})
        self.assertTrue(any("nativeNot16KiBAligned" in error for error in errors))

    def test_native_dependent_plugin_cannot_pass_with_an_empty_native_inventory(self):
        _, errors = audit.audit_native({}, "campus_card", {"abis": []}, {})
        self.assertTrue(any("requiredNativeLibraryMissing" in error for error in errors))
        _, errors = audit.audit_native({}, "storage_migration", {"abis": []}, {})
        self.assertTrue(any("requiredNativeLibraryMissing" in error for error in errors))
        self.assertEqual(audit.audit_native({}, "account", {"abis": []}, {}), ([], []))

    def test_dependency_diamond_is_valid_but_cycle_is_rejected(self):
        graph = {"root": {"dependencies": [{"id": "left"}, {"id": "right"}]},
                 "left": {"dependencies": [{"id": "account"}]},
                 "right": {"dependencies": [{"id": "account"}]}, "account": {}}
        self.assertEqual(audit.dependency_cycles(graph), [])
        graph["account"] = {"dependencies": [{"id": "root"}]}
        self.assertEqual(audit.dependency_cycles(graph), [["root", "left", "account", "root"]])


if __name__ == "__main__":
    unittest.main()

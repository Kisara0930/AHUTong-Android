import base64
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("ci_plugin_build", SCRIPTS / "ci_plugin_build.py")
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class CiPluginBuildTests(unittest.TestCase):
    def test_plan_builds_every_plugin_and_never_the_legacy_app(self):
        names = ci.plugins()
        self.assertIn("storage_migration", names)
        self.assertIn("student_mail", names)
        self.assertEqual(set(names), {task.split(":")[2] for task in ci.task_plan("plugins")})
        self.assertTrue(all(not task.startswith(":app:") for stage in ("plugins", "tests", "host") for task in ci.task_plan(stage)))
        self.assertIn(":host:lintRelease", ci.task_plan("host"))
        self.assertIn(":host:lintDebug", ci.task_plan("host"))

    def test_local_signing_is_refused_even_when_user_config_is_available(self):
        with patch.dict(os.environ, {"AHUP_SIGNING_CONFIG": "user-signing.json"}, clear=True):
            with self.assertRaisesRegex(ValueError, "isolated GitHub"):
                ci.signing_configuration()

    def test_ci_configuration_cannot_reference_an_external_keystore(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            runner = root / "runner"
            runner.mkdir()
            external = root / "user.jks"
            external.write_bytes(b"not-a-real-keystore")
            config = runner / "signing.json"
            config.write_text(json.dumps({"ciOnly": True, "keystore": str(external), "storePassword": "fixture",
                "keyPassword": "fixture", "keyAlias": "ahutong-ci", "storeType": "JKS"}), encoding="utf-8")
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "RUNNER_TEMP": str(runner), "AHUP_SIGNING_CONFIG": str(config)}, clear=True):
                with self.assertRaisesRegex(ValueError, "outside"):
                    ci.signing_configuration()

    def test_prepare_uses_a_fresh_ci_key_and_secret_environment_not_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            export_env = root / "github-env"
            public = root / "checkout/host/src/main/assets/ahup_trusted_certificate.der"
            captured = {}
            def fake_run(arguments, **kwargs):
                captured["arguments"] = arguments
                captured["password"] = kwargs["env"]["AHUTONG_CI_SIGNING_PASSWORD"]
                Path(arguments[arguments.index("-keystore") + 1]).write_bytes(b"fixture-ci-keystore")
                return type("Completed", (), {"returncode": 0})()
            def fake_sign(java, configuration, certificate_path=None):
                certificate_path.write_text(base64.b64encode(b"fixture-public-certificate").decode(), encoding="ascii")
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true", "RUNNER_TEMP": str(root), "GITHUB_ENV": str(export_env)}, clear=True), \
                    patch.object(ci, "CERTIFICATE", public), \
                    patch.object(ci.package_ahup, "java_executable", return_value=str(root / "jdk/bin/java")), \
                    patch.object(ci.subprocess, "run", side_effect=fake_run), \
                    patch.object(ci.package_ahup, "sign", side_effect=fake_sign):
                ci.prepare_signing()
            self.assertNotIn(captured["password"], " ".join(captured["arguments"]))
            self.assertNotIn(captured["password"], export_env.read_text())
            self.assertEqual(b"fixture-public-certificate", public.read_bytes())
            self.assertIn("AHUP_SIGNING_CONFIG=", export_env.read_text())


if __name__ == "__main__":
    unittest.main()

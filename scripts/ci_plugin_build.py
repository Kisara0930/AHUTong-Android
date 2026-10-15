#!/usr/bin/env python3
"""CI-only signing and explicit host/plugin builds. Never reads user signing keys."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile

import package_ahup

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE = ROOT / "host/src/main/assets/ahup_trusted_certificate.der"
SHARED_TESTS = ["runtime:shared", "core:common", "core:model", "core:storage", "core:auth", "core:network",
                "core:plugin-api", "data:personalization", "data:schedule", "data:grade", "data:update"]


def plugins():
    catalog_path = ROOT / "plugins/catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8")) if catalog_path.is_file() else None
    declared = re.findall(r'include\s*\(\s*["\']:plugins:([a-z][a-z0-9_]*)["\']\s*\)',
                          (ROOT / "settings.gradle.kts").read_text(encoding="utf-8"))
    if not declared or len(declared) != len(set(declared)):
        raise ValueError("Plugin list is empty or duplicated")
    if catalog and set(declared) != {item["id"] for item in catalog["components"]}:
        raise ValueError("Settings and official component catalog differ")
    for name in declared:
        if not (ROOT / "plugins" / name / "build.gradle.kts").is_file():
            raise ValueError("Declared plugin directory missing: " + name)
    return sorted(declared)


def ensure_ci():
    if os.environ.get("GITHUB_ACTIONS") != "true" or not os.environ.get("RUNNER_TEMP"):
        raise ValueError("Signing/build commands require an isolated GitHub Actions job")


def prepare_signing():
    ensure_ci()
    directory = Path(tempfile.mkdtemp(prefix="ahutong-ci-signing-", dir=os.environ["RUNNER_TEMP"]))
    java = package_ahup.java_executable()
    keytool = Path(java).parent / ("keytool.exe" if os.name == "nt" else "keytool")
    keystore = directory / "ci-only.jks"
    password = secrets.token_urlsafe(32)
    environment = dict(os.environ, AHUTONG_CI_SIGNING_PASSWORD=password)
    result = subprocess.run([str(keytool), "-genkeypair", "-keystore", str(keystore),
        "-alias", "ahutong-ci", "-storetype", "JKS", "-keyalg", "RSA", "-keysize", "2048",
        "-sigalg", "SHA256withRSA", "-validity", "2",
        "-dname", "CN=AHUTong Ephemeral CI Only,OU=Automated Tests,O=AHUTong",
        "-storepass:env", "AHUTONG_CI_SIGNING_PASSWORD", "-keypass:env", "AHUTONG_CI_SIGNING_PASSWORD", "-noprompt"],
        env=environment, capture_output=True)
    if result.returncode:
        raise ValueError("Could not create ephemeral CI signing key")
    configuration = {"ciOnly": True, "keystore": str(keystore), "storePassword": password,
                     "keyAlias": "ahutong-ci", "keyPassword": password, "storeType": "JKS"}
    path = directory / "ci-only-signing.json"
    path.write_text(json.dumps(configuration), encoding="utf-8")
    if os.name != "nt":
        path.chmod(0o600)
        keystore.chmod(0o600)
    exported = directory / "certificate.b64"
    package_ahup.sign(java, configuration, certificate_path=exported)
    CERTIFICATE.parent.mkdir(parents=True, exist_ok=True)
    CERTIFICATE.write_bytes(base64.b64decode(exported.read_text(encoding="ascii")))
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
        output.write("AHUP_SIGNING_CONFIG=" + str(path) + "\nPYTHON=" + sys.executable + "\n")
    print("Created a temporary CI RSA identity; replaced only the checkout's public trust asset.")


def signing_configuration():
    ensure_ci()
    path = Path(os.environ["AHUP_SIGNING_CONFIG"]).resolve()
    if (Path(os.environ["RUNNER_TEMP"]).resolve() not in path.parents
            or json.loads(path.read_text(encoding="utf-8")).get("ciOnly") is not True):
        raise ValueError("Refusing non-CI signing configuration")
    configuration = package_ahup.signing_configuration(path)
    if (Path(os.environ["RUNNER_TEMP"]).resolve() not in Path(configuration["keystore"]).resolve().parents
            or configuration["keyAlias"] != "ahutong-ci"):
        raise ValueError("Refusing a keystore outside the ephemeral CI directory")
    return configuration


def task_plan(stage):
    modules = plugins()
    if stage == "plugins":
        return [":plugins:%s:packageAhup" % name for name in modules]
    if stage == "tests":
        return [":host:testDebugUnitTest", ":bootstrap-kernel:test"] + [":%s:testDebugUnitTest" % name for name in SHARED_TESTS] + [":plugins:%s:testDebugUnitTest" % name for name in modules]
    if stage == "host":
        return [":host:assembleDebug", ":host:assembleRelease", ":host:lintDebug", ":host:lintRelease"]
    raise ValueError("Unknown build stage")


def gradle(stage):
    signing_configuration()
    wrapper = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    subprocess.run([wrapper, "--no-daemon", "--max-workers=2", "--stacktrace",
        "-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8", "-Pkotlin.compiler.execution.strategy=in-process",
        "-PahupPython=" + sys.executable] + task_plan(stage), cwd=ROOT, check=True)


def rust_tests():
    for feature in ("domain-account", "domain-schedule", "domain-grades", "domain-campus-card", "storage-migration"):
        subprocess.run(["cargo", "test", "--manifest-path", str(ROOT / "sdk/Cargo.toml"),
                        "--no-default-features", "--features", feature], cwd=ROOT, check=True)
    subprocess.run(["cargo", "test", "--manifest-path", str(ROOT / "sdk/Cargo.toml"), "--features", "server"], cwd=ROOT, check=True)


def collect(output):
    config = signing_configuration()
    output = Path(output).resolve()
    if ROOT not in output.parents:
        raise ValueError("CI artifact directory must be a child of the checkout")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("CI artifact directory must be empty; refusing to upload unrelated files")
    package_dir = ROOT / "dist/ahup"
    expected = plugins()
    if {path.stem for path in package_dir.glob("*.ahup")} != set(expected):
        raise ValueError("Plugin artifact set does not match settings.gradle.kts")
    spec = importlib.util.spec_from_file_location("ci_ahup_verifier", ROOT / "server/plugin-market/manifest.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    trust = module.CertificateVerifier([CERTIFICATE])
    records = []
    for name in expected:
        path = package_dir / (name + ".ahup")
        result = module.inspect_package(path, trust)
        if result["manifest"]["id"] != name:
            raise ValueError("Plugin artifact identity mismatch: " + name)
        shutil.copy2(path, output / path.name)
        records.append({"file": path.name, "sha256": result["sha256"], "size": result["size"]})
    sdk = package_ahup.sdk_directory(ROOT)
    build_tools = max((path for path in (sdk / "build-tools").iterdir() if path.is_dir()), key=package_ahup.version_key)
    zipalign = build_tools / ("zipalign.exe" if os.name == "nt" else "zipalign")
    apksigner = build_tools / "lib/apksigner.jar"
    environment = dict(os.environ, AHUTONG_CI_STORE_PASSWORD=config["storePassword"], AHUTONG_CI_KEY_PASSWORD=config["keyPassword"])
    java = package_ahup.java_executable()
    for variant in ("debug", "release"):
        sources = list((ROOT / "host/build/outputs/apk" / variant).glob("*.apk"))
        if len(sources) != 1:
            raise ValueError("Expected one host %s APK" % variant)
        with tempfile.TemporaryDirectory(prefix="ahu-ci-apk-", dir=os.environ["RUNNER_TEMP"]) as temporary:
            aligned = Path(temporary) / "aligned.apk"
            target = output / ("ahutong-host-%s-ci-only.apk" % variant)
            subprocess.run([str(zipalign), "-P", "16", "-f", "4", str(sources[0]), str(aligned)], check=True)
            subprocess.run([java, "-jar", str(apksigner), "sign", "--ks", config["keystore"],
                "--ks-key-alias", config["keyAlias"], "--ks-pass", "env:AHUTONG_CI_STORE_PASSWORD",
                "--key-pass", "env:AHUTONG_CI_KEY_PASSWORD", "--out", str(target), str(aligned)], env=environment, check=True)
            subprocess.run([java, "-jar", str(apksigner), "verify", "--verbose", str(target)], check=True)
            records.append({"file": target.name, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(), "size": target.stat().st_size})
    shutil.copy2(CERTIFICATE, output / "ci-trusted-certificate.der")
    (output / "manifest.json").write_text(json.dumps({"ciOnly": True, "artifacts": records}, ensure_ascii=False, indent=2), encoding="utf-8")
    (output / "CI-ONLY.txt").write_text("Ephemeral CI key, not production. Do not update an installed production app with these APKs.\nNo private key/signing configuration is included.\n", encoding="utf-8")
    print("Collected %s signed plugins and two CI-signed host APKs." % len(expected))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare-signing", "plan", "plugins", "tests", "host", "rust-tests", "collect"))
    parser.add_argument("--output", default=str(ROOT / "artifacts/ci"))
    args = parser.parse_args()
    if args.command == "prepare-signing": prepare_signing()
    elif args.command == "plan": print(json.dumps({stage: task_plan(stage) for stage in ("plugins", "tests", "host")}, indent=2))
    elif args.command == "rust-tests": rust_tests()
    elif args.command == "collect": collect(args.output)
    else: gradle(args.command)


if __name__ == "__main__":
    main()

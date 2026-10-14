#!/usr/bin/env python3
"""Build real Android domain SOs without the legacy all-business SDK feature."""
import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
from pathlib import Path

DOMAINS = {
    "account": ("domain-account", "AccountNative"),
    "schedule": ("domain-schedule", "ScheduleNative"),
    "grades": ("domain-grades", "GradesNative"),
    "campus_card": ("domain-campus-card", "CampusCardNative"),
    "storage": ("storage-migration", "StorageNative"),
}
TARGETS = {"arm64-v8a": "aarch64-linux-android", "armeabi-v7a": "armv7-linux-androideabi",
           "x86_64": "x86_64-linux-android", "x86": "i686-linux-android"}


def executable(name):
    found = shutil.which(name)
    if found:
        return found
    path = Path(os.environ.get("CARGO_HOME", Path.home() / ".cargo")) / "bin" / (name + (".exe" if os.name == "nt" else ""))
    if path.is_file():
        return str(path)
    raise RuntimeError("Executable not found: " + name)


def find_ndk(explicit):
    configured = explicit or os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if configured:
        ndk = Path(configured)
    else:
        sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
        sdk = Path(sdk) if sdk else Path.home() / "AppData/Local/Android/Sdk"
        choices = [item for item in (sdk / "ndk").glob("*") if (item / "source.properties").is_file()]
        if not choices:
            raise RuntimeError("Pass --ndk or configure ANDROID_NDK_HOME")
        ndk = max(choices, key=lambda item: tuple(int(part) for part in item.name.split(".") if part.isdigit()))
    if not (ndk / "source.properties").is_file():
        raise RuntimeError("NDK source.properties not found")
    return ndk.resolve()


def verify_artifact(path, expected_soname, bridge, readelf):
    raw = path.read_bytes()
    if not raw.startswith(b"\x7fELF"):
        raise RuntimeError("Not an ELF shared library: " + str(path))
    if b"com/ahu/ahutong/sdk/RustSDK" in raw:
        raise RuntimeError("Legacy RustSDK JNI registration leaked into " + path.name)
    for other_feature, other_bridge in DOMAINS.values():
        marker = ("com/ahu/ahutong/sdk/nativebridge/" + other_bridge).encode()
        if (marker in raw) != (other_bridge == bridge):
            raise RuntimeError("Unexpected JNI domain boundary in " + path.name + ": " + other_bridge)
    dynamic = subprocess.run([str(readelf), "--dynamic", str(path)], capture_output=True, text=True, check=True).stdout
    if "[" + expected_soname + "]" not in dynamic:
        raise RuntimeError("Distinct ELF SONAME missing: " + expected_soname)
    symbols = subprocess.run([str(readelf), "--dyn-syms", str(path)], capture_output=True, text=True, check=True).stdout
    if "JNI_OnLoad" not in symbols:
        raise RuntimeError("JNI_OnLoad export missing")
    headers = subprocess.run([str(readelf), "--program-headers", "--wide", str(path)], capture_output=True, text=True, check=True).stdout
    loads = [line.split() for line in headers.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(parts[-1], 16) < 16384 for parts in loads):
        raise RuntimeError("ELF is not aligned for 16 KiB Android pages")
    forbidden = [b"/api/check_update", b"studentExamInfoVms", b"studentTableVms", b"/xzxcard/qrcode"]
    allowed = {"GradesNative": {b"studentExamInfoVms"}, "ScheduleNative": {b"studentTableVms"},
               "CampusCardNative": {b"/xzxcard/qrcode"}}
    for marker in forbidden:
        if marker not in allowed.get(bridge, set()) and marker in raw:
            raise RuntimeError("Unrelated domain payload leaked into " + path.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--domains", nargs="+", choices=DOMAINS, default=list(DOMAINS))
    parser.add_argument("--abi", choices=TARGETS, default="arm64-v8a")
    parser.add_argument("--api", type=int, default=26)
    parser.add_argument("--ndk")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    output = (args.output or root / "native/build").resolve()
    output.mkdir(parents=True, exist_ok=True)
    logs = output / "logs"
    logs.mkdir(exist_ok=True)
    ndk = find_ndk(args.ndk)
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}[platform.system()]
    readelf = ndk / "toolchains/llvm/prebuilt" / host / "bin" / ("llvm-readelf.exe" if os.name == "nt" else "llvm-readelf")
    environment = dict(os.environ, ANDROID_NDK_HOME=str(ndk), ANDROID_NDK_ROOT=str(ndk))
    cargo = executable("cargo")
    environment["PATH"] = str(Path(cargo).parent) + os.pathsep + environment.get("PATH", "")
    target_root = root / "sdk/target"
    environment["CARGO_TARGET_DIR"] = str(target_root)
    index = output / "artifacts.json"
    previous = json.loads(index.read_text(encoding="utf-8"))["artifacts"] if index.exists() else []
    artifacts = [item for item in previous if item["abi"] != args.abi or item["domain"] not in args.domains]
    for domain in args.domains:
        feature, bridge = DOMAINS[domain]
        name = "libahup_" + domain + ".so"
        command = [cargo, "ndk", "-t", args.abi, "--platform", str(args.api), "rustc", "--release",
                   "--no-default-features", "--features", feature, "--",
                   "-C", "link-arg=-Wl,-soname," + name, "-C", "link-arg=-Wl,-z,max-page-size=16384"]
        print("Building " + domain + " (" + args.abi + ")", flush=True)
        logfile = logs / (domain + ".log")
        with logfile.open("w", encoding="utf-8") as stream:
            process = subprocess.run(command, cwd=str(root / "sdk"), env=environment, stdout=stream, stderr=subprocess.STDOUT)
        if process.returncode:
            print("\n".join(logfile.read_text(encoding="utf-8", errors="replace").splitlines()[-70:]))
            raise SystemExit(process.returncode)
        source = target_root / TARGETS[args.abi] / "release/libahutong_rs.so"
        destination = output / domain / "lib" / args.abi / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        verify_artifact(destination, name, bridge, readelf)
        artifacts.append({"domain": domain, "feature": feature, "abi": args.abi,
                          "path": str(destination), "size": destination.stat().st_size,
                          "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
                          "bridge": "com.ahu.ahutong.sdk.nativebridge." + bridge, "minSdk": args.api})
        print("Built " + name + " (" + str(destination.stat().st_size) + " bytes)", flush=True)
    (output / "artifacts.json").write_text(json.dumps({"schemaVersion": 1, "ndk": str(ndk), "artifacts": artifacts}, indent=2), encoding="utf-8")
    print("Artifact index: " + str(output / "artifacts.json"), flush=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Run the Rust domain tests on an explicitly selected connected Android device."""
import argparse
import os
import subprocess
from pathlib import Path

from build_domains import DOMAINS, TARGETS, executable, find_ndk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--domains", nargs="+", choices=DOMAINS, default=list(DOMAINS))
    parser.add_argument("--abi", choices=TARGETS, default="arm64-v8a")
    parser.add_argument("--ndk")
    args = parser.parse_args()
    devices = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=True).stdout
    print(devices, flush=True)
    if not any(line.split() == [args.serial, "device"] for line in devices.splitlines()):
        raise SystemExit("Requested Android device is not connected/authorized")
    root = Path(__file__).resolve().parent.parent
    ndk = find_ndk(args.ndk)
    cargo = executable("cargo")
    environment = dict(os.environ, ANDROID_NDK_HOME=str(ndk), CARGO_NDK_ADB_SERIAL=args.serial)
    environment["PATH"] = str(Path(cargo).parent) + os.pathsep + environment.get("PATH", "")
    logs = root / "native/build/logs"
    logs.mkdir(parents=True, exist_ok=True)
    for domain in args.domains:
        log = logs / ("test-" + domain + ".log")
        print("Testing " + domain + " on " + args.serial, flush=True)
        with log.open("w", encoding="utf-8") as stream:
            result = subprocess.run([cargo, "ndk-test", "-t", args.abi, "--platform", "26",
                                     "--no-default-features", "--features", DOMAINS[domain][0]],
                                    cwd=str(root / "sdk"), env=environment, stdout=stream, stderr=subprocess.STDOUT)
        text = log.read_text(encoding="utf-8", errors="replace")
        if result.returncode:
            print("\n".join(text.splitlines()[-70:]))
            raise SystemExit(result.returncode)
        for line in text.splitlines():
            if line.startswith("test result:"):
                print(line, flush=True)


if __name__ == "__main__":
    main()

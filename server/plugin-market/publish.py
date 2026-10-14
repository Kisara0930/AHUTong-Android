#!/usr/bin/env python3
"""Validate, atomically publish or withdraw official AHUP packages locally."""
import argparse
import json
import sys

from manifest import CertificateVerifier, MarketError, inspect_package
from store import PackageStore
from host_updates import AndroidApkVerifier, HostUpdateStore, MetadataSigner


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--openssl", default="openssl")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("verify", "publish"):
        command = commands.add_parser(name)
        command.add_argument("package")
        command.add_argument("--trusted-cert", action="append", required=True,
                             help="Configured official X.509 PEM/DER certificate; repeat for key rotation")
        if name == "publish":
            command.add_argument("--store", required=True)
    withdraw = commands.add_parser("withdraw")
    withdraw.add_argument("id")
    withdraw.add_argument("version", type=int)
    withdraw.add_argument("--store", required=True)
    withdraw.add_argument("--reason", required=True)
    listing = commands.add_parser("list")
    listing.add_argument("--store", required=True)
    host = commands.add_parser("publish-host", help="Publish a signed plugin-host APK and signed update metadata locally")
    host.add_argument("apk")
    host.add_argument("--store", required=True)
    host.add_argument("--trusted-cert", action="append", required=True)
    signing = host.add_mutually_exclusive_group(required=True)
    signing.add_argument("--signing-config", help="Existing AIO/AHUP JKS configuration; passwords are sent to Java via stdin")
    signing.add_argument("--private-key", help="Alternative PEM metadata signing key")
    host.add_argument("--key-pass-env")
    host.add_argument("--java")
    host.add_argument("--sdk")
    host.add_argument("--package-name", default="com.ahu.ahutong")
    host.add_argument("--required-plugin", action="append", help="Exact id:versionCode; default uses APK host marker")
    host.add_argument("--release-notes-file")
    host_withdraw = commands.add_parser("withdraw-host")
    host_withdraw.add_argument("version", type=int)
    host_withdraw.add_argument("--store", required=True)
    host_withdraw.add_argument("--reason", required=True)
    args = parser.parse_args()
    try:
        if args.command in ("verify", "publish"):
            verifier = CertificateVerifier(args.trusted_cert, args.openssl)
            if args.command == "verify":
                result = inspect_package(args.package, verifier)
            else:
                result = PackageStore(args.store, verifier).publish(args.package)
        elif args.command == "publish-host":
            from pathlib import Path
            from manifest import plugin_id, positive_int
            required = None
            if args.required_plugin is not None:
                required = {}
                for value in args.required_plugin:
                    identifier, version = value.split(":")
                    if identifier in required:
                        raise MarketError("Duplicate required plugin id")
                    required[plugin_id(identifier)] = positive_int(int(version), "required plugin version")
            verifier = CertificateVerifier(args.trusted_cert, args.openssl)
            signer = MetadataSigner(args.signing_config, args.private_key, args.java, args.openssl, args.key_pass_env)
            apk_verifier = AndroidApkVerifier(verifier.fingerprints, args.sdk, args.java)
            notes = Path(args.release_notes_file).read_text(encoding="utf-8") if args.release_notes_file else ""
            result = HostUpdateStore(PackageStore(args.store)).publish(args.apk, signer, verifier, apk_verifier,
                                                                         args.package_name, required, notes)
        elif args.command == "withdraw-host":
            result = HostUpdateStore(PackageStore(args.store)).withdraw(args.version, args.reason)
        elif args.command == "withdraw":
            result = PackageStore(args.store).withdraw(args.id, args.version, args.reason)
        else:
            result = PackageStore(args.store).records(include_withdrawn=True)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ValueError, OSError) as error:
        print(json.dumps({"error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

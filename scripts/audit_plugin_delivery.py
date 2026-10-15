#!/usr/bin/env python3
"""Audit one final host APK and every component in the official catalog.

python scripts/audit_plugin_delivery.py --host release.apk --plugins dist/ahup \
    --source-root . --report dist/plugin-delivery-audit.json

The report checks actual DEX definitions/references, payload digests, signatures,
native ownership and resource IDs. It does not build, install or use credentials.
Use audit_plugin_bundle.py for an intentionally incomplete intermediate set.
"""
import struct
import zipfile

from audit_plugin_bundle import main


if __name__ == "__main__":
    try:
        raise SystemExit(main(require_all=True, verify_host=True))
    except (ValueError, KeyError, IndexError, OSError, zipfile.BadZipFile, struct.error) as error:
        print("Delivery audit failed: %s" % error)
        raise SystemExit(1)

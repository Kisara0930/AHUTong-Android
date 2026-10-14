# AHUP v3 packaging

Use Python 3.8 or newer, a JDK 17 or newer, and the Android SDK. The packager
discovers the SDK through `--sdk`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, ancestor
`local.properties`, or standard user installation directories. Java is selected
through `--java`, `JAVA_HOME`, or `PATH`.

```powershell
$env:AHUP_SIGNING_CONFIG = 'D:\path\to\ignored\config.local.json'
python scripts/package_ahup.py export-certificate --out app/src/main/assets/ahup_trusted_pubkey.txt
./gradlew :plugins:campus_circle:packageAhup
```

The configuration accepts the AIO release configuration's `keystore` object:
`path`, `store_password`, `key_alias`, optional `key_password` and `type`.
Only that object is used; server credentials and release settings are ignored.
Alternatively use a JSON object containing `keystore`, `storePassword`,
`keyAlias`, optional `keyPassword` and `storeType`, or the `AHUP_KEYSTORE`,
`AHUP_STORE_PASSWORD`, `AHUP_KEY_ALIAS`, `AHUP_KEY_PASSWORD`, `AHUP_STORE_TYPE`
environment variables. Relative keystore paths resolve against the JSON file.
Never commit a signing configuration or keystore. Only export the public
certificate to the host trust asset. Signer credentials travel over stdin and
are absent from process arguments and build output.

Release packaging fails when signing is unconfigured. The explicit
`--allow-unsigned-debug` option creates a package marked `release: false` with an
empty signature; production installation and the market must reject it.

## Package contents and verification

- `manifest.json`: canonical UTF-8 metadata, including version codes, host API,
  runtime version, dependencies, SDK/ABI constraints and every payload hash/size.
- `signature.sig`: binary `SHA256withRSA` signature over the **exact manifest
  bytes**, without a second manually computed digest.
- `dex/classes.dex`, `dex/classes2.dex`, etc.: all plugin DEX files.
- `resources.apk`: linked Android resources and binary manifest, when present.
- `assets/`, `lib/<abi>/`, `icon.png`: plugin assets and native libraries.

The manifest's `files` list covers all archive entries except the manifest and
signature themselves. Packages carry no self-authorizing public certificate.
The host pins the certificate exported from the configured JKS. The packager
checks the entry class is actually defined in the DEX files and rejects duplicate
class definitions. Repeat `--forbid-class-prefix` to reject copies of runtime
classes supplied by the host. ZIP traversal, duplicate paths, symlinks and
conflicting assets are rejected. Output replacement is atomic, so a failed
build leaves the previous package intact.

`--home-card`, `--foreground` and `--auto-start` declare the plugin's UI and
lifecycle contributions. `--hidden` sets `userVisible: false` for technical
dependencies such as legacy storage migration. These flags are signed metadata.
The ZIP and total extracted content must each fit 256 MiB, each file 128 MiB,
the manifest 1 MiB, and the archive 4096 entries, matching runtime and market limits.

## Android resources and shared code

For a business plugin, build an independent `com.android.application` linking
module and pass its APK as the input. The intermediate APK is not installed.
Declare the feature implementation with `isTransitive = false`; declare host
APIs and shared Compose/Kotlin runtime dependencies as `compileOnly`. The final
APK must contain only the plugin implementation and its private dependencies.
Audit actual DEX definitions as well as Gradle dependency declarations.

Give each plugin a stable resource package ID (`0x80` and above); the host keeps
`0x7f`. Set it through the public AGP DSL:

```kotlin
android {
    androidResources {
        additionalParameters += listOf("--package-id", "0x92")
    }
}
```

The circle plugin reserves `0x92`. Do not reuse it for another plugin. Its R
classes must come from the same Android link operation as its resource table.
For resources whose numeric IDs are persisted externally, use AAPT2's
`--emit-ids` and version-controlled `--stable-ids` mapping. IDs used only inside
one version's matching DEX/resources do not require an external stable-ID file.

The packager preserves the APK's binary manifest, resource table, `res/` and
`assets/` inside `resources.apk`, removing its DEX/native/signature entries.
Assets also remain directly addressable under the installed plugin directory.
The runtime must select plugin resources for plugin resource IDs and host
resources for host IDs, while using the plugin class loader for its classes.

AAR inputs support classes.jar, embedded jars, native libraries and assets;
repeat `--extra-aar` or `--extra-jar` for plugin-owned dependencies. An AAR with
Android resources requires a separately linked `--resources-apk` and matching R
classes. Merely zipping raw XML or copying an AAR's resources is not valid.

The relevant public interfaces are [AAPT2 link options](https://developer.android.com/tools/aapt2)
and [AGP AndroidResources.additionalParameters](https://developer.android.com/reference/tools/gradle-api/8.3/null/com/android/build/api/dsl/AndroidResources).

## Delivery audit

Run against the actual signed host APK and the directory containing all 21 AHUPs:

```powershell
python scripts/audit_plugin_delivery.py --host path/to/release.apk --plugins dist/ahup --source-root . --report dist/plugin-delivery-audit.json
```

The final delivery command also invokes Android SDK apksigner to verify the APK
signature and requires its certificate to match the plugin trust pin. Use
`--apksigner-jar` if SDK discovery cannot locate the installed verifier.

This reads actual DEX classes and method/field references, verifies each exact
manifest signature using the host's bundled certificate, verifies every payload
hash, checks entry classes, dependency versions, Android resource package IDs,
shared class duplication and shared ABI preservation after host R8. It also
checks native ELF architecture, 16 KiB load segments, SONAME, exported JNI
domain ownership and the recorded native build digest. The account/bootstrap
and migration packages embedded in the host must match the delivered packages.
No credentials, build, network or device are used. The JSON lists file hashes
and exact shared ABI symbols so the report is attributable to one artifact set.

Host business exclusion rules reject both new plugin namespaces and old feature,
screen, view-model, gateway, repository and Rust SDK namespaces; only the
explicitly shared crawler/net HTTP transport is excepted. Add narrow
`--host-deny-prefix` / `--host-allow-prefix` rules when changing this boundary.
Generated R/BuildConfig collisions are listed separately but still fail the
shared-class check: identical names alone cannot prove matching resource IDs.
Private classes shared by isolated sibling plugins are reported separately;
duplicates between a plugin and its dependency fail the audit.

For intentionally incomplete intermediate output, use `audit_plugin_bundle.py`
with the same arguments. It omits the full-catalog/21-package requirement.

## Tests

```powershell
python -m unittest discover -s scripts/tests -v
```

The RSA integration test requires both `java` and `keytool` on `PATH` and creates
only temporary test credentials. Other tests use no network or local release
credentials. Full Android integration still requires assembling and loading a
real plugin on the supported device/API range.

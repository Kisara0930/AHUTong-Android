# Minimal initialization APK

The native APK owns initialization, consent, catalog/download, signature and
dependency verification, transactional installation, code loading, repair and
system component bridges. It has no Kotlin, Compose or third-party runtime
dependency. Its outer ABI is `core:host-api`: Android/JDK arguments and `View`.

Foundation AHUPs are `shared_runtime` (the common SDK), `ui_runtime` (the original
application presentation and Android integration), `account`, and `appearance`.
Optional `camera_runtime` and `content_runtime` supply CameraX and rich content.
Feature packages depend on the shared runtime and any optional library they use.
The existing Compose contracts become internal runtime SDK contracts; they do
not appear in the native APK or its public ABI.

Each process owns one cached loader for each selected component version. Runtime
classes have one owner. Dependencies resolve before initialization; incompatible
runtime/host APIs are rejected, never silently substituted with old packages.
Code and resource paths are verified before loading; executable cache copies are
read-only. Installation archives are temporary and are removed after extraction.
Private minified APK classes live in a bootstrap-specific package, separate from
independently minified SDK names. The delivery audit also identifies R8's empty
API-28 AppComponentFactory placeholder by its exact shape; android.* always
delegates to the boot/APK loader. Actual shared implementation duplicates fail.
Active/previous/good pointers commit atomically; data-version guards remain.
Coupled SDK upgrades can restore the entire previous compatible selection in one
transaction. A failed foundation startup records its version set in the native
kernel, so another launch opens repair without executing the same failing set.
Installing changed foundation versions, rolling back, or explicitly choosing
manual retry makes another startup possible; no user database is removed.
Interrupted market setup retains the pending feature selection and range-download
files, while successful installation removes its temporary archives. Destroying
the native activity cancels its worker and disposes extracted local candidates.

Use framework component factories on supported Android versions to instantiate
the original runtime activities/services/providers. The native initialization
activity is the fallback while mandatory components are missing or quarantined.
Keep an Android 8 compatibility adapter isolated from the modern factory path.
Load foundation resource APKs with unique package IDs (host 0x7f, SDK 0x70,
UI 0x71, camera 0x72, content 0x73); business IDs remain 0x80 and above.
Public resource context creation must provide the same resource table to the
application and runtime activity. Widget RemoteViews use the native bridge's
small framework-only templates so launchers do not need plugin classloaders.

Preserve all original UI and business source, account generation fences, old
encrypted data and migration, disabled-feature behavior, background work and
isolated web processes. The account cold-start fix (`81476bf`) is the baseline:
persisted identity restores in-memory login state without clearing explicit
expiry. Native initialization must not access credentials before consent and
storage migration.

The official catalog is the source for package inventory, resource allocation,
dependency closure, CI and auditing. Counts are derived, never fixed at 21.
Measure the bare APK, compressed mandatory closure download, selected closure
payload/native/resources, executable cache copies, and retained rollback bytes
separately. Report device/emulator verification and any remaining limits.

# Plugin platform implementation

Approved scope: the application shell, all existing business functionality as
independently installable official plugins, the local marketplace service and
publication tools, old-user migration, and signed local test artifacts. Production
deployment and changing the AIO release gitlink are outside this task.

## Delivery invariants

- The base APK contains the stable runtime, Android system bridges, default UI,
  recovery/update functionality, and signed mandatory bootstrap packages.
- Optional business implementation classes, resources and native code must be
  absent from the base APK and mandatory package dependency closure.
- An installation is not an activation. A catalog entry must not execute plugin
  code. Disabled components cannot start business jobs, requests or prefetches.
- Official packages are signed. The exact UTF-8 `manifest.json` bytes are signed
  using SHA256withRSA in binary `signature.sig`. The signed manifest enumerates
  every payload file with path, byte length and SHA-256. Package-provided keys do
  not establish trust.
- Format 3 metadata includes id, title, summary, author, versionCode, versionName,
  hostApiVersion (1), runtimeVersion ("1"), minSdk (26), ABIs, required flag,
  dependencies, capabilities, entryClass and files. Identifiers use `[a-z0-9_]+`.
- Installation uses immutable version directories and atomic selection of a
  compatible dependency set. Keep the previous working set. Code rollback must
  account for database compatibility. Native changes take effect on restart.
- Credentials and old application data survive migration. Disabled/uninstalled
  business data is not silently removed. Account changes fence late results and
  clean credentials even for components that are not loaded.

## Component inventory

Mandatory: `account` and `appearance` (the original default interface and themes).
The host preserves the original homepage, bottom navigation, icon grid and
settings presentation; initialization and plugin management are the added flows.
Authentication protocols are owned by the account package; there
is no resident monolithic `campus_runtime` dependency.

Optional: `schedule`, `grade_exam`, `evaluation`, `program_completion`,
`free_classroom`, `campus_card`, `electricity`, `bathroom`, `network_recharge`,
`chaoxing`, `repository`, `student_mail`, `campus_notices`, `weather`,
`campus_guide`, `lost_found`, `campus_circle`, `personalization`.

Technical migration package: `storage_migration`, hidden from feature selection
and embedded separately from bootstrap. It runs only when legacy GuiXu data or
an unfinished migration exists, verifies the encrypted backup and destination,
then disables itself. Existing APK assets cannot be physically removed; imported
or downloaded installation archives are deleted after verified extraction.

The distributable module is `:host`. The old `:app` source tree remains as the
migration/reference implementation; only its plugin runtime, shell and generic
platform bridges are source inputs to `:host`. No old business source directory
is compiled into the host. CI and local delivery build `:host` plus `:plugins:*`.
The shared Android/Kotlin/Compose SDK is kept binary compatible in R8. Resource
shrinking is disabled in the host because separately linked plugin code and
RemoteViews refer to SDK resources that the host shrinker cannot discover.

Each component owns its UI, repositories/adapters, data, settings, resources,
home contributions, background handlers and migration. System declarations stay
in the shell and dispatch through checked plugin identifiers and handler IDs.
Public domain contracts are separate from implementations. Existing undergraduate
and postgraduate capabilities are preserved; this is not a new-feature project.

## Work and verification

1. Establish build baseline, component ownership and public contracts.
2. Implement the format, reproducible packaging and resource/classloader boundary.
3. Implement verified installation, activation, persistence and recovery.
4. Extract the shell and system bridges; migrate all business code and native code.
5. Deliver catalog/resolution/download/update service and publication CLI.
6. Complete mandatory initialization, local import and old-user migration.
7. Verify boundary/package contents, per-plugin tests, release shrinking, device
   startup/background/account lifecycle, corruption/interruption and rollback.
8. Put the signed release APK, every signed `.ahup`, catalog, hashes and test
   instructions in one local delivery directory.

No component is complete merely because a descriptor or wrapper exists. The
complete implementation and original behavior must be present in its package.

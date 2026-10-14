# Host and plugin CI

`.github/workflows/ci.yaml` builds the deliverable `:host` and every declared `:plugins:*` module. It does not build the legacy `:app` as a deliverable. Push/PR checks include `develop`.

Pipeline order: initialize only `sdk` and `GuiXu-Rust`; run packaging/market Python tests; run each Rust domain with defaults disabled and the legacy server compatibility tests; build real arm64 SOs and verify ELF/JNI/16 KiB boundaries; create CI signing identity; package all AHUPs; run explicit host/shared/plugin unit tests; build and lint Debug and Release host APKs; verify and collect artifacts.

`python scripts/ci_plugin_build.py plan` prints the concrete task graph without invoking Gradle or signing. Build/signing commands require a GitHub Actions context. `prepare-signing` creates a new RSA JKS under `RUNNER_TEMP`, exports its public certificate into the checkout's host trust asset, and writes only the temporary configuration path to `GITHUB_ENV`. Passwords go through process environment/stdin and are never command arguments or output. The original user/AIO configuration is not read. Tests ensure an external keystore cannot be disguised as a CI configuration.

The public trust asset changes only in the CI checkout. APKs and AHUPs are signed with the temporary key, validated, and collected under ignored `artifacts/ci/`. CI uploads only that directory and reports, never the keystore/configuration in `RUNNER_TEMP`. The artifact name and `CI-ONLY.txt` explicitly identify test signing; these APKs must not be used to update an installed production-signed application.

All action revisions remain pinned. A local validation can run `python -m unittest discover -s scripts/tests -p test_ci_plugin_build.py -v` and inspect `plan`; running the actual hosted workflow requires pushing the completed branch. SDK source changes must be committed/pushed in the SDK subrepository before the Android gitlink is committed, so a clean runner can retrieve the native feature implementation.

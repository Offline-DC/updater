# updater

The dumb phone updater (`com.offlineinc.dumbdownlauncher.updater`): a tiny,
dependency-free APK whose one screen (the duck) stays in front while the
launcher replaces its own package during a self-update.

## Download

Latest release, always at the same URL:

    https://github.com/Offline-DC/updater/releases/latest/download/updater.apk

Releases are tagged `v<versionCode>` (e.g. `v4`).

## Relationship to the launcher

The launcher repo still has the same module at `launcher/updater` and still
bundles it as `assets/updater.apk`; `UpdaterAppInstaller` installs that copy
when the phone's is missing or has a lower versionCode. This repo builds the
same module from the same source, so the APK here is the one the launcher
bundles, and either can be installed over the other:

- **Same signing key.** The workflow signs with the launcher's release key
  (same `KEYSTORE_*` secrets) and fails if the result's certificate differs from
  the updater inside the latest public launcher release.
- **Same versionCode.** Keep `versionCode` in `updater/build.gradle.kts` in step
  with the launcher's copy. When you change the screen, change it in both
  places and bump versionCode in both.

## Releasing

Push to `main`. `.github/workflows/release.yml` builds, signs, verifies and, if
`v<versionCode>` doesn't exist yet, publishes a GitHub Release with
`updater.apk`. Pushes that don't bump versionCode only upload a workflow
artifact: phones compare versionCodes, so a new APK under an old versionCode
would never reach them.

Required repository secrets (copy from the launcher repo, same values):
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Building locally

    ./gradlew :updater:assembleRelease

Without `KEYSTORE_PATH` set this produces an unsigned APK, as in the launcher.

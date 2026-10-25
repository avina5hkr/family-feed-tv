# Facebook Feed

An Android TV app for my mom that opens Facebook's mobile site (m.facebook.com) in a full-screen WebView, so she can browse the feed and watch videos and Reels using only the TV remote.

- D-pad moves a visible focus ring between posts and buttons, and OK clicks.
- Back closes fullscreen video, then goes back in history.
- Login is kept across restarts and reboots.
- Builds are signed and published as GitHub Releases, and the app checks for new releases and asks before installing.

Tested on a TCL 55C6K (Google TV, Android 14). Install with `adb install -r facebook-feed.apk`, or download the APK from the Releases page.

Not affiliated with Facebook or Meta.

## Debug vs release builds

- `./gradlew assembleDebug` builds `com.avinash.facebookfeed.debug`, labelled "Facebook Feed (debug)", signed with
  the local debug key. It installs next to the release app, with its own login.
- `./gradlew assembleRelease` builds `com.avinash.facebookfeed`. It is signed only when `KEYSTORE_PATH`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` are set in the environment; otherwise the APK is unsigned.
  No keystore or password is ever stored in the repository.
- The version comes from `-PversionName=1.2.3 -PversionCode=10203` (defaults `0.0.0-dev` and `1`).

## Releasing

Releases are built and signed only by GitHub Actions (`.github/workflows/release.yml`), using the `release`
environment's secrets `ANDROID_SIGNING_KEY_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD` and the variable `ANDROID_SIGNING_CERT_SHA256`.

- **Release:** push a tag like `v1.0.0` on a commit that is on `main`. CI builds and signs the APK, checks its
  certificate fingerprint and package, and publishes a GitHub Release with `facebook-feed.apk` and
  `facebook-feed.apk.sha256`. The version code is `MAJOR*10000 + MINOR*100 + PATCH`.
- **Dry run:** `gh workflow run release.yml --ref main`. It builds and signs exactly like a release and uploads the
  APK as a workflow artifact (kept for 1 day), but creates no tag and no release.

`scripts/check-cert.sh <apk> <sha256>` is the fingerprint check the workflow uses; it also works locally.

## Repository layout

- `app/src/main/assets/inject.js`: the remote-control navigation injected into the page.
- `tools/icon/Icon.java`: generates the launcher icon and TV banner
  (`java -Djava.awt.headless=true tools/icon/Icon.java app/src/main/res <preview dir>`).

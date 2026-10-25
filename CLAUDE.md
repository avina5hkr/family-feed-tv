# Standing rules: devices and installs (apply to every task)

Two apps on the TV:
- `com.avinash.facebookfeed` = the signed RELEASE app my mother uses. Hands off.
- `com.avinash.facebookfeed.debug` = the DEBUG app. All testing happens here.

## Release app (`com.avinash.facebookfeed`)

- Never uninstall it, clear its data (`pm clear`), force-stop it, downgrade it, or install anything over it, unless
  my current message explicitly asks for a release install and names the tag.
- Never install a locally built release APK on the TV (no `./gradlew installRelease`, no `adb install` of
  `app-release.apk`).
- A release install means: download `family-feed.apk` and `family-feed.apk.sha256` from the GitHub Release for the
  tag I name, verify the checksum, verify the certificate SHA-256 is
  `7A:86:7D:11:71:21:47:72:E2:FE:FA:9D:3B:B4:F5:6A:39:53:FC:B9:31:8F:D4:02:36:48:DC:E3:6E:4F:04:50`,
  then `adb install -r`.
- If a release install fails (signature mismatch, downgrade, anything), stop and tell me. Never fix it by
  uninstalling.
- Read-only commands on it are fine: `dumpsys`, `meminfo`, `logcat`.
- The release app can also update itself when a person presses 9 on the TV (key 9, see README). Never send key 9,
  or anything else that starts an update, to the release app; that is my decision and my mother's action only.

## Debug app (`com.avinash.facebookfeed.debug`)

- Build and install freely for testing: `./gradlew installDebug` or `adb install -r app-debug.apk`.
- Avoid uninstalling it or clearing its data, because that erases its Facebook login. If you think it's needed,
  ask me first.
- Login, logout and CAPTCHA tests happen only in the debug app.
- Key 9 (the updater) is tested in the debug app, on the TV or in the emulator, against a fake release served from
  the Mac (`update_api` extra, see README).

## Emulator

- `FamilyFeedTV` is a Google TV (Android 14, 1920x1080) emulator on the Mac, for testing when the TV is off.
  When both are connected, address each device explicitly (`ANDROID_SERIAL=emulator-5554`).

## Check before every install, uninstall or clear

Before any adb command that installs, uninstalls or clears a package, print the package name and check it ends in
`.debug`. If it doesn't, stop unless my current message explicitly asked for that release action.

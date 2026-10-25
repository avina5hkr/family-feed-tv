# Family Feed

An Android TV app for my mom that opens Facebook's website (www.facebook.com, desktop layout) in a full-screen WebView, so she can browse the feed and watch videos and Reels using only the TV remote.

- D-pad moves a visible focus ring between posts and buttons, and OK clicks.
- Back closes fullscreen video, then goes back in history.
- Login is kept across restarts and reboots.
- Builds are signed and published as GitHub Releases.

Tested on a TCL 55C6K (Google TV, Android 14). Install with `adb install -r family-feed.apk`, or download the APK from the Releases page.

Not affiliated with Facebook or Meta.

## Remote controls

All keys are handled inside the app only; no system-wide remote settings are changed.

### Feed

| Key | Action |
|---|---|
| Up / Down | Move through the parts of a post (photo or video, "See more", Like / Comment / Share, comments when shown), then on to the next post. Above the first post: the "What's on your mind" box and Stories, then the top bar |
| Left / Right | Move along the Like / Comment / Share row (and between Stories or other cards) |
| OK | Open what is selected. On a video: play it large over the page (see Videos) |
| CH ∨ / CH ∧ | Scroll one screen down / up; hold to scroll continuously |
| Back | Jump to the top bar (Home tab); Back again on the top bar closes the app |

On the top bar, Left / Right move between tabs (Home, Reels, Friends...), Down goes back into the page and OK
opens the tab.

### Reels

| Key | Action |
|---|---|
| Left / Right | Seek back / forward 10 seconds (hold for a steady repeat) |
| Up / Down, CH ∧ / CH ∨ | Previous / next Reel; it starts playing by itself |
| OK | Play / pause |
| OK held (0.8 s) | Button mode: a "Buttons: Back to return" banner, and the arrows move the focus ring between Like, Comment, Share and the other buttons; OK presses the selected one |
| Back | In button mode: back to watching the same Reel. Otherwise: back to the feed in one step, however many Reels were watched |

Button mode also ends with another long OK, or after 8 seconds without a key. Sound is always switched on. While
seeking, a "+10s" / "-10s" flash and a progress bar appear for a moment.

### Videos in the feed

| Key | Action |
|---|---|
| OK | Play / pause (the video fills the screen below the top bar) |
| Left / Right | Seek back / forward 10 seconds |
| Back | Return to the feed (the video pauses) |

### Comments, menus and other pages

The arrows move the focus ring to the nearest button or link in that direction, and OK opens it. Opening a post's
comments (Blue, or OK on Comment) scrolls to the first comment. CH ∨ / CH ∧ scroll the comments (hold for
continuous scrolling). Back leaves a text box first, then closes the comments or menu.

### Shortcut keys

| Key | Action |
|---|---|
| Red | Home feed (from the feed itself: back to the first post) |
| Green | Reels |
| Yellow | Like / unlike the current post or Reel |
| Blue | Open the comments of the current post or Reel |
| Menu (≡) | Jump to the top bar |
| 0 | Reload the page, if Facebook gets stuck |
| Play/Pause, Rewind, Fast-forward | Control the current video, on remotes that have these keys |

Leaving the app, with Back from the top bar or with the Home button, closes it completely and frees its memory;
the login is kept.

## Debug vs release builds

- `./gradlew assembleDebug` builds `com.avinash.facebookfeed.debug`, labelled "Family Feed (debug)", signed with
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
  certificate fingerprint and package, and publishes a GitHub Release with `family-feed.apk` and
  `family-feed.apk.sha256`. The version code is `MAJOR*10000 + MINOR*100 + PATCH`.
- **Dry run:** `gh workflow run release.yml --ref main`. It builds and signs exactly like a release and uploads the
  APK as a workflow artifact (kept for 1 day), but creates no tag and no release.

`scripts/check-cert.sh <apk> <sha256>` is the fingerprint check the workflow uses; it also works locally.

## Repository layout

- `app/src/main/assets/inject.js`: the remote-control navigation injected into the page.
- `tools/icon/Icon.java`: generates the launcher icon and TV banner
  (`java -Djava.awt.headless=true tools/icon/Icon.java app/src/main/res <preview dir>`).

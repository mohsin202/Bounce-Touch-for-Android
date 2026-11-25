# Bounce Touch - native Android port (work in progress)

No emulator. Levels, textures and geometry are decoded from the original Symbian data
(see docs/FORMATS.md); physics and controls are re-implemented and tuned by feel.

## Build
Push to GitHub and run the "Build APK" workflow (Actions tab). The APK is the artifact.
Assets are already converted and live in `app/src/main/assets`.
Do not publish them in a public repo: the game data is copyrighted by its owners.

## Controls
- swipe up: roll faster (repeat to speed up); swipe down: slow down, past zero = reverse
- double tap: jump (keeps current speed)
- hold right/left half of the screen: turn the camera right/left, continues while held
- three-finger tap: next level (temporary, levels 1-8)

## Known gaps
- Physics constants (gravity, speed, jump, bounce) are guesses, not the original values.
- Only level 1 has the real ball start; levels 2-8 use a guessed start point.
- No scripted logic yet (triggers, moving parts, checkpoints, enemies), no menu, sound or save.
- Code was written without an Android SDK in the sandbox; the first CI build may need small fixes.

SDK: minSdk 14 (Android 4.0), compileSdk/targetSdk 37 (Android 17), AGP 8.9.3, Gradle 8.11.1.

## Updating without uninstalling
Android only updates an installed app if the new APK has the same package name, the same signing key
and a higher versionCode. The workflow raises versionCode automatically. For the key, create it once
on any computer with Java:

    keytool -genkeypair -keystore my.keystore -alias bounce -keyalg RSA -keysize 2048 -validity 10000
    base64 -w0 my.keystore      # copy the output

then in GitHub: Settings > Secrets and variables > Actions, add `KEYSTORE_BASE64` (that output) and
`KEYSTORE_PASSWORD` (the password you chose; use the same for key and store). Keep a backup of my.keystore -
if you lose it, you must uninstall the app before installing a build with a new key.

# FlipperHero for Android

FlipperHero connects to a Flipper Zero over Bluetooth LE. Its agent can inspect
the device, manage files and apps, use saved signals and keys, operate the remote
screen, and update firmware. Device actions pass through the app's risk checks,
approval flow, and audit log.

This is the Android counterpart to [FlipperHero for iOS](https://github.com/flipper-hero/ios-app).
The iOS app is the functional reference. The Android port builds and its unit
tests pass, but Bluetooth and device operations still need testing on an Android
phone. A Flipper connected to a development computer by USB is not available to
this app; the Android transport uses Bluetooth LE.

The RPC protobuf schema is included in `flipper-proto/src/main/proto`, so the
build does not depend on a sibling checkout of the FlipperHero CLI.

## Editions

- **Full** includes the engagement tools, raw RPC, GPIO, Bad KB auto-run, and a
  bounded Marauder bridge. The Marauder tool requires the allowlisted
  `/ext/apps/GPIO/marauder_bridge.fap` on the Flipper.
- **Play** compiles those capabilities out of the APK and app bundle.

Both editions include provider and model selection, chat, file browsing, remote
control, firmware updates, launcher shortcuts, and a connected-device
notification. Provider keys are stored in Android's encrypted preferences.

Long conversations are compacted locally while preserving the latest request,
tool-call batches, and the original transcript. Permission changes that loosen
the approval policy require held confirmation and are recorded in the audit log.
The interface supports 15 languages, right-to-left layouts, and tablet navigation.
See the [privacy policy](PRIVACY.md) for provider data sharing and local storage.
Replies can be reported from inside the app through the 2342.ai reporting endpoint.
Rybbit usage statistics are optional and off by default; they exclude chat, photos,
device identifiers, file contents and provider keys.

## Build and test

Requires Android 12 or later. Use JDK 21 with Android SDK platform 36 and build tools 35.0.0. On Nix systems,
`nix-shell` provides those dependencies. Then run:

```sh
./gradlew :flipper-kit:test :agent-kit-full:test testFullDebugUnitTest testPlayDebugUnitTest assembleFullDebug assemblePlayDebug lintPlayRelease bundlePlayRelease
bash scripts/verify-play.sh app/build/outputs/bundle/playRelease/app-play-release.aab
```

Debug APKs are written to `app/build/outputs/apk/full/debug/` and
`app/build/outputs/apk/play/debug/`. They are development builds, not signed
release artifacts.

For emulator UI checks, `nix-shell --arg withEmulator true` also supplies an
Android 16 Google APIs image and the emulator. Create and start an AVD using that
image, then run `./gradlew connectedPlayDebugAndroidTest`. These checks cover
setup, approvals, and disconnected chat. They do not verify Bluetooth pairing,
file transfers, remote control, or firmware updates on a physical Flipper.

The committed translations can be refreshed from an iOS checkout with
`python3 scripts/sync-localizations.py --ios /path/to/flipper-agent-ios`.

Pushing a `vMAJOR.MINOR.PATCH` tag runs the same checks, signs both release
variants with the stable project key, and publishes a GitHub prerelease with
the two APKs, the Play app bundle, and SHA-256 checksums. Release signing material lives in GitHub
Actions secrets, never in this repository.

## License

MIT. See [LICENSE](LICENSE).

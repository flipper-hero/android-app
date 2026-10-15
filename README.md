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
- **Play** compiles those capabilities out of the APK.

Both editions include provider and model selection, chat, file browsing, remote
control, firmware updates, launcher shortcuts, and a connected-device
notification. Provider keys are stored in Android's encrypted preferences.

## Build and test

Use JDK 21 with Android SDK platform 35 and build tools 35.0.0. On Nix systems,
`nix-shell` provides those dependencies. Then run:

```sh
./gradlew :flipper-kit:test :agent-kit-full:test testFullDebugUnitTest testPlayDebugUnitTest assembleFullDebug assemblePlayDebug
```

Debug APKs are written to `app/build/outputs/apk/full/debug/` and
`app/build/outputs/apk/play/debug/`. They are development builds, not signed
release artifacts.

## License

MIT. See [LICENSE](LICENSE).

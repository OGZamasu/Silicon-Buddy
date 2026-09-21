# Contributing

Two native apps that share a contract, not code. Pick the one you want to work on; you do
not need both toolchains to send a change.

## Get a build running

Clone with the submodule — `third_party/llama.cpp` is the phone's own model, and a clone
without it leaves an empty folder that the Android build fails on:

```
git clone --recursive https://github.com/OGZamasu/Silicon-Buddy.git
# already cloned?
git submodule update --init --depth 1 third_party/llama.cpp
```

**Android** — the JDK inside Android Studio, SDK 35, NDK 29.0.14206865, and the SDK's
CMake 3.22.1. The native part is arm64 only; a build for another ABI simply carries no
llama.cpp, and the app says the phone's own model is unavailable.

```
cd android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug
```

**iOS** — Xcode 26 and xcodegen 2.46. `ios/SiliconBuddy.xcodeproj` is generated and not
committed; `ios/project.yml` is the source, so run xcodegen after changing targets or files.

```
cd ios && xcodegen generate
xcodebuild -scheme SiliconBuddy -destination 'platform=iOS Simulator,name=iPad mini (A17 Pro)' build test
```

## What has to be green

`.github/workflows/android.yml` runs on every pull request, on a GitHub runner: unit tests,
the R8-minified release, a check that the keep rules R8 cannot infer actually kept what they
claim, and the APK size budget. If that is red, the change is not ready.

Two suites cannot run there and are run locally before a merge:

- the **instrumented** tests, which need a device or emulator and a Mac to talk to
- the **iOS** suite, which needs Xcode

`scripts/ci-android.sh` runs the same checks the workflow does, and takes `--connected`
(instrumented tests on `$ANDROID_SERIAL`) and `--ios`. It exits non-zero on the first
failure, so it chains onto a merge with `&&`.

## The contract is generated

`contract/` holds the Mac app's control API as JSON fixtures, exported by that app's own
`ContractExportTests`. Both apps round-trip every type against them, which is how a change
on the Mac fails a build here rather than failing a user. Do not hand-edit those files:
change the Mac side, then re-export with `./contract/refresh.sh`.

## Sending a change

Branch, open a pull request, and say what the change is for rather than what it does — the
diff already says what it does. New behaviour needs a test that would fail without it;
"it builds" is not the bar. `docs/PLAN.md` carries the milestones and the reasoning behind
them, and is the fastest way to see where a piece fits.

Please do not put a real tailnet address, hostname or personal path in the repository.
The fixtures use `100.64.0.9` and similar placeholders for exactly this reason.

## Licence

By contributing you agree that your work is licensed under the [MIT licence](LICENSE) that
covers this repository.

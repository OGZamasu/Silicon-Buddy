# Silicon Buddy

The phone and tablet companion to [Silicon Optimizer](https://github.com/OGZamasu/silicon-optimizer)
and [silicon-node](https://github.com/OGZamasu/silicon-node). Everything the Mac can do —
chat with any loaded or cloud model, install and load models, run image, video and 3D jobs on
the Mac or the node, drive agent sessions, ask the swarm — from an Android phone or an iPad,
over your own tailnet. Nothing public, no relay, no account.

- `ios/` — Swift and SwiftUI, iPhone and iPad. Today: pairing, dashboard, models, chat.
  Widgets, App Intents and a share extension arrive in M2.
- `android/` — Kotlin and Jetpack Compose, phone and tablet. The same four screens today;
  Glance widgets, a Quick Settings tile and a share target arrive in M2.
- `contract/` — the control API as JSON fixtures exported by the Mac app's own
  `ContractExportTests`, plus `routes.md`. Both apps round-trip every type against these,
  so a change on the Mac fails a build here before it fails a user. Re-export with
  `./contract/refresh.sh`.
- `docs/PLAN.md` — the milestones and the decisions behind them.

Two native apps on purpose: they share the contract and the design, not code.

## Building

Both apps talk to Silicon Optimizer over your tailnet, and to nothing else: every
request is checked against `TailnetHost` first — loopback, `10.0.2.2` for the Android
emulator, Tailscale's `100.64.0.0/10` and `fd7a:115c:a1e0::/48`. A scanned QR or a
`siliconbuddy://` link never pairs on its own; it names the machine and asks.

```
# iOS — Xcode 26, xcodegen 2.46
cd ios && xcodegen generate
xcodebuild -scheme SiliconBuddy -destination 'platform=iOS Simulator,name=iPad mini (A17 Pro)' build test

# Android — the JDK inside Android Studio, SDK 35
cd android && JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug
```

The generated `ios/SiliconBuddy.xcodeproj` is not committed: `project.yml` is the source.

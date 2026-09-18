# Silicon Buddy

The phone and tablet companion to [Silicon Optimizer](https://github.com/OGZamasu/silicon-optimizer)
and [silicon-node](https://github.com/OGZamasu/silicon-node). Everything the Mac can do —
chat with any loaded or cloud model, install and load models, run image, video and 3D jobs on
the Mac or the node, drive agent sessions, ask the swarm — from an Android phone or an iPad,
over your own tailnet. Nothing public, no relay, no account.

- `ios/` — Swift, SwiftUI, WidgetKit, App Intents, share extension. iPhone and iPad.
- `android/` — Kotlin, Jetpack Compose, Glance widgets, Quick Settings tile, share target.
- `contract/` — the control API as JSON fixtures exported by the Mac app's tests. Both apps
  are checked against these, so a change on the Mac fails a build here before it fails a user.
- `docs/PLAN.md` — the milestones and the decisions behind them.

Two native apps on purpose: they share the contract and the design, not code.

# The stand-in Mac

The Android on-device tests (`OnDeviceModelTest`) need a Mac to pair with and fetch model
files from. They get this one: `demo_mac.py`, a made-up Silicon Optimizer that speaks the
control API in `contract/routes.md`, serves real model files through the real `/ondevice`
routes, and has switches a test flips. Nothing in it is anybody's machine: the name, the
token (`demo-token`), the conversations and the addresses are all invented. It began as the
server the screenshots in `docs/screenshots` were taken against.

It needs Python 3.9 or later and curl, nothing else.

```sh
# Once: the three model files, from Hugging Face at pinned commits, each kept only when its
# size and SHA-256 are the pinned ones (1.44 GB; --small leaves out Qwen3.5 2B, 146 MB).
tools/standin/fetch-models.sh

# Serve them on 127.0.0.1:8916, which the emulator reaches as 10.0.2.2:8916.
tools/standin/standin.sh start

# The instrumented tests, on the emulator.
ANDROID_SERIAL=emulator-5554 scripts/ci-android.sh --connected

tools/standin/standin.sh stop
```

`scripts/ci-android.sh --connected` asks the stand-in before it builds anything, and stops
with one line saying how to start it when it does not answer.

| Model | Pinned at | File | SHA-256 |
|---|---|---|---|
| Stories 260K | `ggml-org/test-model-stories260K@479896ec924a` | `stories260K-f32.gguf`, 1,185,376 bytes | `270cba1b…d3bd256d` |
| SmolLM2 135M | `bartowski/SmolLM2-135M-Instruct-GGUF@09816acd5d99` | `SmolLM2-135M-Instruct-Q8_0.gguf`, 144,811,360 bytes | `5a139571…b06bba83` |
| Qwen3.5 2B | `bartowski/Qwen_Qwen3.5-2B-GGUF@7d26695454df` | `Qwen_Qwen3.5-2B-Q4_0.gguf`, 1,296,764,000 bytes | `91c102fc…e05f36f6` |

The full commits and digests are in `fetch-models.sh`. Qwen's weights do not fit the
emulator; two tests read its chat template and its memory sheet from the real file, and are
skipped when the stand-in does not serve it.

## Settings

| | Default | |
|---|---|---|
| `BUDDY_STANDIN_MODELS` | `tools/standin/models` | where the models are kept (git ignores it); give both scripts the same |
| `STANDIN_PORT` | `8916` | another port needs the tests told too: `BUDDY_STANDIN=10.0.2.2:<port>` for `ci-android.sh`, or `-Pandroid.testInstrumentationRunnerArguments.standin=10.0.2.2:<port>` for Gradle |
| `STANDIN_HOST` | `127.0.0.1` | loopback is all the emulator needs; a wider address is an unauthenticated model API on your network |
| `STANDIN_STATE` | `tools/standin/state/<port>` | its output, the request log, and the phones it has paired, kept across a restart |
| `QWEN=0` | | leaves Qwen3.5 2B out even when it was fetched |
| `READY=1` | | starts with every model already on the "Mac", rather than waiting to be prepared |

`standin.sh stop` stops only the process `start` started, by its pid file.

## Its own routes

All of them take the stand-in's control token, `Authorization: Bearer demo-token`; a paired
phone's own token is refused.

| Route | What it does |
|---|---|
| `POST /buddy/invitations` | a one-time pairing code, as the real Mac mints for a CLI or a test |
| `GET /demo/requests` | the last 2,000 request lines, oldest first |
| `GET /demo/ondevice/requests` | what phones have asked of the `/ondevice` routes since `clearRequests` |
| `POST /demo/ondevice` | the switches below |
| `POST /demo/unreachable` `{"seconds": n}` | drops every connection unanswered for `n` seconds, as a Mac asleep behind a tunnel; `0` brings it back. Its own `/demo` routes still answer |
| `POST /demo/agents/{engine}/answer` | answers an agent approval "at the Mac" |

`POST /demo/ondevice` takes any of:

| Key | Effect |
|---|---|
| `state` | `{"<model id>": "absent" \| "ready" \| …}`, the model's state on the Mac |
| `fetchSeconds` | how long a `prepare` takes to fetch and check |
| `dropAfter` | the next file transfer is cut after that many bytes |
| `corrupt` | the next *n* transfers have their first byte flipped |
| `notReady` | the next *n* file requests are refused with 409 |
| `ignoreRange` | the next *n* resumed requests get the whole file with 200 instead of 206 |
| `driveMissing` | every request for one model is refused with 503, as with the model drive unplugged |
| `clearRequests` | empties `/demo/ondevice/requests` |

It also forgets every paired phone while a file named `revoked` exists in its state folder.

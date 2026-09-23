#!/usr/bin/env python3
"""A stand-in for Silicon Optimizer, for the instrumented tests and for screenshots.

The Android on-device tests (OnDeviceModelTest, through StandInMac) pair with this and fetch
real model files from it, and the apps are photographed against it rather than against
somebody's Mac: the name, the address, the models and the conversations here are all made
up, so neither a test log nor a screenshot in a public repository shows a real machine.

It speaks the same control API — the routes in contract/routes.md — including the
streaming ones, and it refuses a request without the bearer token, so the connection
row and the pairing flow behave exactly as they do against the real thing. Its own
`/demo/...` routes are the switches a test flips: see tools/standin/README.md.

Start it with tools/standin/standin.sh, which also says where the models come from.
"""

import base64
import ipaddress
import json
import os
import random
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# The stand-in's own made-up control token. It is in StandInMac.java too, which is how a
# test mints a pairing code and flips the switches — and so it is public: anyone who can
# reach this server can use it. The routes check only the token; what keeps them to this
# machine is the bind below.
TOKEN = "demo-token"
# Loopback, which is all the Android emulator needs: it reaches its host's loopback as
# 10.0.2.2. Any other address is refused at the bottom unless SILICON_DEMO_ALLOW_NONLOOPBACK=1
# says so, because there the public token is the whole control surface of a server on
# somebody's network. 8916 is what StandInMac expects unless told otherwise.
HOST = os.environ.get("SILICON_DEMO_HOST", "127.0.0.1")
PORT = int(os.environ.get("SILICON_DEMO_PORT", "8916"))

# The pairing code the Mac's Settings UI would be showing, and the device tokens this
# stand-in has minted. A code is minted with the control token, as on the real Mac.
INVITATION = {"code": None, "expiresAt": None, "scope": "full"}
DEVICE_TOKENS = set()
# What each minted token was paired for: the agent routes are full scope only.
TOKEN_SCOPES = {}

# Kept across restarts when asked, so a stand-in that is stopped and started again — the
# way a Mac relaunches — still knows the phones it paired. Without it, a restart is a Mac
# that has forgotten every device, which is the other thing worth testing.
TOKEN_FILE = os.environ.get("SILICON_DEMO_TOKEN_FILE")
if TOKEN_FILE and os.path.exists(TOKEN_FILE):
    with open(TOKEN_FILE) as handle:
        for line in handle:
            token, _, scope = line.strip().partition(" ")
            if token:
                DEVICE_TOKENS.add(token)
                TOKEN_SCOPES[token] = scope or "full"


def remember_tokens():
    if not TOKEN_FILE:
        return
    try:
        with open(TOKEN_FILE, "w") as handle:
            for token in DEVICE_TOKENS:
                handle.write("%s %s\n" % (token, TOKEN_SCOPES.get(token, "full")))
    except OSError:
        pass   # paired all the same; only a restart forgets it

# What Jev's answer checking sends after a reply. Here so the phones' handling of an
# event name they did not ship with is exercised rather than assumed.
VERDICT = {
    "verdict": "annotate",
    "reasons": ["The reply declines or deflects the request."],
}

# Verdicts waiting to go out on /events, which is where the real Mac publishes them and
# where the apps pick them up. One list per open stream.
EVENT_QUEUES = []
EVENT_LOCK = threading.Lock()


def publish(name, payload):
    with EVENT_LOCK:
        for queue in EVENT_QUEUES:
            queue.append((name, payload))

PROFILE = {
    "chip": "Apple M3 Max",
    "generation": "M3",
    "totalMemoryBytes": 38654705664,
    "modelBudgetBytes": 28604482191,
    "performanceCores": 10,
    "efficiencyCores": 4,
    "gpuCores": 30,
    "neuralEngineCores": 16,
    "memoryBandwidthGBps": 300,
    "diskFreeBytes": 402653184000,
}

STATUS = {
    "state": "Ready",
    "loadedModelID": "qwen3-4b-mlx@MLX-4bit",
    "loadedModelName": "Qwen3 4B (MLX)",
    "contextLength": 65536,
    "expertStreaming": False,
    "lastGenerationTokensPerSecond": 68.4,
}

METRICS = {
    "memoryUsedBytes": 21474836480,
    "memoryWiredBytes": 6442450944,
    "memoryTotalBytes": 38654705664,
    "swapUsedBytes": 1073741824,
    "gpuUtilization": 0.34,
    "cpuUtilization": 0.21,
    "memoryPressure": "Normal",
}

INSTALLED = [
    {"id": "qwen3-4b-mlx@MLX-4bit", "name": "Qwen3 4B (MLX)", "quantization": "MLX-4bit",
     "sizeOnDiskBytes": 2278969697, "isLoaded": True, "supportsVision": False},
    {"id": "bonsai-2-27b@PTQ1_0", "name": "Bonsai 2 27B", "quantization": "PTQ1_0",
     "sizeOnDiskBytes": 5946648928, "isLoaded": False, "supportsVision": True},
    {"id": "gemma-3-12b-it@Q4_K_M", "name": "Gemma 3 12B", "quantization": "Q4_K_M",
     "sizeOnDiskBytes": 7629394432, "isLoaded": False, "supportsVision": True},
    {"id": "qwen3-coder-30b-a3b@Q4_K_M", "name": "Qwen3-Coder 30B A3B",
     "quantization": "Q4_K_M", "sizeOnDiskBytes": 18493440000, "isLoaded": False,
     "supportsVision": False},
]


def catalog_entry(identifier, name, author, summary, category, parameters, rating,
                  download, speed, verdict, featured=False, capabilities=None):
    return {
        "id": identifier, "name": name, "author": author, "license": "Apache-2.0",
        "summary": summary, "category": category, "parameters": parameters,
        "isMoE": False, "capabilities": capabilities or ["Reasoning", "Tools"],
        "rating": rating, "maxContext": 262144, "quantizations": ["Q4_K_M", "Q6_K"],
        "featured": featured,
        "recommendation": {
            "quantization": "Q4_K_M", "contextLength": 32768,
            "estimatedGenerationTokensPerSecond": speed,
            "estimatedPromptTokensPerSecond": speed * 5,
            "downloadBytes": download,
            "plan": {
                "verdict": verdict, "residentBytes": int(download * 1.4),
                "budgetBytes": 28604482191, "weightsBytes": download,
                "expertsBytes": 0, "kvCacheBytes": 4294967296,
                "computeBytes": 201326592, "streamedFromDiskBytes": 0,
                "suggestions": [], "notes": [],
            },
            "rationale": f"Q4_K_M at 32K context fits comfortably, about {speed:.0f} tok/s.",
        },
    }


CATALOG = [
    catalog_entry("bonsai-2-27b", "Bonsai 2 27B", "PrismML",
                  "A 27B flagship in 5.9 GB: ternary weights, vision, tool calling and a "
                  "262K context.", "General", "27B", 5, 5947000000, 21.1, "Comfortable",
                  featured=True, capabilities=["Vision", "Coding", "Reasoning", "Tools"]),
    catalog_entry("qwen3-coder-30b-a3b", "Qwen3-Coder 30B A3B", "Alibaba",
                  "The best coding model that fits on a mainstream Mac. Only 3.3B of its "
                  "30B parameters are active per token.", "Coding", "30B", 5,
                  18490000000, 89.0, "Tight", capabilities=["Coding", "Tools"]),
    catalog_entry("gemma-3-12b-it", "Gemma 3 12B", "Google DeepMind",
                  "A vision model small enough to leave room for a long context.",
                  "Vision", "12B", 4, 7629000000, 38.2, "Comfortable",
                  capabilities=["Vision", "Multilingual"]),
    catalog_entry("qwen3-4b-mlx", "Qwen3 4B (MLX)", "Alibaba",
                  "Small and quick, running on Apple's MLX engine — the one to keep "
                  "loaded when you want an answer now.", "Small & Fast", "4.0B", 4,
                  2280000000, 68.4, "Comfortable"),
    catalog_entry("gpt-oss-20b", "gpt-oss 20B", "OpenAI",
                  "Released natively in 4-bit, so there is no quantization tax to pay.",
                  "Reasoning", "21B", 4, 12110000000, 81.1, "Comfortable"),
]

SWARM = {
    "peers": [{
        "name": "render-node", "baseURL": "http://100.64.0.7:8790", "reachable": True,
        "error": None, "platform": "windows-cuda",
        "hardware": "NVIDIA GeForce RTX 3090 Ti", "totalMemoryGB": 24.0,
        "usedMemoryGB": 9.4, "headroomGB": 14.6, "gpuUtilization": 0.38,
        "gpuConsumer": "job:text-to-video", "queueDepth": 1,
        "loadedModel": "qwen3.8-27b-q4_k_m.gguf", "modelContextLength": 65536,
        "modelEngine": "stock",
        "lanes": {"gguf": True, "image": False, "mesh": True, "video": True},
        "capabilities": [
            {"id": "text-to-image", "kind": "image", "ready": True},
            {"id": "text-to-video", "kind": "video", "ready": True},
            {"id": "image-to-mesh", "kind": "mesh", "ready": True},
            {"id": "llm-qwen3.8-27b", "kind": "llm", "ready": True},
        ],
    }],
    "polledSecondsAgo": 4.0,
}

NODE = {
    "name": "Mac Studio", "platform": "macos-apple-silicon",
    "profile": {"chip": "Apple M3 Max", "memory_gb": 38.654705664,
                "bandwidth_gbps": 300, "gpu_cores": 30},
    "capabilities": [
        {"id": "llm", "kind": "llm", "ready": True, "detail": "Qwen3 4B (MLX) loaded"},
        {"id": "image-flux", "kind": "image", "ready": True, "detail": "MFLUX ready"},
        {"id": "trellis2-4b", "kind": "mesh", "ready": True, "peak_gb": 19.3,
         "detail": "Ready to go."},
    ],
    "metrics": {"queue_depth": 0, "headroom_gb": 28.6, "gpu_util_pct": 34,
                "memory_used_pct": 55},
}


# MARK: - What this stand-in can render

VIDEO_MODELS = [
    {"id": "hailuo-h3", "name": "Hailuo H3", "summary": "Text and image to video.",
     "typicalDuration": "4 minutes", "supportsImageInput": True,
     "supportedSeconds": [5, 10], "available": True, "node": "render-node",
     "supportedParameters": ["h3_turbo", "h3_steps", "negative_prompt"],
     "supportedResolutions": ["480p", "720p", "1080p"],
     "supportsNegativePrompt": True},
    {"id": "ltx-2", "name": "LTX 2", "summary": "Fast local video, short takes.",
     "typicalDuration": "90 seconds", "supportsImageInput": False,
     "supportedSeconds": [3, 5], "available": True, "node": "render-node",
     "supportedResolutions": ["480p", "720p"], "supportsNegativePrompt": False},
    {"id": "wan-2-2", "name": "Wan 2.2", "summary": "Long takes, no node here today.",
     "typicalDuration": "9 minutes", "supportsImageInput": True,
     "supportedSeconds": [5, 10, 15], "available": False, "node": None},
]

IMAGE_PLAN = {
    "width": 1024, "height": 1024, "steps": 8, "quantization": "8-bit",
    "peakBytes": 13958643712, "peakPhase": "Decode", "budgetBytes": 28604482191,
    "verdict": "fits",
    "phases": [
        {"name": "Text encode", "detail": "T5", "residentBytes": 5368709120},
        {"name": "Denoise", "detail": "19 blocks", "residentBytes": 11811160064},
        {"name": "Decode", "detail": "VAE", "residentBytes": 13958643712},
    ],
    "suggestions": [], "notes": ["The last phase is the one that decides."],
}

IMAGE_MODELS = [
    {"id": "flux2-klein", "name": "FLUX.2 klein", "author": "Black Forest Labs",
     "license": "Apache-2.0", "summary": "Fast local text-to-image.",
     "parameters": "4B", "blocks": 19, "defaultSteps": 8, "isGated": False,
     "recommendation": IMAGE_PLAN},
    {"id": "sd35-medium", "name": "Stable Diffusion 3.5 Medium", "author": "Stability AI",
     "license": "SAI-Community", "summary": "Sharper text, slower.",
     "parameters": "2.5B", "blocks": 24, "defaultSteps": 28, "isGated": True,
     "recommendation": dict(IMAGE_PLAN, steps=28, peakBytes=9663676416,
                            peakPhase="Denoise", verdict="fits")},
]

MESH_MODELS = [
    {"id": "hunyuan3d-2", "name": "Hunyuan3D 2", "author": "Tencent",
     "summary": "Image to textured mesh.", "outputs": "GLB, OBJ",
     "typicalDuration": "5 minutes", "peakBytes": 13958643712,
     "weightsBytes": 6442450944, "isInstalled": True, "installDetail": "Installed"},
    {"id": "trellis2-4b", "name": "TRELLIS 2 4B", "author": "Microsoft",
     "summary": "Geometry first, on the node.", "outputs": "GLB",
     "typicalDuration": "3 minutes", "peakBytes": 20401094656,
     "weightsBytes": 8589934592, "isInstalled": False, "installDetail": "Not downloaded"},
]

MESH_PLAN = {
    "model": "Hunyuan3D 2", "peakBytes": 13958643712, "peakPhase": "Texture bake",
    "budgetBytes": 28604482191, "verdict": "fits", "isRemote": False,
    "phases": [
        {"name": "Shape", "detail": "octree 256", "residentBytes": 8589934592},
        {"name": "Texture bake", "detail": "2048px", "residentBytes": 13958643712},
    ],
    "suggestions": [], "notes": [],
}

# The render queue this stand-in actually runs: items move pending → submitting →
# rendering → completed on a worker thread, publishing `job` events as they go, so the
# phone's queue screen is driven by the same two sources as against a real Mac.
QUEUE_LOCK = threading.Lock()
QUEUE = {
    "paused": False,
    "activeID": None,
    "message": None,
    "items": [
        # One that already failed, so the reason line and Retry have something to show.
        {"id": "8A11-0001", "batchID": "8A11", "title": "Harbour", "scene": 1,
         "variation": 1, "prompt": "A crane turning above the harbour at night",
         "seed": 90210, "modelID": "hailuo-h3", "seconds": 5, "resolution": "768P",
         "h3Turbo": False, "h3Steps": 30, "status": "failed", "nodeJobID": None,
         "file": None, "outputDirectory": "/Users/you/Movies/Silicon/Harbour",
         "error": "No node accepted this clip: render-node was busy for 90 seconds.",
         "uncertainSubmission": False},
    ],
}
PROGRESS = {}
# Clips this stand-in stopped following while their node kept the job: a node that offers
# to cancel can still be asked to, as with the real Mac.
RECEIPTS = set()
# The lanes whose node here advertises cancelling one job. H3 goes through Phosphene, which
# has no job-specific stop for a running render, so — as on the real bundled node — it
# offers Stop following and nothing more.
CANCELLABLE_LANES = {"ltx-2", "wan-2-2"}
BATCH = {"n": 0x9C2F}
# What this stand-in was sent and what it has rendered, by the ids it handed out.
MEDIA = {}
MEDIA_LOCK = threading.Lock()


def keep(data, content_type):
    """Files a blob under a fresh media id, the way the Mac's own store does."""
    identifier = base64.urlsafe_b64encode(os.urandom(9)).decode().rstrip("=")
    with MEDIA_LOCK:
        MEDIA[identifier] = (content_type, data)
    return identifier


def keep_placeholder(kind):
    """A rendered result: a tiny PNG or MP4 so saving it to Photos has bytes to save."""
    if kind == "video":
        return keep(PLACEHOLDER_MP4, "video/mp4")
    return keep(PLACEHOLDER_PNG, "image/png")


# A 1x1 PNG and the smallest MP4 that a phone's media store will accept.
PLACEHOLDER_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)
PLACEHOLDER_MP4 = base64.b64decode(
    "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAAAr1tZGF0AAACrgYF//+q3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NCAtIEguMjY0L01QRUctNCBBVkMgY29kZWMAAAAAFHN0Y28AAAAAAAAAAQAAACw="
)
# How fast a clip renders here. A screenshot of a render in flight needs a slower one
# than a queue that has to drain; SILICON_DEMO_STEP sets it.
STEP = float(os.environ.get("SILICON_DEMO_STEP", "0.07"))
# How long a synchronous render takes here. Long enough, when asked, to photograph the
# ongoing notification and press the thing that stops waiting for it.
SYNC_SECONDS = float(os.environ.get("SILICON_DEMO_SYNC_SECONDS", "6"))


def can_cancel(item):
    """The Mac's `canCancel`: the clip's node offers it for the lane, and the render may
    still be running there. Called with QUEUE_LOCK held."""
    if item["modelID"] not in CANCELLABLE_LANES or not item.get("nodeJobID"):
        return False
    if item.get("cancelState") in ("sending", "requested", "confirmed", "completed", "failed"):
        return False
    return item["status"] == "rendering" or (item["status"] == "failed" and item["id"] in RECEIPTS)


def queue_view():
    with QUEUE_LOCK:
        return {
            "paused": QUEUE["paused"],
            "activeID": QUEUE["activeID"],
            "message": QUEUE["message"],
            "items": [dict(item, canCancel=can_cancel(item)) for item in QUEUE["items"]],
        }


def publish_job(item, fraction=None, stage=None, media=None):
    frame = {
        "id": item["id"], "kind": "video", "status": item["status"],
        "title": item["title"], "fraction": fraction,
    }
    if stage:
        frame["stage"] = stage
    if media:
        frame["mediaID"] = media
    if item.get("error"):
        frame["reason"] = item["error"]
    publish("job", frame)


def queue_worker():
    """Moves one clip at a time, the way the Mac's own worker does."""
    while True:
        time.sleep(0.5)
        with QUEUE_LOCK:
            if QUEUE["paused"]:
                continue
            active = next(
                (i for i in QUEUE["items"] if i["status"] in ("submitting", "rendering")),
                None,
            )
            if active is None:
                nxt = next((i for i in QUEUE["items"] if i["status"] == "pending"), None)
                if nxt is None:
                    QUEUE["activeID"] = None
                    continue
                nxt["status"] = "submitting"
                nxt["nodeJobID"] = "job-%d" % random.randrange(9999)
                QUEUE["activeID"] = nxt["id"]
                PROGRESS[nxt["id"]] = 0.0
                publish_job(nxt)
                continue
            if active["status"] == "submitting":
                active["status"] = "rendering"
                publish_job(active, 0.0)
                continue
            fraction = PROGRESS.get(active["id"], 0.0) + STEP
            if fraction >= 1.0:
                active["status"] = "completed"
                active["file"] = "%s/scene-%03d_take-%02d.mp4" % (
                    active["outputDirectory"], active["scene"], active["variation"],
                )
                active["mediaID"] = keep_placeholder("video")
                active["mediaURL"] = "/media/%s" % active["mediaID"]
                active["thumbnailMediaID"] = keep_placeholder("image")
                QUEUE["activeID"] = None
                PROGRESS.pop(active["id"], None)
                publish_job(active, 1.0, media=active["mediaID"])
            else:
                PROGRESS[active["id"]] = fraction
                publish_job(active, fraction,
                            stage="video-denoise %d/30" % int(fraction * 30))


def render_in_background(kind, title, seconds, on_done):
    """An image or a mesh: a `job` event stream and then an answer, like the real thing."""
    def run():
        steps = max(1, int(seconds / 0.4))
        for step in range(steps):
            publish("job", {"id": kind, "kind": kind, "status": "running",
                            "title": title, "fraction": (step + 1) / steps})
            time.sleep(0.4)
        publish("job", {"id": kind, "kind": kind, "status": "completed",
                        "title": title, "fraction": 1.0})
        on_done()
    threading.Thread(target=run, daemon=True).start()


PEER_STATUS = {
    "name": "render-node", "baseURL": "http://100.64.0.7:8790", "reachable": True,
    "platform": "windows-cuda", "hardware": "NVIDIA GeForce RTX 3090 Ti",
    "totalMemoryGB": 24.0, "usedMemoryGB": 9.4, "headroomGB": 14.6,
    "gpuUtilization": 0.38, "queueDepth": 1,
    "capabilities": [
        {"id": "text-to-image", "kind": "image", "ready": True},
        {"id": "text-to-video", "kind": "video", "ready": True},
        {"id": "image-to-mesh", "kind": "mesh", "ready": True},
    ],
    "gguf": {
        "running": True, "model": "qwen3.8-27b-q4_k_m.gguf",
        "adapter": "bonsai-27b-v3.lora.gguf", "engine": "stock",
        "contextLength": 65536, "uptimeSeconds": 4281.0,
        "installedModels": ["qwen3.8-27b-q4_k_m.gguf", "qwen3-coder-30b-q4_k_m.gguf"],
        "adapters": ["bonsai-27b-v3.lora.gguf"],
    },
}

JEV = {
    "enabled": True, "keySet": True, "model": "jev-1.13.0",
    "availableModels": ["jev-1.13.0", "jev-latest", "jev-preview"],
    "models": {"jev-1.13.0": 312}, "calls": 312, "inputTokens": 3104882,
    "estimatedUSD": 0.13, "monthlyUSD": {"2026-08": 0.09, "2026-09": 0.13},
    "month": "2026-09", "monthlyBudgetUSD": 5.0, "budgetRemainingUSD": 4.87,
    "cacheMinutes": 10, "composerAutoRoute": False,
    "automaticUncensoredLaneInEffect": False, "ledgerWriteFailed": False,
    "maxStateBytes": 65536,
    "features": [
        {"id": "decideTool", "displayName": "Decide tool", "summary": "Calibrated probabilities.",
         "built": True, "available": True, "enabled": True, "calls": 312,
         "estimatedUSD": 0.13, "inputTokens": 3104882},
        {"id": "mediaRouting", "displayName": "Media routing",
         "summary": "Reads an image, video or mesh request and picks the model and settings for it.",
         "built": True, "available": True, "enabled": True, "calls": 4,
         "estimatedUSD": 0.0, "inputTokens": 9120},
    ],
}

CONVERSATIONS = {
    "C1": {
        "id": "C1", "title": "Three days in Lisbon",
        "updatedAt": "2026-09-18T09:41:12Z", "isGenerating": False,
        "messages": [
            {"role": "user", "content": "Three days in Lisbon — what would you do?",
             "createdAt": "2026-09-18T09:40:58Z"},
            {"role": "assistant",
             "content": "Start in **Alfama** early, before the tour groups:\n\n"
                        "- Miradouro de Santa Luzia at opening\n"
                        "- Tram 28 downhill, not up\n"
                        "- Late lunch in Campo de Ourique\n\n"
                        "Day two belongs to Belém, and day three to the coast at Cascais.",
             "createdAt": "2026-09-18T09:41:12Z"},
        ],
    },
    "C2": {
        "id": "C2", "title": "Reading a tailnet address",
        "updatedAt": "2026-09-18T09:20:03Z", "isGenerating": False,
        "messages": [
            {"role": "user", "content": "What is a tailnet address?",
             "createdAt": "2026-09-18T09:19:40Z"},
            {"role": "assistant",
             "content": "An address in `100.64.0.0/10` that only the machines on your "
                        "own Tailscale network can reach — no port forwarding, nothing "
                        "public.\n\n```bash\ntailscale ip -4\n```",
             "createdAt": "2026-09-18T09:20:03Z"},
        ],
    },
    "C3": {
        "id": "C3", "title": "Unit tests for a parser",
        "updatedAt": "2026-09-17T18:02:44Z", "isGenerating": False,
        "messages": [
            {"role": "user", "content": "How should I test a line parser?",
             "createdAt": "2026-09-17T18:02:10Z"},
            {"role": "assistant",
             "content": "Feed it the shapes a socket produces: a line split across two "
                        "reads, a blank line, CRLF, and a stream that ends mid-event.",
             "createdAt": "2026-09-17T18:02:44Z"},
        ],
    },
}

ANSWER = (
    "A tailnet is your own private network across your machines: every device gets an "
    "address in `100.64.0.0/10`, and nothing is exposed to the public internet.\n\n"
    "```bash\ntailscale status\n```"
)




# MARK: - Agent sessions (M4)
#
# The Mac's Chat tab runs two agent engines, Codex and Pi, and a paired phone is a second
# screen on them. This stand-in keeps a ledger the way the Mac's does: every row change
# takes the next number on the session's clock and remembers it as the row's own; state,
# turn and approval frames take fresh numbers; `?since=` answers the rows newer than a
# number, or the whole transcript (complete) for a number from before the last clear.
# A scripted turn plays out when a message arrives, with one approval in it, so the phone
# has something to watch, to approve, and to be too late for.

AGENT_LOCK = threading.RLock()
AGENT_DECISIONS = {}          # approval id -> threading.Event
AGENT_ANSWERS = {}            # approval id -> (decision, by)


def iso(ts=None):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts if ts is not None else time.time()))


MODEL_CHOICES = [
    {"id": "local/qwen3-coder-30b", "label": "Qwen3-Coder 30B A3B — Q4_K_M — serving now",
     "where": "This Mac"},
    {"id": "node/render-node/qwen3.8-27b", "label": "Qwen3.8 27B", "where": "render-node"},
]


def mint():
    return "%08X-%04X-4%03X-8%03X-%012X" % (
        random.getrandbits(32), random.getrandbits(16), random.getrandbits(12),
        random.getrandbits(12), random.getrandbits(48))


def new_session(engine, state, cwd, mode, sandbox):
    return {
        "engine": engine, "state": state, "threadID": None, "failure": None,
        "model": "local/qwen3-coder-30b", "cwd": cwd, "turnActive": False,
        "approvals": mode, "sandbox": sandbox, "epoch": mint(),
        "rows": [], "rowSeq": {}, "firstSeen": {}, "stamped": {},
        "pending": [], "seq": 0, "clearedAt": 0, "startedAt": time.time(),
        "cancel": None,
    }


AGENTS = {
    "codex": new_session("codex", "running", "~/Developer/lisbon", "screened",
                         "workspace-write"),
    "pi": new_session("pi", "stopped",
                      "~/Library/Application Support/SiliconOptimizer/pi/workspace",
                      "unattended", "none"),
}
AGENTS["codex"]["threadID"] = "0199F2C1-4A7E-4C3B-9D15-6E2A8B0C1D3F"


def frame_head(engine, kind, seq):
    session = AGENTS[engine]
    head = {"engine": engine, "kind": kind, "seq": seq, "epoch": session["epoch"]}
    if session.get("threadID"):
        head["threadID"] = session["threadID"]
    return head


def agent_publish(engine, kind, **fields):
    session = AGENTS[engine]
    session["seq"] += 1
    frame = frame_head(engine, kind, session["seq"])
    frame.update({k: v for k, v in fields.items() if v is not None})
    publish("agent", frame)


def opening_frames():
    """What the Mac sends a phone first: state, turn and pending approvals, at the current seq."""
    frames = []
    with AGENT_LOCK:
        for engine, session in AGENTS.items():
            frames.append(dict(frame_head(engine, "state", session["seq"]), state=session["state"]))
            frames.append(dict(frame_head(engine, "turn", session["seq"]), turnActive=session["turnActive"]))
            for approval in session["pending"]:
                frames.append(dict(frame_head(engine, "approval", session["seq"]),
                                   approval=wire_approval(approval), state="pending"))
    return frames


OUTPUT_LIMIT = 8192


def wire_item(session, row):
    item = {k: v for k, v in row.items() if v is not None}
    output = item.get("output")
    if output and len(output) > OUTPUT_LIMIT:
        item["output"] = "…" + output[-OUTPUT_LIMIT:]
        item["truncated"] = True
    item["at"] = iso(session["firstSeen"].get(row["id"], session["startedAt"]))
    stamped = session["stamped"].get(row["id"])
    if stamped:
        item["model"] = stamped
    return item


def agent_row(engine, row):
    """Adds or changes a row: the next number on the clock, and a frame carrying it whole."""
    with AGENT_LOCK:
        session = AGENTS[engine]
        rows = session["rows"]
        existing = next((i for i, r in enumerate(rows) if r["id"] == row["id"]), None)
        if existing is None:
            rows.append(row)
            session["firstSeen"][row["id"]] = time.time()
        else:
            rows[existing] = row
        session["seq"] += 1
        session["rowSeq"][row["id"]] = session["seq"]
        publish("agent", dict(frame_head(engine, "item", session["seq"]),
                              item=wire_item(session, row)))


def wire_approval(approval):
    return {k: v for k, v in approval.items() if v is not None and not k.startswith("_")}


def summary(engine):
    session = AGENTS[engine]
    newest = max(session["firstSeen"].values(), default=session["startedAt"])
    body = {
        "engine": engine, "state": session["state"], "epoch": session["epoch"],
        "model": session["model"], "modelChoices": MODEL_CHOICES,
        "approvals": session["approvals"], "sandbox": session["sandbox"],
        "turnActive": session["turnActive"],
        "pendingApprovals": len(session["pending"]) if session["state"] == "running" else 0,
        "itemCount": len(session["rows"]), "updatedAt": iso(newest),
    }
    for key in ("threadID", "failure", "cwd"):
        if session.get(key) is not None:
            body[key] = session[key]
    return body


def detail(engine, since, epoch, limit):
    """`since` and `epoch` are one cursor: a slice only when both match this transcript."""
    session = AGENTS[engine]
    watermark = None
    if since is not None and epoch == session["epoch"]:
        number = int(since)
        if 0 <= number <= session["seq"] and number >= session["clearedAt"]:
            watermark = number
    rows = [r for r in session["rows"]
            if session["rowSeq"].get(r["id"], 0) > (watermark or 0)]
    omitted = 0
    if len(rows) > limit:
        # A slice that would not fit is answered as the newest rows, whole.
        omitted = len(session["rows"]) - limit if watermark is None else len(session["rows"]) - limit
        rows = session["rows"][-limit:]
        watermark = None
    return {
        "session": summary(engine),
        "items": [wire_item(session, r) for r in rows],
        "approvals": ([wire_approval(a) for a in session["pending"]]
                      if session["state"] == "running" else []),
        "seq": session["seq"],
        "epoch": session["epoch"],
        "complete": watermark is None,
        "omitted": omitted,
    }


def set_turn(engine, active):
    with AGENT_LOCK:
        AGENTS[engine]["turnActive"] = active
        agent_publish(engine, "turn", turnActive=active)


def set_state(engine, state):
    with AGENT_LOCK:
        AGENTS[engine]["state"] = state
        if state != "running":
            AGENTS[engine]["turnActive"] = False
        agent_publish(engine, "state", state=state)


def clear_transcript(engine):
    """A new thread: a new epoch, and a `reset` frame instead of silence."""
    with AGENT_LOCK:
        session = AGENTS[engine]
        for approval in list(session["pending"]):
            resolve(engine, approval["id"], "declined", by="gone")
        session["rows"] = []
        session["rowSeq"] = {}
        session["firstSeen"] = {}
        session["turnActive"] = False
        session["epoch"] = mint()
        if engine == "codex":
            session["threadID"] = None
        session["seq"] += 1
        session["clearedAt"] = session["seq"]
        publish("agent", dict(frame_head(engine, "reset", session["seq"]),
                              state=session["state"], turnActive=False))


def ask(engine, kind, summary_text, reason=None,
        screening={"verdict": "confirm", "summary": "Jev: review"}):
    """Holds the turn on an approval until somebody answers it, here or at the "Mac"."""
    approval = {
        "id": mint(),
        "kind": kind, "summary": summary_text, "reason": reason,
        "requestedAt": iso(), "screening": screening,
    }
    event = threading.Event()
    with AGENT_LOCK:
        AGENT_DECISIONS[approval["id"]] = event
        AGENTS[engine]["pending"].append(approval)
        agent_publish(engine, "approval", approval=wire_approval(approval), state="pending")
    event.wait(600)
    return AGENT_ANSWERS.get(approval["id"], ("declined", "gone"))[0]


def resolve(engine, identifier, decision, by):
    with AGENT_LOCK:
        session = AGENTS[engine]
        approval = next((a for a in session["pending"] if a["id"] == identifier), None)
        if approval is None:
            return None
        session["pending"] = [a for a in session["pending"] if a["id"] != identifier]
        AGENT_ANSWERS[identifier] = (decision, by)
        agent_publish(engine, "approval", approval=wire_approval(approval), state=decision)
        event = AGENT_DECISIONS.get(identifier)
    if event:
        event.set()
    return approval


def stream_text(engine, row, text, step=0.1, words_per_step=3):
    words = text.split(" ")
    for index in range(0, len(words), words_per_step):
        row = dict(row, text=" ".join(words[:index + words_per_step]))
        agent_row(engine, row)
        time.sleep(step)
    return row


def codex_turn(engine, text):
    base = "%x" % random.getrandbits(24)
    time.sleep(0.4)
    stream_text(engine, {"id": "rs_" + base, "kind": "reasoning", "text": ""},
                "The itinerary test is the one that failed last time. Run the suite first, "
                "then read the first failure before touching anything.")
    stream_text(engine, {"id": "msg_" + base, "kind": "assistant", "text": ""},
                "I'll run the tests first and fix whatever fails first.")
    decision = ask(engine, "command", "swift test --filter Lisbon",
                   reason="Codex asks before running a command in this folder.",
                   screening={"verdict": "confirm", "summary": "Jev: review: runs the test suite"})
    if decision != "accepted":
        agent_row(engine, {"id": "cmd_" + base, "kind": "command",
                           "text": "swift test --filter Lisbon", "status": "declined"})
        stream_text(engine, {"id": "fin_" + base, "kind": "assistant", "text": ""},
                    "Understood — I won't run the tests. Tell me which file to look at and "
                    "I'll read it instead.")
        return
    command = {"id": "cmd_" + base, "kind": "command", "text": "swift test --filter Lisbon",
               "status": "running", "output": ""}
    lines = [
        "Building for debugging...",
        "[1/4] Compiling Lisbon Itinerary.swift",
        "[2/4] Compiling LisbonTests ItineraryTests.swift",
        "Build complete! (3.84s)",
        "Test Suite 'LisbonTests' started.",
        "Test Case 'ItineraryTests.testBelemFitsInAMorning' passed (0.002 seconds).",
        "Test Case 'ItineraryTests.testItineraryFitsInThreeDays' failed (0.004 seconds).",
        "ItineraryTests.swift:42: error: XCTAssertLessThanOrEqual failed: (\"4\") is not less than or equal to (\"3\")",
        "Test Suite 'LisbonTests' failed. Executed 12 tests, with 1 failure.",
    ]
    for count in range(1, len(lines) + 1):
        command = dict(command, output="\n".join(lines[:count]))
        agent_row(engine, command)
        time.sleep(0.15)
    agent_row(engine, dict(command, status="failed"))
    agent_row(engine, {"id": "fc_" + base, "kind": "fileChange",
                       "text": "Sources/Lisbon/Itinerary.swift", "status": "running"})
    time.sleep(0.6)
    agent_row(engine, {"id": "fc_" + base, "kind": "fileChange",
                       "text": "Sources/Lisbon/Itinerary.swift", "status": "completed"})
    stream_text(engine, {"id": "fin_" + base, "kind": "assistant", "text": ""},
                "The first failure was **itineraryFitsInThreeDays**: Belém and Sintra were "
                "both on day two. I moved Sintra to day three and kept the coast for the "
                "afternoon. Run the suite again when you like.")


def pi_turn(engine, text):
    base = "%x" % random.getrandbits(24)
    time.sleep(0.4)
    thought = stream_text(engine, {"id": "th_" + base, "kind": "reasoning", "text": "", "status": "running"},
                          "List the workspace first, then read the notes file.")
    # Pi's thinking row stops running when the thought is finished, as on the Mac.
    agent_row(engine, {k: v for k, v in thought.items() if k != "status"})
    tool = {"id": "tool_" + base, "kind": "tool", "text": "bash", "status": "running",
            "output": "ls -la notes/"}
    agent_row(engine, tool)
    time.sleep(0.5)
    agent_row(engine, dict(tool, status="completed",
                           output="lisbon.md\nsintra.md\nday-trips.md"))
    stream_text(engine, {"id": "msg_" + base, "kind": "assistant", "text": ""},
                "There are three notes: Lisbon, Sintra and the day trips. Want a summary of "
                "each?")


def run_turn(engine, text):
    try:
        (codex_turn if engine == "codex" else pi_turn)(engine, text)
    finally:
        set_turn(engine, False)


def seed_agents():
    """A Codex thread with some history and a turn waiting on the owner, for screenshots."""
    engine = "codex"
    session = AGENTS[engine]
    first = {"id": "7C3E1A50-6B2D-4F19-8E44-0A1B2C3D4E5F", "kind": "user",
             "text": "Run the tests and fix whatever the first failure is."}
    agent_row(engine, first)
    session["stamped"][first["id"]] = session["model"]
    agent_row(engine, {"id": "item_reasoning_1", "kind": "reasoning",
                       "text": "Run the suite first, then read the first failure."})
    agent_row(engine, {"id": "item_message_1", "kind": "assistant",
                       "text": "Running the suite now."})
    agent_row(engine, {"id": "item_command_1", "kind": "command",
                       "text": "swift build", "status": "completed",
                       "output": "Building for debugging...\nBuild complete! (2.91s)"})
    if os.environ.get("SILICON_DEMO_AGENT_WAITING", "1") == "1":
        session["turnActive"] = True

        def waiting():
            decision = ask(engine, "command", "swift test --filter Lisbon",
                           reason="Codex asks before running a command in this folder.",
                           screening={"verdict": "confirm",
                                      "summary": "Jev: review: runs the test suite"})
            if decision == "accepted":
                command = {"id": "item_command_2", "kind": "command",
                           "text": "swift test --filter Lisbon", "status": "running",
                           "output": "Building for debugging..."}
                agent_row(engine, command)
                time.sleep(0.8)
                agent_row(engine, dict(command, status="completed",
                                       output="Building for debugging...\nTest Suite 'LisbonTests' passed. Executed 12 tests, with 0 failures."))
                stream_text(engine, {"id": "item_message_2", "kind": "assistant", "text": ""},
                            "All twelve tests pass. Nothing to fix.")
            else:
                agent_row(engine, {"id": "item_command_2", "kind": "command",
                                   "text": "swift test --filter Lisbon", "status": "declined"})
                stream_text(engine, {"id": "item_message_2", "kind": "assistant", "text": ""},
                            "Understood — I won't run them.")
            set_turn(engine, False)
        threading.Thread(target=waiting, daemon=True).start()


# MARK: - Models for the phone (M5)
#
# `/ondevice/models`: what the real Mac serves — a pinned list, a prepare that fetches (here:
# pretends to, publishing the same `download` frames with `ondevice:<id>` and a stage), and
# the file itself with Range, If-Range, the digest as ETag and X-Content-SHA256, 409 until
# ready, 416 past the end, 503 with the drive unplugged. The files are real — the tiny test
# models, and Qwen3.5 2B when it was fetched — so the phone's own digest check has something
# true to check. tools/standin/fetch-models.sh gets them, pinned and checked.

PHONE_DIR = os.environ.get("SILICON_DEMO_PHONE_MODELS")
QWEN_FILE = os.environ.get("SILICON_DEMO_QWEN")
PHONE_LOCK = threading.RLock()
PHONE = {}
PHONE_KNOBS = {"corrupt": 0, "dropAfter": None, "notReady": 0, "driveMissing": False,
               "ignoreRange": 0, "fetchSeconds": float(os.environ.get("SILICON_DEMO_FETCH_SECONDS", "2"))}
PHONE_REQUESTS = []   # what phones asked of these routes, for the tests to read back
UNREACHABLE = {"until": 0.0}
ALL_REQUESTS = []     # every request line, for a test to read back through /demo/requests
CHAT_ONLY = ("This device is paired for chat only. Pair it again with full control from "
             "Settings → Silicon Buddy on the Mac.")
NOT_READY = ("This Mac does not have that model ready yet. Ask for it with "
             "POST /ondevice/models/{id}/prepare, and fetch it once GET /ondevice/models says "
             "it is ready.")
NO_SUCH = ("No phone model with that id. GET /ondevice/models lists the ones this Mac can "
           "fetch for a phone.")
DRIVE_MISSING = ("The drive “Demo SSD” that holds this Mac's model library is not connected, "
                 "and the phone models are kept beside the library. Connect it and try again — "
                 "the Mac will not put them on its startup disk instead.")


def digest_of(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def add_phone_model(identifier, label, path, repo, commit, pinned, licence, recommended,
                    measured=None, default=False, slower=False, ready=False):
    if not path or not os.path.exists(path):
        return
    # Only the pinned file. The phone checks what it fetched against the digest listed
    # here, so a wrong file served under its own digest would pass that check.
    digest = digest_of(path)
    if digest != pinned:
        print("not serving %s: %s is not the pinned file (sha256 %s, expected %s); "
              "run tools/standin/fetch-models.sh" % (identifier, path, digest, pinned), flush=True)
        return
    PHONE[identifier] = {
        "entry": {
            "id": identifier, "label": label, "isDefault": default,
            "sizeBytes": os.path.getsize(path), "sha256": digest,
            "licence": licence,
            "source": {"repo": repo, "commit": commit, "file": os.path.basename(path)},
            "recommended": recommended, "slowerOnPhone": slower,
        },
        "measured": measured, "path": path,
        "onMac": {"state": "ready"} if ready else {"state": "absent"},
    }


def seed_phone_models():
    ready = os.environ.get("SILICON_DEMO_PHONE_READY") == "1"
    if PHONE_DIR:
        add_phone_model(
            "smollm2-135m-q8_0", "SmolLM2 135M",
            os.path.join(PHONE_DIR, "SmolLM2-135M-Instruct-Q8_0.gguf"),
            "bartowski/SmolLM2-135M-Instruct-GGUF", "09816acd5d99df7be770d85ea30822623dab342c",
            "5a1395716f7913741cc51d98581b9b1228d80987a9f7d3664106742eb06bba83", "Apache-2.0",
            {"threadsPrompt": 2, "threadsGenerate": 2, "contextLength": 2048,
             "minFreeMemoryBytes": 400000000, "thinking": False},
            default=not QWEN_FILE, ready=ready)
        add_phone_model(
            "stories260k-f32", "Stories 260K",
            os.path.join(PHONE_DIR, "stories260K-f32.gguf"),
            "ggml-org/test-model-stories260K", "479896ec924af6d40fd419ab8f4d1eb2101de00d",
            "270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d", "MIT",
            {"threadsPrompt": 2, "threadsGenerate": 2, "contextLength": 256,
             "minFreeMemoryBytes": 100000000, "thinking": False},
            ready=ready)
    if QWEN_FILE:
        # The real default, with the Mac's numbers (contract/GET__ondevice_models.json):
        # 3.1 GB free to load, which the emulator does not have — the memory guard's own
        # demonstration.
        add_phone_model(
            "qwen3.5-2b-q4_0", "Qwen3.5 2B", QWEN_FILE,
            "bartowski/Qwen_Qwen3.5-2B-GGUF", "7d26695454df6de5fbcce2e58681e62dae06ce43",
            "91c102fc9a86de80e427057ee938e1e34fcaf3bba956b7296e252406e05f36f6", "Apache-2.0",
            {"threadsPrompt": 6, "threadsGenerate": 4, "contextLength": 4096,
             "minFreeMemoryBytes": 3100000000, "thinking": False},
            measured={
                "device": "Galaxy S24 Ultra", "runtime": "llama.cpp b11053, CPU",
                "conditions": "phone hot and charging", "tokensPerSecond": 19.2,
                "threadSweep": [{"threads": 4, "tokensPerSecond": 19.2},
                                {"threads": 6, "tokensPerSecond": 17.2}],
                "promptTokensPerSecond": 122.9, "secondsToFirstWord300": 2.5,
                "firstWordEstimated": True, "sustainedMeasured": False,
                "peakMemoryBytes": 2586836992, "peakMemoryContextTokens": 640},
            default=True, ready=ready)
    # SmolLM2 is the default when Qwen was asked for but not served.
    if "smollm2-135m-q8_0" in PHONE and not any(m["entry"]["isDefault"] for m in PHONE.values()):
        PHONE["smollm2-135m-q8_0"]["entry"]["isDefault"] = True


def phone_wire(identifier):
    held = PHONE[identifier]
    out = dict(held["entry"])
    out["onMac"] = dict(held["onMac"])
    if held["measured"]:
        out["measured"] = held["measured"]
    return out


def phone_frame(identifier, fraction, stage=None, error=None):
    held = PHONE[identifier]
    size = held["entry"]["sizeBytes"]
    frame = {"id": "ondevice:" + identifier, "name": held["entry"]["label"] + " for your phone",
             "fraction": fraction, "bytesReceived": int(size * fraction), "bytesExpected": size,
             "bytesPerSecond": 0 if stage != "fetching" else 48234496}
    if stage:
        frame["stage"] = stage
    if error:
        frame["error"] = error
    publish("download", frame)


def phone_fetch(identifier, verify):
    """What the Mac does when asked: fetch (unless only checking), check, then ready."""
    steps = 10
    pause = PHONE_KNOBS["fetchSeconds"] / (steps * 2)
    if not verify:
        for step in range(1, steps + 1):
            with PHONE_LOCK:
                if PHONE[identifier]["onMac"].get("state") != "downloading":
                    return
                PHONE[identifier]["onMac"] = {"state": "downloading", "stage": "fetching",
                                              "fraction": step / steps}
            phone_frame(identifier, step / steps, "fetching")
            time.sleep(pause)
    for step in range(1, steps + 1):
        with PHONE_LOCK:
            if PHONE[identifier]["onMac"].get("state") != "downloading":
                return
            PHONE[identifier]["onMac"] = {"state": "downloading", "stage": "checking",
                                          "fraction": step / steps}
        phone_frame(identifier, step / steps, "checking")
        time.sleep(pause)
    with PHONE_LOCK:
        PHONE[identifier]["onMac"] = {"state": "ready"}
    phone_frame(identifier, 1.0)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def log_request_line(self, note=""):
        if not note:
            ALL_REQUESTS.append("%s %s" % (self.command, self.path))
            del ALL_REQUESTS[:-2000]
        path = os.environ.get("SILICON_DEMO_REQUEST_LOG")
        if not path:
            return
        # A log that cannot be written is a missing line, not a dropped request: a copy
        # whose log folder was deleted under it once answered every request with nothing.
        try:
            with open(path, "a") as handle:
                handle.write("%.3f %s %s %s\n" % (time.time(), self.command, self.path.split("&epoch=")[0], note))
        except OSError:
            pass

    def parse_request(self):
        ok = super().parse_request()
        if ok:
            self.log_request_line()
            # A Mac that has gone: every connection is dropped unanswered, as a phone sees a
            # sleeping Mac through a tunnel — except the stand-in's own switches.
            if time.time() < UNREACHABLE["until"] and not self.path.startswith("/demo/"):
                self.close_connection = True
                try:
                    self.connection.shutdown(2)
                except OSError:
                    pass
                return False
        return ok

    def phone_route(self, method, path):
        """`/ondevice/models...`. Returns True when it answered."""
        match = re.fullmatch(r"/ondevice/models(?:/([^/]+)(?:/(prepare|file))?)?", path)
        if not match:
            return False
        identifier, verb = match.group(1), match.group(2)
        PHONE_REQUESTS.append("%s %s %s" % (method, self.path, self.headers.get("Range") or ""))
        if not self.authorized():
            self.refuse()
            return True
        if not self.is_full():
            self.send_json({"error": CHAT_ONLY}, 403)
            return True
        if method == "GET" and identifier is None:
            with PHONE_LOCK:
                self.send_json({"models": [phone_wire(i) for i in PHONE]})
            return True
        if identifier not in PHONE:
            self.send_json({"error": NO_SUCH}, 404)
            return True
        if PHONE_KNOBS["driveMissing"]:
            self.send_json({"error": DRIVE_MISSING}, 503)
            return True
        if method == "POST" and verb == "prepare":
            query = self.path.partition("?")[2]
            verify = "verify=1" in query or "verify=true" in query
            with PHONE_LOCK:
                state = PHONE[identifier]["onMac"].get("state")
                if state == "ready" and not verify:
                    self.send_json(phone_wire(identifier), 200)
                    return True
                if state != "downloading":
                    PHONE[identifier]["onMac"] = {"state": "downloading",
                                                  "stage": "checking" if verify else "fetching",
                                                  "fraction": 0}
                    threading.Thread(target=phone_fetch, args=(identifier, verify), daemon=True).start()
                self.send_json(phone_wire(identifier), 202)
            return True
        if method == "DELETE" and verb is None:
            with PHONE_LOCK:
                was = PHONE[identifier]["onMac"].get("state")
                PHONE[identifier]["onMac"] = {"state": "absent"}
            if was == "downloading":
                phone_frame(identifier, 0, error="Removed from the Mac before it finished.")
            self.send_json(phone_wire(identifier))
            return True
        if method == "GET" and verb == "file":
            return self.phone_file(identifier)
        self.send_json({"error": f"Unknown endpoint {method} {path}"}, 404)
        return True

    def phone_file(self, identifier):
        with PHONE_LOCK:
            held = PHONE[identifier]
            ready = held["onMac"].get("state") == "ready"
            if PHONE_KNOBS["notReady"] > 0:
                PHONE_KNOBS["notReady"] -= 1
                ready = False
        if not ready:
            self.send_json({"error": NOT_READY}, 409)
            return True
        size, sha = held["entry"]["sizeBytes"], held["entry"]["sha256"]
        start = None
        asked = self.headers.get("Range")
        if asked and asked.lower().startswith("bytes=") and "," not in asked:
            first = asked[6:].split("-")[0].strip()
            start = int(first) if first.isdigit() else None
        condition = self.headers.get("If-Range")
        if start is not None and condition is not None:
            if condition.strip().strip('"') != sha or condition.strip().startswith("W/"):
                start = None   # not the file the partial came from: the whole file
        if start is not None and PHONE_KNOBS["ignoreRange"] > 0:
            PHONE_KNOBS["ignoreRange"] -= 1
            start = None
        if start is not None and start >= size:
            body = json.dumps({"error": "That byte range is not inside this file."}).encode()
            self.send_response(416)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Range", "bytes */%d" % size)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return True
        offset = start or 0
        self.send_response(206 if start is not None else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size - offset))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", '"%s"' % sha)
        self.send_header("X-Content-SHA256", sha)
        self.send_header("Content-Disposition", 'attachment; filename="%s"' % held["entry"]["source"]["file"])
        self.send_header("Cache-Control", "no-store")
        if start is not None:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (offset, size - 1, size))
        self.end_headers()
        with PHONE_LOCK:
            corrupt = PHONE_KNOBS["corrupt"] > 0
            if corrupt:
                PHONE_KNOBS["corrupt"] -= 1
            drop = PHONE_KNOBS["dropAfter"]
            PHONE_KNOBS["dropAfter"] = None
        sent = 0
        with open(held["path"], "rb") as f:
            f.seek(offset)
            while True:
                chunk = f.read(256 * 1024)
                if not chunk:
                    break
                if corrupt:
                    chunk = bytes([chunk[0] ^ 0xFF]) + chunk[1:]
                    corrupt = False
                if drop is not None and sent + len(chunk) > drop:
                    chunk = chunk[:max(0, drop - sent)]
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    self.close_connection = True
                    self.connection.shutdown(2)
                    return True
                self.wfile.write(chunk)
                sent += len(chunk)
        return True

    # MARK: - Plumbing

    def authorized(self):
        # A Mac that has forgotten every paired phone while the flag file exists (a test's
        # switch): its control token still works, the phones' do not.
        bearer = (self.headers.get("Authorization") or "").removeprefix("Bearer ")
        if bearer != TOKEN and os.path.exists(os.environ.get("SILICON_DEMO_REVOKE", "revoked")):
            return False
        return bearer == TOKEN or bearer in DEVICE_TOKENS

    def is_full(self):
        bearer = (self.headers.get("Authorization") or "").removeprefix("Bearer ")
        return bearer == TOKEN or TOKEN_SCOPES.get(bearer, "full") == "full"

    def agent_refusal(self):
        return self.send_json(
            {"error": "This device is paired for chat only. Pair it again with full control "
                      "from Settings → Silicon Buddy on the Mac."}, 403)

    def agent_route(self, method, path, body=None):
        """The agent routes. Returns True when it answered."""
        match = re.fullmatch(r"/agent/sessions(?:/([^/]+)(?:/(start|new|interrupt|messages|approvals/([^/]+)))?)?", path)
        if not match:
            return False
        if not self.is_full():
            self.agent_refusal()
            return True
        engine, verb, approval_id = match.group(1), match.group(2), match.group(3)
        if engine is None:
            if method != "GET":
                return False
            with AGENT_LOCK:
                self.send_json({"sessions": [summary(e) for e in ("codex", "pi")]})
            return True
        if engine not in AGENTS:
            self.send_json({"error": f"No agent session called {engine}. This Mac runs codex and pi."}, 404)
            return True
        name = "Codex" if engine == "codex" else "Pi"
        session = AGENTS[engine]
        if method == "GET" and verb is None:
            since = epoch = None
            limit = 500
            if "?" in self.path:
                from urllib.parse import parse_qs
                query = parse_qs(self.path.split("?", 1)[1])
                since = (query.get("since") or [None])[0]
                epoch = (query.get("epoch") or [None])[0]
                if since is not None and not since.isdigit():
                    self.send_json({"error": "since must be a whole number."}, 400)
                    return True
                raw_limit = (query.get("limit") or [None])[0]
                if raw_limit is not None:
                    if not raw_limit.isdigit():
                        self.send_json({"error": "limit must be a whole number."}, 400)
                        return True
                    limit = max(1, min(2000, int(raw_limit)))
            with AGENT_LOCK:
                self.send_json(detail(engine, since, epoch, limit))
            return True
        if method == "DELETE" and verb is None:
            clear_pending = list(session["pending"])
            for approval in clear_pending:
                resolve(engine, approval["id"], "declined", by="gone")
            set_state(engine, "stopped")
            with AGENT_LOCK:
                self.send_json(summary(engine))
            return True
        if method != "POST":
            return False
        if verb == "start":
            if session["state"] != "running":
                set_state(engine, "starting")
                time.sleep(0.3)
                set_state(engine, "running")
            with AGENT_LOCK:
                self.send_json(summary(engine))
            return True
        if verb == "new":
            if session["state"] != "running":
                self.send_json({"error": f"{name} is not running. Start it with POST /agent/sessions/{engine}/start and try again."}, 409)
                return True
            clear_transcript(engine)
            if session["turnActive"]:
                set_turn(engine, False)
            if engine == "codex":
                session["threadID"] = None
            with AGENT_LOCK:
                self.send_json(summary(engine))
            return True
        if verb == "interrupt":
            if session["state"] != "running":
                self.send_json({"error": f"{name} is not running. Start it with POST /agent/sessions/{engine}/start and try again."}, 409)
                return True
            for approval in list(session["pending"]):
                resolve(engine, approval["id"], "declined", by="gone")
            agent_row(engine, {"id": "int_%x" % random.getrandbits(24), "kind": "notice",
                               "text": "Turn interrupted."})
            with AGENT_LOCK:
                self.send_json(summary(engine))
            return True
        if verb == "messages":
            text = (body or {}).get("text", "").strip()
            model = (body or {}).get("model")
            if model and model not in [c["id"] for c in MODEL_CHOICES]:
                self.send_json({"error": f"{model} is not one of this session's models. Pick one of the `modelChoices` in GET /agent/sessions."}, 400)
                return True
            if not text:
                self.send_json({"error": "A message needs something in it."}, 400)
                return True
            if session["state"] != "running":
                self.send_json({"error": f"{name} is not running. Start it with POST /agent/sessions/{engine}/start and try again."}, 409)
                return True
            if engine == "codex" and session["turnActive"]:
                self.send_json({"error": "Codex is still working on the last message. Wait for the turn to end, or stop it with POST /agent/sessions/codex/interrupt."}, 409)
                return True
            if model:
                session["model"] = model
            identifier = mint()
            session["stamped"][identifier] = session["model"]
            agent_row(engine, {"id": identifier, "kind": "user", "text": text})
            if engine == "codex" and session["threadID"] is None:
                session["threadID"] = mint()
                # The Mac says so with a state frame when the thread gets its id.
                agent_publish(engine, "state", state=session["state"])
            if not session["turnActive"]:
                set_turn(engine, True)
                threading.Thread(target=run_turn, args=(engine, text), daemon=True).start()
            self.send_json({"itemID": identifier}, 202)
            return True
        if approval_id is not None:
            decision = (body or {}).get("decision")
            if decision not in ("accept", "decline"):
                self.send_json({"error": f"{decision} is not a decision. Send \"accept\" or \"decline\"."}, 400)
                return True
            if session["state"] != "running":
                self.send_json({"error": f"{name} is not running. Start it with POST /agent/sessions/{engine}/start and try again."}, 409)
                return True
            answered = AGENT_ANSWERS.get(approval_id)
            if answered and answered[1] == "mac":
                self.send_json({"error": "That was answered at the Mac before this arrived. The agent already has its decision; nothing was sent twice."}, 409)
                return True
            approval = resolve(engine, approval_id,
                               "accepted" if decision == "accept" else "declined", by="remote")
            if approval is None:
                self.send_json({"error": f"No approval with id {approval_id} is waiting. It was answered already, or never existed."}, 404)
                return True
            with AGENT_LOCK:
                self.send_json({"id": approval_id,
                                "decision": "accepted" if decision == "accept" else "declined",
                                "session": summary(engine)})
            return True
        return False

    def is_control(self):
        """Only the control token mints a pairing code, as on the real Mac."""
        return (self.headers.get("Authorization") or "") == f"Bearer {TOKEN}"

    def send_json(self, payload, status=200):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def refuse(self):
        self.send_json({"error": "Invalid or missing control token."}, 401)

    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        return json.loads(self.rfile.read(length) or b"{}")

    def start_stream(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()

    def send_event(self, name, payload):
        block = f"event: {name}\ndata: {json.dumps(payload)}\n\n".encode()
        self.wfile.write(block)
        self.wfile.flush()

    def stream_answer(self, text, conversation_id=None):
        self.start_stream()
        words = text.split(" ")
        for index, word in enumerate(words):
            self.send_event("token", {"text": word + (" " if index < len(words) - 1 else "")})
            time.sleep(0.02)
        self.send_event("finished", {
            "promptTokens": 48, "generatedTokens": len(words),
            "tokensPerSecond": 68.4, "timeToFirstToken": 0.21,
        })
        # After `finished`, the way the real Mac now does. A client that waited for the
        # socket to close before showing the answer as done would stall here.
        time.sleep(0.4)
        # The real Mac publishes the check on /events as well; that is the path the
        # apps render from, because a chat stream ends at `finished`.
        publish("verdict", dict(VERDICT, conversationID=conversation_id))
        try:
            self.send_event("verdict", dict(VERDICT, conversationID=conversation_id))
            # And a name no shipped client has ever heard of, which must be skipped
            # rather than treated as an error.
            self.send_event("weather", {"outlook": "fine"})
        except Exception:
            return

    # MARK: - Routes

    def do_DELETE(self):
        path = self.path.split("?")[0]
        if self.phone_route("DELETE", path):
            return
        if path.startswith("/agent/"):
            if not self.authorized():
                return self.refuse()
            if self.agent_route("DELETE", path):
                return
        if path == "/buddy/invitations":
            if not self.is_control():
                return self.send_json({"error": "Only this Mac can do that."}, 403)
            INVITATION.update(code=None, expiresAt=None)
            return self.send_json({"status": "cancelled"})
        return self.send_json({"error": f"Unknown endpoint DELETE {path}"}, 404)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/health":
            return self.send_json({"status": "ok", "version": "0.1.0"})
        if not self.authorized():
            return self.refuse()

        routes = {
            "/status": STATUS, "/profile": PROFILE, "/metrics": METRICS,
            "/installed": INSTALLED, "/catalog": CATALOG, "/swarm": SWARM,
            "/v1/node": NODE, "/video/models": VIDEO_MODELS,
            "/image/models": IMAGE_MODELS, "/mesh/models": MESH_MODELS,
            "/jev": JEV,
        }
        if path == "/video/queue":
            return self.send_json(queue_view())

        if self.agent_route("GET", path):
            return
        if self.phone_route("GET", path):
            return
        if path == "/demo/requests":
            if not self.is_control():
                return self.send_json({"error": "Only this Mac can do that."}, 403)
            return self.send_json({"requests": ALL_REQUESTS})
        if path == "/demo/ondevice/requests":
            if not self.is_control():
                return self.send_json({"error": "Only this Mac can do that."}, 403)
            return self.send_json({"requests": PHONE_REQUESTS})

        media = re.fullmatch(r"/media/([^/]+)", path)
        if media:
            with MEDIA_LOCK:
                held = MEDIA.get(media.group(1))
            if not held:
                return self.send_json(
                    {"error": "No file with that media id. Ids are issued by this Mac "
                              "with each result and stop working when the file is deleted."},
                    404,
                )
            content_type, data = held
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "private, max-age=3600")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            return self.wfile.write(data)

        peer = re.fullmatch(r"/swarm/peers/([^/]+)/status", path)
        if peer:
            if peer.group(1) != "render-node":
                return self.send_json({"error": "No peer by that name."}, 404)
            return self.send_json(PEER_STATUS)
        if path in routes:
            return self.send_json(routes[path])
        if path == "/conversations":
            return self.send_json([
                {"id": c["id"], "title": c["title"], "updatedAt": c["updatedAt"],
                 "messageCount": len(c["messages"])}
                for c in sorted(CONVERSATIONS.values(), key=lambda c: c["updatedAt"],
                                reverse=True)
            ])
        match = re.fullmatch(r"/conversations/([^/]+)", path)
        if match:
            conversation = CONVERSATIONS.get(match.group(1))
            if not conversation:
                return self.send_json({"error": "No conversation with that id."}, 404)
            return self.send_json(conversation)
        if path == "/events":
            self.start_stream()
            self.log_request_line("OPEN")
            queue = [("agent", frame) for frame in opening_frames()]
            with EVENT_LOCK:
                EVENT_QUEUES.append(queue)
            try:
                beat = 0
                while True:
                    while queue:
                        name, payload = queue.pop(0)
                        self.send_event(name, payload)
                    if beat % 100 == 0:
                        # As the real Mac does: the credential is checked again at each
                        # heartbeat, and a revoked device's stream ends.
                        if not self.authorized():
                            return
                        self.send_event("heartbeat", {"at": "2026-09-18T09:41:00Z"})
                    beat += 1
                    time.sleep(0.1)
            except Exception:
                return
            finally:
                self.log_request_line("CLOSE")
                with EVENT_LOCK:
                    if queue in EVENT_QUEUES:
                        EVENT_QUEUES.remove(queue)
        return self.send_json({"error": f"Unknown endpoint GET {path}"}, 404)

    def do_POST(self):
        path = self.path.split("?")[0]

        # The one route that runs before there is a token.
        if path == "/buddy/pair":
            body = self.read_body()
            live = INVITATION["code"] and time.time() < (INVITATION["expiresAt"] or 0)
            if not live or body.get("code") != INVITATION["code"]:
                return self.send_json({"error": "That code is wrong or has expired."}, 401)
            token = "device-%06d" % random.randrange(1_000_000)
            DEVICE_TOKENS.add(token)
            TOKEN_SCOPES[token] = INVITATION["scope"]
            remember_tokens()
            # One use, as on the real Mac.
            INVITATION.update(code=None, expiresAt=None)
            return self.send_json({
                "deviceID": "D%03d" % random.randrange(1000),
                "token": token,
                "macName": "Demo Studio",
                "port": PORT,
                "scope": INVITATION["scope"],
            })

        if not self.authorized():
            return self.refuse()

        if path == "/uploads":
            length = int(self.headers.get("Content-Length") or 0)
            if not length:
                return self.send_json({"error": "That upload has no body in it."}, 400)
            if length > 24 * 1024 * 1024:
                return self.send_json(
                    {"error": "That request body is larger than this device may send "
                              "(25165824 bytes)."},
                    413,
                )
            data = self.rfile.read(length)
            claimed = self.headers.get("Content-Type") or "application/octet-stream"
            # The type is read off the bytes, not believed.
            if data[:8] == b"\x89PNG\r\n\x1a\n":
                content_type = "image/png"
            elif data[:3] == b"\xff\xd8\xff":
                content_type = "image/jpeg"
            elif data[4:8] == b"ftyp":
                content_type = "video/mp4"
            elif claimed.startswith("image/") or claimed.startswith("video/"):
                content_type = claimed
            else:
                return self.send_json(
                    {"error": "That upload is not an image or a short video this Mac "
                              "will keep. Send a PNG, JPEG, GIF, WebP, MP4, MOV or WebM."},
                    415,
                )
            identifier = keep(data, content_type)
            return self.send_json({
                "uploadID": "%s-upload" % identifier,
                "mediaID": identifier,
                "bytes": len(data),
                "contentType": content_type,
                "mediaURL": "/media/%s" % identifier,
                "expiresAt": time.strftime(
                    "%Y-%m-%dT%H:%M:%SZ", time.gmtime(time.time() + 7 * 24 * 3600)
                ),
            })

        body = self.read_body()

        if self.agent_route("POST", path, body):
            return
        if self.phone_route("POST", path):
            return

        # The stand-in's own switches, for the tests: the control token only (public; the
        # bind is what keeps them to this machine).
        if path == "/demo/unreachable":
            if not self.is_control():
                return self.send_json({"error": "Only this Mac can do that."}, 403)
            UNREACHABLE["until"] = time.time() + float(body.get("seconds") or 0)
            return self.send_json({"unreachableFor": float(body.get("seconds") or 0)})
        if path == "/demo/ondevice":
            if not self.is_control():
                return self.send_json({"error": "Only this Mac can do that."}, 403)
            with PHONE_LOCK:
                for key in ("corrupt", "dropAfter", "notReady", "driveMissing", "ignoreRange", "fetchSeconds"):
                    if key in body:
                        PHONE_KNOBS[key] = body[key]
                for identifier, state in (body.get("state") or {}).items():
                    if identifier in PHONE:
                        PHONE[identifier]["onMac"] = {"state": state}
                if body.get("clearRequests"):
                    PHONE_REQUESTS.clear()
            return self.send_json({"knobs": PHONE_KNOBS,
                                   "states": {i: PHONE[i]["onMac"] for i in PHONE}})

        # The owner answering at the Mac, for the stand-in: the control token only.
        answer = re.fullmatch(r"/demo/agents/([^/]+)/answer", path)
        if answer:
            if not self.is_control():
                return self.send_json({"error": "Only this Mac can do that."}, 403)
            decision = "accepted" if body.get("decision") == "accept" else "declined"
            approval = resolve(answer.group(1), body.get("id", ""), decision, by="mac")
            if approval is None:
                return self.send_json({"error": "Nothing waiting with that id."}, 404)
            return self.send_json({"id": approval["id"], "decision": decision})

        if path == "/buddy/invitations":
            # The control token only, which is the whole point of the route the Mac grew
            # for this: tests and CLIs could not pair before it. (The real Mac also insists
            # on loopback; here only the bind does.)
            if not self.is_control():
                return self.send_json({"error": "Only this Mac can do that."}, 403)
            scope = body.get("scope") or "full"
            if scope not in ("full", "chat"):
                return self.send_json({"error": f"Unknown scope {scope!r}."}, 400)
            INVITATION.update(
                code="%06d" % random.randrange(1_000_000),
                expiresAt=time.time() + 300,
                scope=scope,
            )
            return self.send_json({
                "code": INVITATION["code"],
                "host": "127.0.0.1",
                "port": PORT,
                "expiresAt": time.strftime(
                    "%Y-%m-%dT%H:%M:%SZ", time.gmtime(INVITATION["expiresAt"])
                ),
                "scope": scope,
            })

        if path == "/chat":
            return self.send_json({
                "content": ANSWER, "reasoning": None, "promptTokens": 48,
                "generatedTokens": 96, "tokensPerSecond": 68.4,
            })
        if path == "/chat/stream":
            return self.stream_answer(ANSWER)
        if path == "/conversations":
            new = {"id": "C%d" % (len(CONVERSATIONS) + 1),
                   "title": "New conversation",
                   "updatedAt": "2026-09-18T09:45:00Z", "isGenerating": False,
                   "messages": []}
            CONVERSATIONS[new["id"]] = new
            return self.send_json({"id": new["id"], "title": new["title"],
                                   "updatedAt": new["updatedAt"], "messageCount": 0})
        match = re.fullmatch(r"/conversations/([^/]+)/messages", path)
        if match:
            conversation = CONVERSATIONS.get(match.group(1))
            if not conversation:
                return self.send_json({"error": "No conversation with that id."}, 404)
            conversation["messages"].append({
                "role": "user", "content": body.get("content", ""),
                "createdAt": "2026-09-18T09:45:10Z",
            })
            conversation["messages"].append({
                "role": "assistant", "content": ANSWER,
                "createdAt": "2026-09-18T09:45:12Z",
            })
            return self.stream_answer(ANSWER, conversation_id=match.group(1))
        if path == "/video/queue":
            prompts = body.get("prompts") or []
            if not prompts:
                return self.send_json({"error": "No prompts."}, 400)
            variations = max(1, min(20, int(body.get("variations") or 1)))
            title = body.get("title") or "Untitled"
            model = body.get("modelID") or "hailuo-h3"
            seconds = int(body.get("seconds") or 5)
            BATCH["n"] += 1
            batch = "%04X" % BATCH["n"]
            with QUEUE_LOCK:
                index = 0
                for scene, prompt in enumerate(prompts, start=1):
                    for variation in range(1, variations + 1):
                        index += 1
                        QUEUE["items"].append({
                            "id": "%s-%04d" % (batch, index), "batchID": batch,
                            "title": title, "scene": scene, "variation": variation,
                            "prompt": prompt, "seed": random.randrange(1_000_000),
                            "modelID": model, "seconds": seconds, "resolution": "768P",
                            "h3Turbo": False, "h3Steps": 30, "status": "pending",
                            "nodeJobID": None, "file": None,
                            "outputDirectory": "/Users/you/Movies/Silicon/" + title,
                            "error": None, "uncertainSubmission": False,
                            "negativePrompt": body.get("negativePrompt"),
                            "detail": None,
                        })
                QUEUE["message"] = None
            return self.send_json(queue_view())

        if path == "/video/queue/control":
            action = body.get("action")
            identifier = body.get("id")
            with QUEUE_LOCK:
                item = next((i for i in QUEUE["items"] if i["id"] == identifier), None)
                if action == "pause":
                    QUEUE["paused"] = True
                elif action == "resume":
                    QUEUE["paused"] = False
                elif action == "retry":
                    if item is None:
                        return self.send_json({"error": "An item ID is required."}, 400)
                    item.update(status="pending", error=None, file=None)
                    for key in ("cancelState", "cancelDetail"):
                        item.pop(key, None)
                    RECEIPTS.discard(identifier)
                elif action == "remove":
                    if item is None:
                        return self.send_json({"error": "An item ID is required."}, 400)
                    QUEUE["items"] = [i for i in QUEUE["items"] if i["id"] != identifier]
                elif action == "stop_following":
                    if item is None or QUEUE["activeID"] != identifier:
                        return self.send_json(
                            {"error": "That clip is not currently being followed by the app."}, 400,
                        )
                    QUEUE["paused"] = True
                    item.update(status="failed", error="Stopped following. The node may still finish it.")
                    QUEUE["activeID"] = None
                    RECEIPTS.add(identifier)
                elif action == "cancel":
                    if item is None:
                        return self.send_json({"error": "An item ID is required."}, 400)
                    if item.get("cancelState") == "confirmed":
                        return self.send_json({"error": "This clip's render is already cancelled."}, 400)
                    if not can_cancel(item):
                        return self.send_json({"error": (
                            "This clip's node does not offer to cancel its render. Use "
                            "stop_following: the app stops waiting and keeps the receipt, but "
                            "the node may still finish the render."
                        )}, 400)
                    # This node stops a render at once, so the answer is always a confirmed
                    # cancel; the real Mac may also answer requested, unsupported or unknown.
                    item.update(status="cancelled", error=None, cancelState="confirmed",
                                cancelDetail="Cancelled; the renderer was stopped.")
                    RECEIPTS.discard(identifier)
                    PROGRESS.pop(identifier, None)
                    if QUEUE["activeID"] == identifier:
                        QUEUE["activeID"] = None
                    QUEUE["message"] = "The node stopped this render. Nothing will be published for it."
                    publish_job(item)
                elif action == "clear_finished":
                    QUEUE["items"] = [
                        i for i in QUEUE["items"]
                        if i["status"] not in ("completed", "failed", "cancelled")
                    ]
                else:
                    return self.send_json(
                        {"error": "Use pause, resume, retry, remove, stop_following, cancel, or clear_finished."},
                        400,
                    )
            return self.send_json(queue_view())

        if path == "/video/generate":
            seconds = int(body.get("seconds") or 5)
            time.sleep(SYNC_SECONDS)
            clip = keep_placeholder("video")
            return self.send_json({
                "file": "/Users/you/Movies/Silicon/one-off-%04d.mp4" % random.randrange(9999),
                "node": "render-node", "model": body.get("modelID") or "hailuo-h3",
                "elapsedSeconds": 3.0 + seconds,
                "mediaID": clip, "mediaURL": "/media/%s" % clip,
                "thumbnailMediaID": keep_placeholder("image"),
            })

        if path == "/image/plan":
            return self.send_json(dict(
                IMAGE_PLAN,
                width=int(body.get("width") or 1024),
                height=int(body.get("height") or 1024),
                steps=int(body.get("steps") or 8),
            ))

        if path == "/image/generate":
            model = next(
                (m for m in IMAGE_MODELS if m["id"] == body.get("modelID")), IMAGE_MODELS[0],
            )
            done = threading.Event()
            render_in_background("image", model["name"], SYNC_SECONDS, done.set)
            done.wait(SYNC_SECONDS * 4 + 10)
            picture = keep_placeholder("image")
            return self.send_json({
                "path": "/Users/you/Pictures/Silicon/lisbon-%04d.png" % random.randrange(9999),
                "elapsedSeconds": 6.4, "peakMemoryBytes": 13958643712,
                "predictedPeakBytes": 14200000000, "model": model["name"],
                "mediaID": picture, "mediaURL": "/media/%s" % picture,
            })

        if path == "/mesh/plan":
            return self.send_json(dict(MESH_PLAN))

        if path == "/mesh/generate":
            done = threading.Event()
            render_in_background("mesh", "Hunyuan3D 2", SYNC_SECONDS, done.set)
            done.wait(SYNC_SECONDS * 4 + 10)
            mesh = keep(b"glTF-placeholder", "model/gltf-binary")
            return self.send_json({
                "glbPath": "/Users/you/Models/Silicon/kettle.glb",
                "objPath": None, "elapsedSeconds": 5.2, "model": "Hunyuan3D 2",
                "mediaID": mesh, "mediaURL": "/media/%s" % mesh,
            })

        if path == "/load":
            return self.send_json(STATUS)
        if path == "/unload":
            return self.send_json({"status": "unloaded"})
        if path == "/install":
            return self.send_json({"status": "Downloading."})
        return self.send_json({"error": f"Unknown endpoint POST {path}"}, 404)


def is_loopback(host):
    if host == "localhost":
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


if __name__ == "__main__":
    # Loopback unless explicitly told otherwise (HOST, above).
    if not is_loopback(HOST):
        if os.environ.get("SILICON_DEMO_ALLOW_NONLOOPBACK") != "1":
            sys.exit("Not listening on %s: anyone who can reach it could use the control "
                     "token, demo-token, which is public. Set SILICON_DEMO_ALLOW_NONLOOPBACK=1 "
                     "to listen there anyway." % HOST)
        print("warning: listening on %s, where anyone who can reach it has the public control "
              "token demo-token: the switches, pairing codes and the model files" % HOST, flush=True)
    threading.Thread(target=queue_worker, daemon=True).start()
    seed_agents()
    seed_phone_models()
    print("phone models: %s" % (", ".join(PHONE) or "none"), flush=True)
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.daemon_threads = True
    print(f"demo Mac on {HOST}:{PORT}", flush=True)
    server.serve_forever()

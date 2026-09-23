#!/usr/bin/env bash
# The Android checks, run locally: GitHub Actions cannot run on this repository, so this is
# the CI. Exits non-zero on the first thing that is wrong, so it can gate a merge with &&.
#
#   scripts/ci-android.sh                 unit tests, minified release, keep rules, size budget
#   scripts/ci-android.sh --connected     …and the instrumented tests on $ANDROID_SERIAL, which
#                                         need the stand-in Mac running on this machine:
#                                         tools/standin/fetch-models.sh, then
#                                         tools/standin/standin.sh start (tools/standin/README.md)
#   scripts/ci-android.sh --ios           …and the iOS suite (contract/ is shared)
#
# Environment: JAVA_HOME (defaults to Android Studio's), ANDROID_SERIAL for --connected,
# BUDDY_STANDIN for where the emulator finds the stand-in (10.0.2.2:8916),
# IOS_DERIVED_DATA and IOS_DESTINATION for --ios.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android="$here/android"
connected=false
ios=false
for argument in "$@"; do
    case "$argument" in
        --connected) connected=true ;;
        --ios) ios=true ;;
        *) echo "unknown option $argument" >&2; exit 2 ;;
    esac
done

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

fail() { echo "FAIL: $*" >&2; exit 1; }

# llama.cpp is a submodule; a clone without --recursive has an empty folder there.
[ -f "$here/third_party/llama.cpp/CMakeLists.txt" ] ||
    fail "third_party/llama.cpp is not checked out: git submodule update --init --depth 1 third_party/llama.cpp"
[ "$(git -C "$here/third_party/llama.cpp" rev-parse HEAD)" = "1af554f8fc78ba029665a47b839484d9763e2a75" ] ||
    fail "third_party/llama.cpp is not at b11053 (1af554f8fc78ba029665a47b839484d9763e2a75)"
[ -f "$android/local.properties" ] || [ -n "${ANDROID_HOME:-}" ] ||
    fail "no Android SDK: copy local.properties from the main checkout or set ANDROID_HOME"

# --- The stand-in Mac, before anything is built ------------------------------------
# The on-device tests pair with a stand-in Mac on this machine and fetch real model files
# from it (tools/standin). Without it they fail in setUp, after the whole build — so it is
# asked here, once, and a missing one is one line saying how to start it. The address is
# the emulator's: 10.0.2.2 is its name for this machine's loopback.
standin="${BUDDY_STANDIN:-10.0.2.2:8916}"
if $connected; then
    [ -n "${ANDROID_SERIAL:-}" ] || fail "--connected needs ANDROID_SERIAL"
    python3 - "$standin" <<'PY'
import json, sys, urllib.request

standin = sys.argv[1]
host, _, port = standin.rpartition(":")
probe = "127.0.0.1" if host == "10.0.2.2" else host
where = f"{probe}:{port}" + (f" (the emulator's {standin})" if probe != host else "")
start = "tools/standin/standin.sh start" if port == "8916" else f"STANDIN_PORT={port} tools/standin/standin.sh start"
# Where to start a stand-in of your own when this one is somebody else's.
other = f"STANDIN_PORT={int(port) + 1} tools/standin/standin.sh start, with BUDDY_STANDIN={host}:{int(port) + 1}"

def get(path, token=None):
    request = urllib.request.Request(f"http://{probe}:{port}{path}")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=5) as answer:
        return json.load(answer)

try:
    get("/health")
except Exception:
    try:
        # Its own switches answer even while a test has it playing a Mac that went away.
        get("/demo/requests", "demo-token")
    except Exception:
        sys.exit(f"FAIL: the stand-in Mac is not answering at {where}. Start it, from the "
                 f"repository: tools/standin/fetch-models.sh && {start}")
    sys.exit(f"FAIL: the stand-in Mac at {where} is playing a Mac that went away — another "
             f"test run is using it. Wait for that run, or start your own on another port: {other}")
try:
    served = {model["id"] for model in get("/ondevice/models", "demo-token")["models"]}
except Exception as refusal:
    sys.exit(f"FAIL: something answers at {where}, but not as tools/standin/demo_mac.py does "
             f"({refusal}). Stop it, or start the stand-in on another port: {other}")
missing = sorted({"stories260k-f32", "smollm2-135m-q8_0"} - served)
if missing:
    stop = start.replace(" start", " stop")
    sys.exit(f"FAIL: the stand-in Mac at {where} does not serve {', '.join(missing)}. "
             f"Fetch the models and restart it: tools/standin/fetch-models.sh && {stop} && {start} "
             f"(if it was not started by tools/standin/standin.sh, stop it yourself)")
print(f"stand-in: {where}, serving {', '.join(sorted(served))}")
if "qwen3.5-2b-q4_0" not in served:
    print("stand-in: without Qwen3.5 2B, so qwenAnswersWithThinkingOff and "
          "makeRoomSaysWhatIsFreeAndWhatThisAppCannotDo will be skipped "
          "(tools/standin/fetch-models.sh without --small fetches it)")
PY
fi

cd "$android"

# --- Unit tests -------------------------------------------------------------------
# --rerun, always: an up-to-date `testDebugUnitTest` prints nothing and passes, so a green
# run can mean "nothing was tested since the last one". A gate that can pass without
# running the tests is not a gate.
./gradlew --console=plain -q testDebugUnitTest --rerun assembleRelease assembleReleaseProbe
# Both modules: the app's, and the llama module's own — which are where the rules about
# loading and freeing a model live.
python3 - "$android/app/build/test-results/testDebugUnitTest" "$android/llama/build/test-results/testDebugUnitTest" <<'PY'
import glob, re, sys
tests = failures = 0
for folder in sys.argv[1:]:
    found = glob.glob(folder + "/*.xml")
    if not found:
        sys.exit(f"no test results in {folder}")
    for path in found:
        head = re.search(r'<testsuite [^>]*tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"', open(path).read())
        tests += int(head.group(1)); failures += int(head.group(2)) + int(head.group(3))
print(f"unit tests: {tests}, failures: {failures}")
sys.exit(1 if failures or tests == 0 else 0)
PY

# --- What R8 kept -----------------------------------------------------------------
# R8 cannot see an edge that goes through a name: a Glance callback, a serializer, a JNI
# function, a method native code looks up. Lose one and the build still succeeds.
seeds="$android/app/build/outputs/mapping/release/seeds.txt"
[ -f "$seeds" ] || fail "no seeds.txt — did R8 run?"
for class in AskQuickPromptAction ClearQuickAnswerAction; do
    grep -q "dev.siliconoptimizer.buddy.widget.$class$" "$seeds" || fail "$class was not kept"
done
# 48 after M2.1, 62 with M3's media types, 75 with M4's agent sessions, 83 with M5's phone
# models. Raise it with every wire type: a floor that lags lets a lost serializer through.
serializers=$(grep -oE '^[A-Za-z0-9_.]+\$\$serializer' "$seeds" | sort -u | wc -l | tr -d ' ')
echo "serializers kept: $serializers"
[ "$serializers" -ge 83 ] || fail "only $serializers serializers kept, expected at least 83"
for type in AgentEvent AgentSessionDetail AgentSessionSummary AgentApproval ResyncEvent \
            PhoneModel PhoneModelList PhoneModelOnMac PhoneModelRecommended PhoneModelMeasured; do
    grep -qF "dev.siliconoptimizer.buddy.transport.$type\$\$serializer" "$seeds" ||
        fail "$type's serializer was not kept"
done
# The JNI entry points, bound by name: every one of them, and the sink native code calls.
for method in nativeInit nativeBackends nativeSystemInfo nativeLoad nativeCancelLoad nativeReset \
              nativeCancel nativeSetThreads nativeUnload nativeGenerate nativeRenderPrompt; do
    grep -qE "^dev\.siliconoptimizer\.buddy\.llama\.LlamaNative: .* $method\(" "$seeds" ||
        fail "LlamaNative.$method was not kept — the phone's model would be an UnsatisfiedLinkError"
done
grep -qE "^dev\.siliconoptimizer\.buddy\.llama\.LlamaSink: boolean onText\(byte\[\]\)" "$seeds" ||
    fail "LlamaSink.onText was not kept — native code finds it by name"
# The test probe reaches the engine by name from inside a minified build — and is in the
# build the tests run on, never in the one the owner installs.
grep -q "^dev.siliconoptimizer.buddy.ondevice.OnDeviceProbe$" "$seeds" &&
    fail "OnDeviceProbe is in the release build; it belongs to the releaseProbe build type only"
probeSeeds="$android/app/build/outputs/mapping/releaseProbe/seeds.txt"
[ -f "$probeSeeds" ] || fail "no seeds.txt for releaseProbe — did R8 run?"
grep -q "^dev.siliconoptimizer.buddy.ondevice.OnDeviceProbe$" "$probeSeeds" ||
    fail "OnDeviceProbe was not kept — the instrumented tests reach the engine through it"

# --- What the JNI bridge can reach ------------------------------------------------
# llama.cpp ships an HTTP client and a Hugging Face downloader in libllama-common. The
# bridge links that library for its chat templates and its sampling, and must not have
# brought any of the rest with it: nothing on this phone fetches a model except the app's
# own downloader, from the owner's Mac.
bridge=$(ls "$android"/app/build/outputs/apk/release/*.apk | head -1)
python3 - "$bridge" <<'PY'
import re, struct, sys, zipfile

data = zipfile.ZipFile(sys.argv[1]).read("lib/arm64-v8a/libbuddy_llama.so")
if data[:4] != b"\x7fELF" or data[4] != 2:
    sys.exit("libbuddy_llama.so is not an ELF64 object")
shoff, = struct.unpack_from("<Q", data, 0x28)
shentsize, shnum, shstrndx = struct.unpack_from("<HHH", data, 0x3A)
sections = []
for index in range(shnum):
    fields = struct.unpack_from("<IIQQQQIIQQ", data, shoff + index * shentsize)
    sections.append(dict(name=fields[0], offset=fields[4], size=fields[5], link=fields[6]))
def named(section):
    start = sections[shstrndx]["offset"] + section["name"]
    return data[start:data.index(b"\0", start)].decode()
dynsym = next(s for s in sections if named(s) == ".dynsym")
dynstr = sections[dynsym["link"]]
undefined = []
for index in range(dynsym["size"] // 24):
    st_name, _, _, st_shndx, _, _ = struct.unpack_from("<IBBHQQ", data, dynsym["offset"] + index * 24)
    if st_shndx == 0 and st_name:
        start = dynstr["offset"] + st_name
        undefined.append(data[start:data.index(b"\0", start)].decode())
forbidden = re.compile(
    r"^(socket|socketpair|connect|bind|listen|accept4?|send|sendto|sendmsg|recv|recvfrom|recvmsg|"
    r"getaddrinfo|gethostbyname\w*|inet_\w+|curl_\w+|SSL_\w+|fork|vfork|execv\w*|execl\w*|"
    r"system|popen|posix_spawn\w*)$",
)
bad = sorted({name for name in undefined if forbidden.match(name)})
print(f"bridge imports: {len(undefined)} symbols, {len(bad)} reaching a network or a process")
if bad:
    sys.exit("libbuddy_llama.so imports " + ", ".join(bad))
PY

# --- The APK ----------------------------------------------------------------------
apk=$(ls "$android"/app/build/outputs/apk/release/*.apk | head -1)
bytes=$(stat -f%z "$apk" 2>/dev/null || stat -c%s "$apk")
echo "release APK: $bytes bytes"
# The budget. 13.9 MB before the phone's own model; llama.cpp adds about 4 MB compressed.
[ "$bytes" -le 30000000 ] || fail "release APK is $bytes bytes, over the 30 MB budget"
listing=$(unzip -lv "$apk")
for library in libbuddy_llama libllama libllama-common libggml libggml-base libc++_shared \
               libggml-cpu-android_armv8.0_1 libggml-cpu-android_armv8.2_1 libggml-cpu-android_armv8.2_2 \
               libggml-cpu-android_armv8.6_1 libggml-cpu-android_armv9.0_1 libggml-cpu-android_armv9.2_1 \
               libggml-cpu-android_armv9.2_2; do
    line=$(echo "$listing" | grep " lib/arm64-v8a/$library.so$") || fail "$library.so is not in the APK"
    # Compressed (they are extracted at install, where llama.cpp looks for the CPU
    # variants), and stripped (under 5 MB each; unstripped, libllama-common is 65 MB).
    echo "$line" | grep -q " Defl:" || fail "$library.so is stored, not compressed"
    size=$(echo "$line" | awk '{print $1}')
    [ "$size" -lt 5000000 ] || fail "$library.so is $size bytes: not stripped?"
done

# --- What was downloaded to build it ----------------------------------------------
# llama.cpp fetches KleidiAI v1.24.0 at build time and pins it by MD5, which is not a hash
# to trust a download to. The archive that was actually fetched is checked here.
kleidiai=$(find "$android/llama/.cxx" -name "kleidiai-*-src.tar.gz" 2>/dev/null | head -1)
if [ -n "$kleidiai" ]; then
    sum=$(shasum -a 256 "$kleidiai" | awk '{print $1}')
    echo "kleidiai archive: $sum"
    [ "$sum" = "9348b969e042d8890a54b01a463dbe71f5a4c074b5329e9c26a85ef3b68aa19b" ] ||
        fail "the KleidiAI archive is not the pinned v1.24.0 release (got $sum)"
elif [ -z "${BUDDY_KLEIDIAI_VENDORED:-}" ]; then
    fail "no KleidiAI archive under android/llama/.cxx — build with -Pbuddy.kleidiaiSource and set BUDDY_KLEIDIAI_VENDORED=1 if it is vendored"
fi

# --- On a device ------------------------------------------------------------------
if $connected; then
    # Two passes. The main one, and then — on the fresh install every run begins with —
    # the one class that is about a phone where notifications were never granted, which
    # another class grants for the whole of the run above.
    notice=dev.siliconoptimizer.buddy.NotificationsOffTest
    ./gradlew --console=plain -q connectedReleaseProbeAndroidTest \
        "-Pandroid.testInstrumentationRunnerArguments.standin=$standin" \
        "-Pandroid.testInstrumentationRunnerArguments.notClass=$notice"
    python3 - "$android/app/build/outputs/androidTest-results/connected/releaseProbe" <<'PY'
import glob, re, sys
tests = failures = skipped = 0
for path in glob.glob(sys.argv[1] + "/**/*.xml", recursive=True):
    text = open(path).read()
    head = re.search(r'<testsuite [^>]*tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"', text)
    if head:
        tests += int(head.group(1)); failures += int(head.group(2)) + int(head.group(3))
        # A test whose assumption does not hold here (Qwen3.5 2B not served) is counted
        # in tests but ran nothing; said, so a green run with holes in it is visible.
        skip = re.search(r'<testsuite [^>]*skipped="(\d+)"', text)
        skipped += int(skip.group(1)) if skip else 0
print(f"instrumented tests: {tests}, failures: {failures}, skipped: {skipped}")
sys.exit(1 if failures or tests == 0 else 0)
PY
    ./gradlew --console=plain -q connectedReleaseProbeAndroidTest \
        "-Pandroid.testInstrumentationRunnerArguments.standin=$standin" \
        "-Pandroid.testInstrumentationRunnerArguments.class=$notice"
    python3 - "$android/app/build/outputs/androidTest-results/connected/releaseProbe" <<'PY'
import glob, re, sys
tests = failures = skipped = 0
for path in glob.glob(sys.argv[1] + "/**/*.xml", recursive=True):
    text = open(path).read()
    head = re.search(r'<testsuite [^>]*tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"', text)
    if head:
        tests += int(head.group(1)); failures += int(head.group(2)) + int(head.group(3))
        skip = re.search(r'<testsuite [^>]*skipped="(\d+)"', text)
        skipped += int(skip.group(1)) if skip else 0
print(f"instrumented tests (fresh install, notifications never granted): {tests}, failures: {failures}, skipped: {skipped}")
sys.exit(1 if failures or tests == 0 else 0)
PY
fi

if $ios; then
    cd "$here/ios"
    xcodegen generate >/dev/null
    xcodebuild -project SiliconBuddy.xcodeproj -scheme SiliconBuddy \
        -destination "${IOS_DESTINATION:-platform=iOS Simulator,name=iPad mini (A17 Pro)}" \
        -derivedDataPath "${IOS_DERIVED_DATA:?set IOS_DERIVED_DATA to a folder on a roomy drive}" \
        test | grep -E "Executed [0-9]+ tests|error:" | tail -3
    [ "${PIPESTATUS[0]}" -eq 0 ] || fail "the iOS suite failed"
fi

echo "OK"

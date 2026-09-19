#!/usr/bin/env bash
# The Android checks, run locally: GitHub Actions cannot run on this repository, so this is
# the CI. Exits non-zero on the first thing that is wrong, so it can gate a merge with &&.
#
#   scripts/ci-android.sh                 unit tests, minified release, keep rules, size budget
#   scripts/ci-android.sh --connected     …and the instrumented tests on $ANDROID_SERIAL, which
#                                         need the stand-in Mac (see docs/PLAN.md, M5)
#   scripts/ci-android.sh --ios           …and the iOS suite (contract/ is shared)
#
# Environment: JAVA_HOME (defaults to Android Studio's), ANDROID_SERIAL for --connected,
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

cd "$android"

# --- Unit tests -------------------------------------------------------------------
./gradlew --console=plain -q testDebugUnitTest assembleRelease
python3 - "$android/app/build/test-results/testDebugUnitTest" <<'PY'
import glob, re, sys
tests = failures = 0
for path in glob.glob(sys.argv[1] + "/*.xml"):
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
grep -q "^dev.siliconoptimizer.buddy.ondevice.OnDeviceProbe$" "$seeds" ||
    fail "OnDeviceProbe was not kept — the instrumented tests reach the engine through it"

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

# --- On a device ------------------------------------------------------------------
if $connected; then
    [ -n "${ANDROID_SERIAL:-}" ] || fail "--connected needs ANDROID_SERIAL"
    ./gradlew --console=plain -q connectedReleaseAndroidTest
    python3 - "$android/app/build/outputs/androidTest-results/connected/release" <<'PY'
import glob, re, sys
tests = failures = 0
for path in glob.glob(sys.argv[1] + "/**/*.xml", recursive=True):
    head = re.search(r'<testsuite [^>]*tests="(\d+)"[^>]*failures="(\d+)"[^>]*errors="(\d+)"', open(path).read())
    if head:
        tests += int(head.group(1)); failures += int(head.group(2)) + int(head.group(3))
print(f"instrumented tests: {tests}, failures: {failures}")
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

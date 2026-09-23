#!/usr/bin/env bash
# The stand-in Mac the Android on-device tests talk to: start | stop | status.
#
#   tools/standin/fetch-models.sh     once: the pinned test models, each checked by SHA-256
#   tools/standin/standin.sh start    serves them on 127.0.0.1:8916 — the emulator's 10.0.2.2:8916
#   tools/standin/standin.sh status
#   tools/standin/standin.sh stop
#
# Environment:
#   STANDIN_PORT           8916. Another port needs the tests told as well:
#                          BUDDY_STANDIN=10.0.2.2:<port> scripts/ci-android.sh --connected
#   STANDIN_HOST           127.0.0.1. Loopback is all the emulator needs; anything wider is an
#                          unauthenticated model API on your network.
#   BUDDY_STANDIN_MODELS   where fetch-models.sh put the models (tools/standin/models)
#   STANDIN_STATE          its log, request log and paired phones (tools/standin/state/<port>)
#   QWEN=0                 leaves Qwen3.5 2B out even when it was fetched; the two tests that
#                          need it are then skipped
#
# It stops only the process it started, found by its pid file, never whatever else is
# listening on the port.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
port="${STANDIN_PORT:-8916}"
host="${STANDIN_HOST:-127.0.0.1}"
models="${BUDDY_STANDIN_MODELS:-$here/models}"
state="${STANDIN_STATE:-$here/state/$port}"
pidfile="$state/demo_mac.pid"
# How to say this command again, from the repository, for the port in use.
again="tools/standin/standin.sh"
[ "$port" = 8916 ] || again="STANDIN_PORT=$port $again"

fail() { echo "FAIL: $*" >&2; exit 1; }

# The pid in the pid file, if that process is still this stand-in.
running() {
    [ -f "$pidfile" ] || return 1
    local pid
    pid=$(cat "$pidfile")
    [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null || return 1
    local command
    command=$(ps -o command= -p "$pid" 2>/dev/null) || return 1
    [[ "$command" == *demo_mac.py* ]] && echo "$pid"
}

answers() {
    curl -s -m 2 -o /dev/null "http://$host:$port/health" 2>/dev/null
}

case "${1:-}" in
    status)
        if pid=$(running); then
            echo "running (pid $pid) on $host:$port"
        else
            echo "stopped"
            exit 1
        fi
        ;;

    stop)
        if pid=$(running); then
            kill "$pid"
            for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.25; done
            rm -f "$pidfile"
            echo "stopped (pid $pid)"
        else
            rm -f "$pidfile"
            echo "not running"
        fi
        ;;

    start)
        if pid=$(running); then
            echo "already running (pid $pid) on $host:$port"
            exit 0
        fi
        answers && fail "something else is answering on $host:$port; pick another port: STANDIN_PORT=<port> tools/standin/standin.sh start"
        command -v python3 >/dev/null || fail "python3 (3.9 or later) is needed"
        [ -f "$models/stories260K-f32.gguf" ] && [ -f "$models/SmolLM2-135M-Instruct-Q8_0.gguf" ] ||
            fail "no test models in $models: run tools/standin/fetch-models.sh first"
        models="$(cd "$models" && pwd)"
        qwen="$models/Qwen_Qwen3.5-2B-Q4_0.gguf"
        if [ "${QWEN:-1}" = 0 ] || [ ! -f "$qwen" ]; then qwen=""; fi

        mkdir -p "$state"
        state="$(cd "$state" && pwd)"; pidfile="$state/demo_mac.pid"
        # Everything it writes goes in its state folder: the request log a test can read
        # back, the phones it has paired (kept across a restart, as a Mac keeps them), and
        # the flag file that makes it forget them.
        (
            cd "$state"
            SILICON_DEMO_HOST="$host" SILICON_DEMO_PORT="$port" \
                SILICON_DEMO_PHONE_MODELS="$models" SILICON_DEMO_QWEN="$qwen" \
                SILICON_DEMO_PHONE_READY="${READY:-0}" \
                SILICON_DEMO_REQUEST_LOG="$state/requests.log" \
                SILICON_DEMO_TOKEN_FILE="$state/tokens.txt" \
                SILICON_DEMO_REVOKE="$state/revoked" \
                nohup python3 "$here/demo_mac.py" > "$state/demo_mac.log" 2>&1 &
            echo $! > "$pidfile"
        )
        pid=$(cat "$pidfile")
        # Hashing the models comes before it listens: a second or two, more with Qwen.
        for _ in $(seq 1 180); do
            answers && break
            kill -0 "$pid" 2>/dev/null || { tail -20 "$state/demo_mac.log" >&2; rm -f "$pidfile"; fail "the stand-in exited"; }
            sleep 0.5
        done
        answers || fail "the stand-in (pid $pid) did not answer on $host:$port within 90 seconds; see $state/demo_mac.log"
        echo "started (pid $pid) on $host:$port — the emulator's 10.0.2.2:$port"
        grep "^phone models:" "$state/demo_mac.log" || true
        [ -n "$qwen" ] || echo "without Qwen3.5 2B: qwenAnswersWithThinkingOff and makeRoomSaysWhatIsFreeAndWhatThisAppCannotDo will be skipped"
        echo "stop it with: $again stop"
        ;;

    *)
        echo "usage: tools/standin/standin.sh start | stop | status" >&2
        exit 2
        ;;
esac

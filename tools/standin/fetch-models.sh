#!/usr/bin/env bash
# The model files the stand-in Mac serves to the on-device tests: from Hugging Face at
# pinned commits, each kept only once its size and SHA-256 are the pinned ones. A file
# already here with the right digest is not fetched again, and a cut download resumes.
#
#   tools/standin/fetch-models.sh           all three, about 1.44 GB
#   tools/standin/fetch-models.sh --small   without Qwen3.5 2B, 146 MB: the two tests that
#                                           read its template are then skipped
#
# Environment: BUDDY_STANDIN_MODELS, where they go (tools/standin/models, which git ignores;
# point it at a roomier drive if you like, and give standin.sh the same).
#
# The same pins are in OnDeviceModelTest (STORIES_SHA256, SMOL_SHA256) and, for Qwen, in the
# Mac's contract/GET__ondevice_models.json. Nothing here is ever committed.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
models="${BUDDY_STANDIN_MODELS:-$here/models}"
small=false
for argument in "$@"; do
    case "$argument" in
        --small) small=true ;;
        *) echo "usage: tools/standin/fetch-models.sh [--small]" >&2; exit 2 ;;
    esac
done

fail() { echo "FAIL: $*" >&2; exit 1; }

sha256() {
    if command -v shasum >/dev/null; then shasum -a 256 "$1" | awk '{print $1}'
    else sha256sum "$1" | awk '{print $1}'; fi
}
size() { stat -f%z "$1" 2>/dev/null || stat -c%s "$1"; }

# repository, commit, file, bytes, sha256
fetch() {
    local repo=$1 commit=$2 file=$3 bytes=$4 want=$5
    local target="$models/$file" part="$models/$file.part"
    if [ -f "$target" ] && [ "$(size "$target")" = "$bytes" ] && [ "$(sha256 "$target")" = "$want" ]; then
        echo "ok      $file (already here)"
        return
    fi
    rm -f "$target"
    echo "fetch   $file from $repo@${commit:0:12} ($bytes bytes)"
    local progress=-sS
    [ -t 2 ] && progress=--progress-bar
    curl -fL "$progress" --retry 3 --retry-delay 2 -C - -o "$part" \
        "https://huggingface.co/$repo/resolve/$commit/$file" ||
        fail "could not fetch $file from huggingface.co; run tools/standin/fetch-models.sh again to resume"
    local got
    got=$(sha256 "$part")
    if [ "$(size "$part")" != "$bytes" ] || [ "$got" != "$want" ]; then
        rm -f "$part"
        fail "$file is not the pinned file (sha256 $got, expected $want); it was deleted"
    fi
    mv "$part" "$target"
    echo "ok      $file (sha256 $want)"
}

mkdir -p "$models"
fetch ggml-org/test-model-stories260K 479896ec924af6d40fd419ab8f4d1eb2101de00d \
    stories260K-f32.gguf 1185376 270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d
fetch bartowski/SmolLM2-135M-Instruct-GGUF 09816acd5d99df7be770d85ea30822623dab342c \
    SmolLM2-135M-Instruct-Q8_0.gguf 144811360 5a1395716f7913741cc51d98581b9b1228d80987a9f7d3664106742eb06bba83
if $small; then
    echo "skip    Qwen_Qwen3.5-2B-Q4_0.gguf (--small)"
else
    fetch bartowski/Qwen_Qwen3.5-2B-GGUF 7d26695454df6de5fbcce2e58681e62dae06ce43 \
        Qwen_Qwen3.5-2B-Q4_0.gguf 1296764000 91c102fc9a86de80e427057ee938e1e34fcaf3bba956b7296e252406e05f36f6
fi
echo "models in $models; start the stand-in with: tools/standin/standin.sh start"

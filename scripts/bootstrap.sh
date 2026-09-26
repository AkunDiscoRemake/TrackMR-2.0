#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .deps app/src/main/assets
# Immutable upstream source. No prebuilt native binary from an untrusted APK.
revision=6eea12f99ba825086838554d7702217d780282be
if [ ! -d .deps/cardboard/.git ]; then
  git clone --depth 1 --branch v1.30.0 https://github.com/googlevr/cardboard.git .deps/cardboard
fi
actual=$(git -C .deps/cardboard rev-parse HEAD)
[[ "$actual" == "$revision" ]] || { echo "Unexpected Cardboard revision: $actual" >&2; exit 1; }
# Download model separately; weights are not committed. Versioned Google model URL.
model=app/src/main/assets/hand_landmarker.task
if [ ! -f "$model" ]; then
  curl --fail --location --retry 3 'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task' -o "$model.tmp"
  python3 scripts/verify_model.py "$model.tmp"
  mv "$model.tmp" "$model"
fi
python3 scripts/verify_model.py "$model"

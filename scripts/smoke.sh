#!/usr/bin/env bash
# Local smoke test (downloads checkpoint on first run).
set -euo pipefail
cd "$(dirname "$0")/.."
export USE_TF="${USE_TF:-0}"
export LAYA_MODEL="${LAYA_MODEL:-english}"
export LAYA_DEVICE="${LAYA_DEVICE:-cpu}"
uv run laya-smoke examples/request.json

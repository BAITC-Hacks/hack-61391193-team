#!/usr/bin/env bash
# Prepare tensors + fine-tune on your dataset.
set -euo pipefail
cd "$(dirname "$0")/.."

DATA="${1:-data/sample/train.jsonl}"
OUT_ITEMS="${2:-data/processed/train_items.pt}"
CKPT="${3:-checkpoints/laya-domain}"
EPOCHS="${EPOCHS:-4}"

export USE_TF="${USE_TF:-0}"
export LAYA_DEVICE="${LAYA_DEVICE:-cuda}"

echo "==> prepare $DATA"
uv run laya-prepare --data "$DATA" --out "$OUT_ITEMS" --max-len 1024 --head-max-len 256

echo "==> train → $CKPT (epochs=$EPOCHS)"
if command -v nvidia-smi >/dev/null 2>&1 && [[ "${LAYA_DEVICE}" == cuda* || "${LAYA_DEVICE}" == cuda ]]; then
  NGPU=$(nvidia-smi -L 2>/dev/null | wc -l | tr -d ' ')
  if [[ "${NGPU}" -gt 1 ]]; then
    uv run torchrun --nproc_per_node="${NGPU}" -m finetune.train \
      --items "$OUT_ITEMS" --output "$CKPT" --epochs "$EPOCHS"
  else
    uv run laya-train --items "$OUT_ITEMS" --output "$CKPT" --epochs "$EPOCHS" --device cuda
  fi
else
  echo "No GPU detected — training on CPU (slow, ok for tiny sample smoke)."
  uv run laya-train --items "$OUT_ITEMS" --output "$CKPT" --epochs 1 --device cpu \
    --micro-batch 2 --grad-accum 1 --no-amp
fi

echo "==> done. Try: LAYA_MODEL_PATH=$CKPT uv run laya-smoke examples/request.json"

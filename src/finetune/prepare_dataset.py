"""Convert JSONL domain data into Laya training tensors (train_items.pt).

Dataset schema (one JSON object per line)::

    {
      "state": {"body": "..."} | "plain text" | {...},
      "questions": {
        "department": {
          "type": "choice" | "score" | "noul",
          "instructions": "...",
          "criteria": {...} | [...]
        }
      },
      "gold": {
        "department": {
          "probabilities": {"billing": 0.9, "technical": 0.05, ...}
          # or for noul: {"false": 0.1, "true": 0.9}
          # or for score: {"0": 0.05, "1": 0.2, "2": 0.75}
        }
      }
    }

Hard labels are fine: put 1.0 on the true class and 0.0 elsewhere.
Soft teacher distributions (from an LLM judge) usually train better with RLCD.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

# Avoid TF deadlock on import.
os.environ.setdefault("USE_TF", "0")


def _normalize_probs(target: list[float]) -> list[float]:
    s = sum(target)
    if s <= 0:
        return [1.0 / len(target)] * len(target)
    return [v / s for v in target]


def build_training_item(state: Any, q: dict, gold_q: dict, tok, cfg: dict):
    from laya.common import QTYPES, build_sequence, render_options

    t = q["type"]
    crit = q.get("criteria", {})
    probs = gold_q.get("probabilities") or gold_q

    if t == "choice":
        keys = list(crit.keys())
        target = [float(probs.get(k, 0.0)) for k in keys]
    elif t == "noul":
        target = [float(probs.get("false", 0.5)), float(probs.get("true", 0.5))]
    elif t == "score":
        n_levels = len(crit) if isinstance(crit, list) else int(crit) if isinstance(crit, int) else 4
        target = [float(probs.get(str(i), probs.get(i, 0.0))) for i in range(n_levels)]
    else:
        raise ValueError(f"unsupported question type: {t}")

    target = _normalize_probs(target)
    label = int(max(range(len(target)), key=lambda i: target[i]))
    k = len(render_options({"t": t, "crit": crit}))

    seq, markers = build_sequence(
        tok,
        state,
        {"t": t, "ins": q["instructions"], "crit": crit},
        cfg["max_len"],
        cfg["head_max_len"],
    )
    if len(markers) != k:
        return None
    return {
        "ids": seq,
        "markers": markers,
        "qtype": QTYPES[t],
        "target": target,
        "label": label,
        "type_name": t,
    }


def load_rows(path: Path) -> list[dict]:
    rows: list[dict] = []
    if path.suffix == ".jsonl":
        with path.open(encoding="utf-8") as f:
            for line_no, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError as e:
                    raise ValueError(f"{path}:{line_no}: invalid JSON — {e}") from e
        return rows

    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict) and "rows" in payload:
        return payload["rows"]
    raise ValueError(f"{path}: expected JSONL, a JSON list, or {{'rows': [...]}}")


def prepare(
    data_path: Path,
    output_path: Path,
    model_id: str = "convaiinnovations/laya",
    subfolder: str | None = None,
    max_len: int | None = None,
    head_max_len: int | None = None,
) -> int:
    import torch
    from huggingface_hub import snapshot_download
    from transformers import AutoTokenizer

    from laya.agent import _fix_tokenizer_config

    allow = None
    if subfolder:
        # Download only the requested checkpoint subfolder + shared bits when possible.
        allow = [f"{subfolder}/*"]
        model_dir = snapshot_download(model_id, allow_patterns=allow)
        model_dir = os.path.join(model_dir, subfolder)
    else:
        model_dir = snapshot_download(model_id)

    _fix_tokenizer_config(model_dir)
    tok = AutoTokenizer.from_pretrained(os.path.join(model_dir, "tokenizer"))
    with open(os.path.join(model_dir, "rl_agent_config.json"), encoding="utf-8") as f:
        cfg = json.load(f)

    if max_len is not None:
        cfg["max_len"] = max_len
    if head_max_len is not None:
        cfg["head_max_len"] = head_max_len

    # Fine-tunes usually want more head budget than the English default (192).
    cfg.setdefault("max_len", 1024)
    cfg.setdefault("head_max_len", 256)

    items: list[dict] = []
    skipped = 0
    for row in load_rows(data_path):
        state = row["state"]
        if isinstance(state, str):
            try:
                state = json.loads(state)
            except json.JSONDecodeError:
                pass
        questions = row["questions"]
        if isinstance(questions, str):
            questions = json.loads(questions)
        gold = row["gold"]
        if isinstance(gold, str):
            gold = json.loads(gold)

        for qid, q in questions.items():
            if qid not in gold:
                skipped += 1
                continue
            it = build_training_item(state, q, gold[qid], tok, cfg)
            if it is None:
                skipped += 1
                continue
            it["qid"] = qid
            items.append(it)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    meta = {
        "model_id": model_id,
        "subfolder": subfolder,
        "n_items": len(items),
        "skipped": skipped,
        "max_len": cfg["max_len"],
        "head_max_len": cfg["head_max_len"],
        "source": str(data_path),
    }
    torch.save({"items": items, "meta": meta, "cfg": cfg, "model_dir": model_dir}, output_path)
    print(json.dumps(meta, indent=2))
    print(f"Wrote {len(items)} training sequences → {output_path}")
    return len(items)


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description="Prepare Laya RLCD training tensors from JSONL")
    p.add_argument(
        "--data",
        type=Path,
        default=Path("data/sample/train.jsonl"),
        help="JSONL or JSON dataset path",
    )
    p.add_argument(
        "--out",
        type=Path,
        default=Path("data/processed/train_items.pt"),
        help="Output .pt path",
    )
    p.add_argument("--model-id", default="convaiinnovations/laya")
    p.add_argument(
        "--subfolder",
        default=None,
        help="Checkpoint subfolder: multilingual | typed-decisions",
    )
    p.add_argument("--max-len", type=int, default=1024)
    p.add_argument("--head-max-len", type=int, default=256)
    args = p.parse_args(argv)

    if not args.data.exists():
        print(f"Dataset not found: {args.data}", file=sys.stderr)
        sys.exit(1)

    n = prepare(
        args.data,
        args.out,
        model_id=args.model_id,
        subfolder=args.subfolder,
        max_len=args.max_len,
        head_max_len=args.head_max_len,
    )
    if n == 0:
        print("No training items produced — check gold labels / criteria.", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()

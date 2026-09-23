"""Fine-tune Laya with RLCD (proper scoring rules + soft CE).

Adapted from the official notebook:
https://github.com/NandhaKishorM/laya/blob/main/notebooks/laya_finetune_typed_decisions_2xT4_kaggle.ipynb

Usage (single GPU)::

    uv run laya-train \\
      --items data/processed/train_items.pt \\
      --output checkpoints/laya-domain \\
      --epochs 4

Multi-GPU (DDP)::

    uv run torchrun --nproc_per_node=2 -m finetune.train \\
      --items data/processed/train_items.pt \\
      --output checkpoints/laya-domain \\
      --epochs 4
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from pathlib import Path

os.environ.setdefault("USE_TF", "0")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")


def collate_train_batch(items, pad_id):
    import torch

    n, L = len(items), max(len(it["ids"]) for it in items)
    kmax = max(len(it["markers"]) for it in items)
    ids = torch.full((n, L), pad_id, dtype=torch.long)
    att = torch.zeros((n, L), dtype=torch.long)
    mpos = torch.zeros((n, kmax), dtype=torch.long)
    mmask = torch.zeros((n, kmax), dtype=torch.bool)
    target = torch.zeros((n, kmax), dtype=torch.float32)
    for i, it in enumerate(items):
        ids[i, : len(it["ids"])] = torch.tensor(it["ids"])
        att[i, : len(it["ids"])] = 1
        k = len(it["markers"])
        mpos[i, :k] = torch.tensor(it["markers"])
        mmask[i, :k] = True
        target[i, : len(it["target"])] = torch.tensor(it["target"], dtype=torch.float32)
    return {
        "input_ids": ids,
        "attention_mask": att,
        "marker_pos": mpos,
        "marker_mask": mmask,
        "target": target,
        "qtype": torch.tensor([it["qtype"] for it in items]),
        "label": torch.tensor([it["label"] for it in items]),
    }


def fit_one_temp(sel):
    import torch

    if len(sel) < 10:
        return 1.0
    kmax = max(len(z) for z, _ in sel)
    Z = torch.full((len(sel), kmax), -1e4)
    T = torch.zeros((len(sel), kmax))
    for i, (z, t) in enumerate(sel):
        Z[i, : len(z)] = torch.tensor(z)
        T[i, : len(t)] = torch.tensor(t, dtype=torch.float32)
    log_t = torch.zeros(1, requires_grad=True)
    opt = torch.optim.LBFGS([log_t], lr=0.1, max_iter=100)

    def closure():
        opt.zero_grad()
        loss = -(T * torch.log_softmax(Z / log_t.exp(), -1)).sum(-1).mean()
        loss.backward()
        return loss

    opt.step(closure)
    return float(torch.clamp(log_t.exp(), 0.1, 10.0).item())


def _is_distributed() -> bool:
    return "RANK" in os.environ and "WORLD_SIZE" in os.environ


def _save_checkpoint(model, tok, cfg, output_dir: Path, fitted_temps: list[float], name: str):
    from safetensors.torch import save_file

    output_dir.mkdir(parents=True, exist_ok=True)
    sd = {k: v.half().contiguous().cpu() for k, v in model.state_dict().items()}
    save_file(sd, str(output_dir / "model.safetensors"))
    model.encoder.config.save_pretrained(str(output_dir / "encoder"))
    tok.save_pretrained(str(output_dir / "tokenizer"))
    out_cfg = dict(cfg)
    out_cfg["fine_tuned"] = True
    out_cfg["model_name"] = name
    out_cfg["temperature"] = fitted_temps
    out_cfg.pop("temperature_by_options", None)
    (output_dir / "rl_agent_config.json").write_text(
        json.dumps(out_cfg, indent=2), encoding="utf-8"
    )


def train(args: argparse.Namespace) -> None:
    import torch
    import torch.distributed as dist
    from safetensors.torch import load_file, save_file
    from transformers import AutoTokenizer

    from laya.common import build_model, proper_reward

    distributed = _is_distributed()
    if distributed:
        dist.init_process_group("nccl")
        rank = dist.get_rank()
        world_size = dist.get_world_size()
        local_rank = int(os.environ.get("LOCAL_RANK", "0"))
        torch.cuda.set_device(local_rank)
        device = torch.device("cuda", local_rank)
    else:
        rank, world_size, local_rank = 0, 1, 0
        if args.device:
            device = torch.device(args.device)
        else:
            device = torch.device("cuda" if torch.cuda.is_available() else "cpu")

    payload = torch.load(args.items, weights_only=False, map_location="cpu")
    if isinstance(payload, dict) and "items" in payload:
        all_items = payload["items"]
        cfg = dict(payload.get("cfg") or {})
        model_dir = payload.get("model_dir") or args.model_dir
    else:
        # Raw list saved by the Kaggle notebook style.
        all_items = payload
        cfg = {}
        model_dir = args.model_dir

    if not model_dir:
        raise SystemExit("--model-dir is required when train_items.pt has no embedded model_dir")

    cfg_path = Path(model_dir) / "rl_agent_config.json"
    with cfg_path.open(encoding="utf-8") as f:
        base_cfg = json.load(f)
    base_cfg.update(cfg)
    cfg = base_cfg
    cfg["gradient_checkpointing"] = True
    cfg["max_tokens_per_batch"] = args.max_tokens_per_batch
    cfg["max_len"] = args.max_len or cfg.get("max_len", 1024)
    cfg["head_max_len"] = args.head_max_len or cfg.get("head_max_len", 256)

    tok = AutoTokenizer.from_pretrained(os.path.join(model_dir, "tokenizer"))
    model = build_model(cfg, encoder_dir=os.path.join(model_dir, "encoder"))
    weights = load_file(os.path.join(model_dir, "model.safetensors"))
    model.load_state_dict(weights, strict=True)

    if device.type == "cuda":
        model.encoder.gradient_checkpointing_enable(
            gradient_checkpointing_kwargs={"use_reentrant": False}
        )
        model.head_checkpointing = True

    model.to(device)
    model.train()

    if distributed:
        from torch.nn.parallel import DistributedDataParallel as DDP

        train_model = DDP(model, device_ids=[local_rank], find_unused_parameters=True)
        raw_model = model
    else:
        train_model = model
        raw_model = model

    # Hold out calibration slice before sharding (fixed seed, shared across ranks).
    order = list(range(len(all_items)))
    random.Random(args.seed).shuffle(order)
    n_calib = min(args.calib_max, max(1, len(all_items) // 10))
    if len(all_items) < 20:
        n_calib = max(1, len(all_items) // 5)
    calib_items = [all_items[i] for i in sorted(order[:n_calib])]
    train_items = [all_items[i] for i in sorted(order[n_calib:])] or list(all_items)
    my_items = train_items[rank::world_size]

    epochs = args.epochs
    micro_batch = args.micro_batch
    grad_accum = args.grad_accum
    group_size = args.group_size
    sigma_start, sigma_end = args.sigma_start, args.sigma_end

    named = list(train_model.named_parameters())
    enc_params = [p for n, p in named if "encoder." in n]
    head_params = [p for n, p in named if "encoder." not in n]
    optimizer = torch.optim.AdamW(
        [
            {"params": enc_params, "lr": args.lr_encoder},
            {"params": head_params, "lr": args.lr_head},
        ],
        weight_decay=0.01,
    )
    total_updates = max(1, (len(my_items) // max(1, micro_batch * grad_accum)) * epochs)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(
        optimizer, T_max=total_updates, eta_min=1e-6
    )
    use_amp = device.type == "cuda" and not args.no_amp
    scaler = torch.amp.GradScaler("cuda", enabled=use_amp)

    output_dir = Path(args.output)
    if rank == 0:
        print(
            f"device={device} | train={len(train_items)} calib={len(calib_items)} "
            f"| per_rank={len(my_items)} | epochs={epochs}"
        )

    t0 = time.time()
    for epoch in range(epochs):
        random.seed(args.seed + epoch + rank)
        random.shuffle(my_items)
        epoch_loss, n_batches = 0.0, 0
        optimizer.zero_grad(set_to_none=True)
        accum_step = 0
        progress = epoch / max(1, epochs - 1)
        sigma = sigma_start + (sigma_end - sigma_start) * progress

        for b_idx in range(0, len(my_items), micro_batch):
            chunk = my_items[b_idx : b_idx + micro_batch]
            if not chunk:
                continue
            batch = collate_train_batch(chunk, tok.pad_token_id)

            with torch.autocast(device.type, dtype=torch.float16, enabled=use_amp):
                logits, act = train_model(
                    batch["input_ids"].to(device),
                    batch["attention_mask"].to(device),
                    batch["marker_pos"].to(device),
                    batch["marker_mask"].to(device),
                    batch["qtype"].to(device),
                )

            logits = logits.float()
            mask = batch["marker_mask"].to(device)
            k = mask.sum(-1, keepdim=True).float().clamp_min(1.0)
            target = batch["target"].to(device)

            eps = torch.randn((group_size,) + logits.shape, device=device) * sigma * mask
            eps = (eps - eps.sum(-1, keepdim=True) / k) * mask
            z = logits.detach().unsqueeze(0) + eps
            q = torch.softmax(z.masked_fill(~mask, -1e4), -1)

            with torch.no_grad():
                r = proper_reward(
                    q,
                    target.unsqueeze(0),
                    batch["qtype"].to(device),
                    mask,
                    w_sph=0.75,
                    w_rps=1.0,
                )
                adv = r - r.mean(0, keepdim=True)
                adv = adv / (adv.std() + 1e-6)

            logp = -(((z - logits.unsqueeze(0)) ** 2) * mask).sum(-1) / (2 * sigma**2)
            loss_rl = -(adv * logp).mean()
            loss_ce = -(
                target * torch.log_softmax(logits.masked_fill(~mask, -1e4), -1)
            ).sum(-1).mean()
            loss = (loss_rl + args.ce_weight * loss_ce) / grad_accum + 0.0 * act.sum()

            scaler.scale(loss).backward()
            accum_step += 1

            if accum_step % grad_accum == 0 or (b_idx + micro_batch) >= len(my_items):
                scaler.unscale_(optimizer)
                torch.nn.utils.clip_grad_norm_(train_model.parameters(), 1.0)
                scaler.step(optimizer)
                scaler.update()
                scheduler.step()
                optimizer.zero_grad(set_to_none=True)

            epoch_loss += float(loss.item()) * grad_accum
            n_batches += 1
            if rank == 0 and n_batches % 50 == 0:
                print(
                    f"  epoch {epoch + 1}/{epochs} step {n_batches} "
                    f"loss={loss.item() * grad_accum:.4f} reward={r.mean().item():.3f}"
                )

        if rank == 0:
            avg = epoch_loss / max(1, n_batches)
            print(f"=== epoch {epoch + 1}/{epochs} done in {time.time() - t0:.1f}s | avg_loss={avg:.4f}")
            ckpt_dir = output_dir / "checkpoint_latest"
            ckpt_dir.mkdir(parents=True, exist_ok=True)
            ckpt_sd = {k: v.half().contiguous().cpu() for k, v in raw_model.state_dict().items()}
            save_file(ckpt_sd, str(ckpt_dir / "model.safetensors"))
            raw_model.encoder.config.save_pretrained(str(ckpt_dir / "encoder"))
            tok.save_pretrained(str(ckpt_dir / "tokenizer"))
            (ckpt_dir / "checkpoint_meta.json").write_text(
                json.dumps(
                    {"epoch": epoch + 1, "total_epochs": epochs, "avg_loss": avg},
                    indent=2,
                ),
                encoding="utf-8",
            )

        if distributed:
            dist.barrier()

    # Calibration + final save on rank 0.
    if rank == 0:
        print("\nFitting calibration temperatures on held-out slice...")
        del optimizer, scaler, scheduler
        if device.type == "cuda":
            torch.cuda.empty_cache()
        raw_model.eval()
        calib_preds = []
        with torch.no_grad():
            for c_idx in range(0, len(calib_items), 16):
                c_chunk = calib_items[c_idx : c_idx + 16]
                cb = collate_train_batch(c_chunk, tok.pad_token_id)
                with torch.autocast(device.type, dtype=torch.float16, enabled=use_amp):
                    l_sub, _ = raw_model(
                        cb["input_ids"].to(device),
                        cb["attention_mask"].to(device),
                        cb["marker_pos"].to(device),
                        cb["marker_mask"].to(device),
                        cb["qtype"].to(device),
                    )
                l_np = l_sub.float().cpu().numpy()
                for row_i, it in enumerate(c_chunk):
                    k = len(it["markers"])
                    calib_preds.append((it["qtype"], l_np[row_i, :k], it["target"]))

        fitted_temps = [1.2, 1.2, 1.2]
        try:
            for qt in range(3):
                sel = [(z, t) for q_type, z, t in calib_preds if q_type == qt]
                if sel:
                    fitted_temps[qt] = fit_one_temp(sel)
            print("temperatures (choice, score, noul):", [round(t, 3) for t in fitted_temps])
        except Exception as e:  # noqa: BLE001
            print("temperature fitting fallback:", e)

        _save_checkpoint(
            raw_model,
            tok,
            cfg,
            output_dir,
            fitted_temps,
            name=args.checkpoint_name,
        )
        print(f"Saved fine-tuned checkpoint → {output_dir}")
        print("Load with: LAYA_MODEL_PATH=%s uv run laya-smoke" % output_dir)

    if distributed:
        dist.destroy_process_group()


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Fine-tune Laya with RLCD")
    p.add_argument("--items", type=Path, default=Path("data/processed/train_items.pt"))
    p.add_argument("--output", type=Path, default=Path("checkpoints/laya-domain"))
    p.add_argument(
        "--model-dir",
        default=None,
        help="Base checkpoint dir (default: embedded in train_items.pt)",
    )
    p.add_argument("--checkpoint-name", default="laya-domain")
    p.add_argument("--device", default=None, help="cuda / cpu / cuda:0 (single-process)")
    p.add_argument("--epochs", type=int, default=4)
    p.add_argument("--micro-batch", type=int, default=8)
    p.add_argument("--grad-accum", type=int, default=4)
    p.add_argument("--group-size", type=int, default=4)
    p.add_argument("--lr-encoder", type=float, default=2.5e-5)
    p.add_argument("--lr-head", type=float, default=1.0e-4)
    p.add_argument("--sigma-start", type=float, default=0.4)
    p.add_argument("--sigma-end", type=float, default=0.1)
    p.add_argument("--ce-weight", type=float, default=1.0)
    p.add_argument("--calib-max", type=int, default=400)
    p.add_argument("--seed", type=int, default=20260922)
    p.add_argument("--max-len", type=int, default=None)
    p.add_argument("--head-max-len", type=int, default=None)
    p.add_argument("--max-tokens-per-batch", type=int, default=4096)
    p.add_argument("--no-amp", action="store_true")
    return p


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    if not args.items.exists():
        print(
            f"Missing {args.items}. Run: uv run laya-prepare --data data/sample/train.jsonl",
            file=sys.stderr,
        )
        sys.exit(1)
    train(args)


if __name__ == "__main__":
    main()

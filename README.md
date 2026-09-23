# Hackathon scaffold — Laya + infra + fine-tune

Team repo for **Алем жив**. Prep stack: [Laya](https://huggingface.co/convaiinnovations/laya) + Postgres / Redis / Qdrant / MinIO, with [BGE-M3](https://huggingface.co/BAAI/bge-m3) for embeddings.

Versions are pinned in `pyproject.toml`, `uv.lock`, and `compose.yaml` image tags.

## Quick start

```bash
cp .env.example .env   # already present; edit secrets
uv sync

# Infra + Laya API
docker compose up -d --build

# Download BGE-M3 (~2GB) for Qdrant embeddings
uv run download-bge-m3

# Smoke Laya on host
./scripts/smoke.sh
```

| Service | Port | Notes |
| --- | --- | --- |
| Laya | `8000` | `POST /v1/systemone` |
| Postgres | `5432` | user/db from `.env` |
| Redis | `6379` | |
| Qdrant | `6333` / `6334` | HTTP / gRPC |
| MinIO | `9000` / `9001` | API / console |

```bash
# GPU Laya image
LAYA_TORCH_INDEX=cu128 LAYA_DEVICE=cuda docker compose up -d --build laya

# Fine-tune (NVIDIA)
docker compose --profile train run --rm laya-train

# SDK smoke container
docker compose --profile tools run --rm laya-smoke
```

## Fine-tune

```bash
./scripts/finetune.sh data/sample/train.jsonl
# or: uv run laya-prepare … && uv run laya-train …
```

Sample JSONL schema is in `data/sample/`. Official recipe notebook: `notebooks/laya_finetune_typed_decisions_2xT4_kaggle.ipynb`.

## Embeddings → Qdrant

```python
from app.embeddings import embed_texts, ensure_collection

ensure_collection()  # creates collection with dim=1024 (cosine)
vecs = embed_texts(["duplicate invoice refund"])
```

Local weights live in `models/bge-m3` (`BGE_M3_PATH`).

## Layout

```
compose.yaml          # single stack: postgres redis qdrant minio laya (+train/tools profiles)
Dockerfile            # Laya inference/serve
Dockerfile.train      # CUDA fine-tune
src/app/              # smoke, server, embeddings, bge-m3 download
src/finetune/         # RLCD prepare + train
data/sample/          # starter JSONL
models/bge-m3/        # downloaded weights (gitignored)
checkpoints/          # fine-tuned Laya (gitignored)
```

Host `uv sync` uses CPU torch by default (`tool.uv.sources`). GPU training goes through `Dockerfile.train`.

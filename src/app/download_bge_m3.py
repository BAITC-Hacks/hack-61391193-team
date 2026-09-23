"""Download BAAI/bge-m3 weights for local Qdrant embedding pipelines."""

from __future__ import annotations

import argparse
import os
from pathlib import Path


REPO_ID = "BAAI/bge-m3"


def download(dest: Path, token: str | None = None) -> Path:
    from huggingface_hub import snapshot_download

    dest.mkdir(parents=True, exist_ok=True)
    path = snapshot_download(
        repo_id=REPO_ID,
        local_dir=str(dest),
        token=token,
    )
    return Path(path)


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=f"Download {REPO_ID} for Qdrant embeddings")
    p.add_argument(
        "--dest",
        type=Path,
        default=Path(os.environ.get("BGE_M3_PATH", "models/bge-m3")),
        help="Target directory (default: models/bge-m3 or $BGE_M3_PATH)",
    )
    p.add_argument("--token", default=os.environ.get("HF_TOKEN") or None)
    args = p.parse_args(argv)
    path = download(args.dest, token=args.token)
    print(f"Downloaded {REPO_ID} → {path}")


if __name__ == "__main__":
    main()

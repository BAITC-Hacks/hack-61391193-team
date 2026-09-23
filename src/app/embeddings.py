"""BGE-M3 embeddings helper for Qdrant (dense 1024-d vectors)."""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Sequence

import numpy as np


@lru_cache(maxsize=1)
def _model():
    from sentence_transformers import SentenceTransformer

    path = os.environ.get("BGE_M3_PATH", "models/bge-m3")
    local = Path(path)
    model_id = str(local) if local.exists() and any(local.iterdir()) else "BAAI/bge-m3"
    return SentenceTransformer(model_id)


def embed_texts(texts: Sequence[str], *, normalize: bool = True) -> np.ndarray:
    """Encode texts → (n, 1024) float32. Prefers local weights under BGE_M3_PATH."""
    vectors = _model().encode(
        list(texts),
        normalize_embeddings=normalize,
        show_progress_bar=False,
    )
    return np.asarray(vectors, dtype=np.float32)


def ensure_collection(
    collection: str | None = None,
    *,
    url: str | None = None,
    dim: int | None = None,
) -> str:
    """Create a Qdrant collection for BGE-M3 dense vectors if missing."""
    from qdrant_client import QdrantClient
    from qdrant_client.http import models as qm

    collection = collection or os.environ.get("QDRANT_COLLECTION", "documents")
    url = url or os.environ.get("QDRANT_URL", "http://localhost:6333")
    dim = dim or int(os.environ.get("EMBEDDING_DIM", "1024"))
    client = QdrantClient(url=url, timeout=30)
    names = {c.name for c in client.get_collections().collections}
    if collection not in names:
        client.create_collection(
            collection_name=collection,
            vectors_config=qm.VectorParams(size=dim, distance=qm.Distance.COSINE),
        )
    return collection

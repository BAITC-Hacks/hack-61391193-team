# syntax=docker/dockerfile:1

FROM python:3.11.16-slim-bookworm AS build

ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

COPY --from=ghcr.io/astral-sh/uv:0.11.19 /uv /usr/local/bin/uv

# CPU by default; override TORCH_INDEX=cu128 for GPU builds.
ARG TORCH_INDEX=cpu
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" \
    VIRTUAL_ENV=/opt/venv

WORKDIR /src
COPY pyproject.toml README.md uv.lock ./
COPY src ./src
# Install torch from the selected index first. --no-sources ignores the host
# pyproject CPU pin so CUDA builds keep the GPU wheel.
RUN uv pip install --python /opt/venv/bin/python \
      "torch==2.14.0" --index-url "https://download.pytorch.org/whl/${TORCH_INDEX}" \
 && uv pip install --python /opt/venv/bin/python --no-sources .

FROM python:3.11.16-slim-bookworm AS runtime

LABEL org.opencontainers.image.title="hack-61391193-team" \
      org.opencontainers.image.licenses="Apache-2.0"

ENV PATH="/opt/venv/bin:$PATH" \
    VIRTUAL_ENV=/opt/venv \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    USE_TF=0 \
    USE_TORCH=1 \
    TOKENIZERS_PARALLELISM=false \
    OMP_NUM_THREADS=4 \
    LAYA_DEVICE=cpu \
    LAYA_HOST=0.0.0.0 \
    LAYA_PORT=8000 \
    LAYA_PRELOAD=1 \
    HF_HOME=/home/laya/.cache/huggingface \
    BGE_M3_PATH=/models/bge-m3

RUN groupadd --gid 10001 laya \
 && useradd --uid 10001 --gid laya --create-home laya \
 && mkdir -p /home/laya/.cache/huggingface /workspace /models \
 && chown -R laya:laya /home/laya /workspace /models

COPY --from=build /opt/venv /opt/venv
COPY entrypoint.py /opt/laya/entrypoint.py
COPY examples /opt/laya/examples
COPY src /opt/laya/src

ENV PYTHONPATH=/opt/laya/src

USER laya
WORKDIR /workspace

EXPOSE 8000
ENTRYPOINT ["python", "/opt/laya/entrypoint.py"]
CMD ["python", "-m", "app.server"]

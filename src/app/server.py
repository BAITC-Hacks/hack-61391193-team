"""FastAPI wrapper around laya.serve for docker / local HTTP inference."""

from __future__ import annotations

import os

# Avoid TensorFlow probe deadlocks during transformers import.
os.environ.setdefault("USE_TF", "0")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")


def create_app():
    """Prefer upstream laya.serve when available; fall back to a thin local app."""
    try:
        from laya.serve import create_app as laya_create_app

        return laya_create_app()
    except Exception:
        # Older laya without serve extra / import issues — minimal local surface.
        from fastapi import FastAPI, HTTPException
        import laya
        from laya import Router

        app = FastAPI(title="hack-laya", version="0.1.0")
        device = os.environ.get("LAYA_DEVICE") or None
        model_path = os.environ.get("LAYA_MODEL_PATH")
        agent = None
        router = None
        if model_path:
            agent = laya.load(model_path, device=device)
        else:
            router = Router(
                preload=os.environ.get("LAYA_PRELOAD", "1") in ("1", "true", "yes"),
                device=device,
            )

        @app.get("/health")
        def health():
            return {
                "status": "ok",
                "mode": "checkpoint" if agent is not None else "router",
                "device": device or "auto",
            }

        @app.post("/v1/systemone")
        def systemone(body: dict):
            if "questions" not in body:
                raise HTTPException(400, detail="body must include 'questions'")
            state = body.get("state")
            questions = body["questions"]
            model = body.get("model")
            if agent is not None:
                return agent.predict(state, questions)
            return router.predict(state, questions, model=model)

        return app


def main() -> None:
    import uvicorn

    uvicorn.run(
        create_app(),
        host=os.environ.get("LAYA_HOST", "0.0.0.0"),
        port=int(os.environ.get("LAYA_PORT", "8000")),
        log_level=os.environ.get("LAYA_LOG_LEVEL", "info"),
    )


if __name__ == "__main__":
    main()

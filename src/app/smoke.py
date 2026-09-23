"""HTTP smoke / demo client helpers and CLI entrypoint."""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path


DEFAULT_REQUEST = {
    "state": {
        "from": "user@acme.com",
        "subject": "Duplicate charge on invoice #4411",
        "body": (
            "Hi, we were billed twice for March. "
            "Please refund the duplicate today or we will cancel our plan."
        ),
    },
    "questions": {
        "department": {
            "type": "choice",
            "instructions": "Which department should handle this request?",
            "criteria": {
                "billing": "invoices, payments, refunds",
                "technical": "bugs, outages, system errors",
                "sales": "pricing, new contracts",
                "other": "everything else",
            },
        },
        "urgency": {
            "type": "score",
            "instructions": "How urgent is this request?",
            "criteria": ["not urgent", "soon", "critical deadline or blocking issue"],
        },
        "churn_risk": {
            "type": "noul",
            "instructions": "Does the user threaten to cancel or leave?",
        },
        "refund_requested": {
            "type": "noul",
            "instructions": "Does the user explicitly request a refund?",
        },
    },
}


def run_local_predict(request: dict | None = None) -> dict:
    """Load Laya (or a fine-tuned checkpoint) and run one forward pass."""
    os.environ.setdefault("USE_TF", "0")
    import laya

    request = request or DEFAULT_REQUEST
    device = os.environ.get("LAYA_DEVICE") or None
    model_path = os.environ.get("LAYA_MODEL_PATH")
    model_alias = os.environ.get("LAYA_MODEL", "english")

    if model_path:
        agent = laya.load(model_path, device=device)
        return agent.predict(request["state"], request["questions"])

    # Prefer single-checkpoint load for a fast smoke test; Router needs more VRAM/disk.
    if model_alias in ("auto", "router"):
        from laya import Router

        preload = os.environ.get("LAYA_PRELOAD", "0") in ("1", "true", "yes")
        router = Router(preload=preload, device=device)
        return router.predict(request["state"], request["questions"])

    repo = "convaiinnovations/laya"
    subfolder = None
    if model_alias == "multilingual":
        subfolder = "multilingual"
    elif model_alias == "typed-decisions":
        subfolder = "typed-decisions"
    elif model_alias != "english":
        # Treat unknown values as a Hub repo id or local path.
        agent = laya.load(model_alias, device=device)
        return agent.predict(request["state"], request["questions"])

    agent = laya.load(repo, subfolder=subfolder, device=device)
    return agent.predict(request["state"], request["questions"])


def main(argv: list[str] | None = None) -> None:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] in ("-h", "--help"):
        print(
            "Usage: laya-smoke [request.json]\n"
            "Env: LAYA_MODEL=english|multilingual|typed-decisions|auto  "
            "LAYA_DEVICE=cpu|cuda  LAYA_MODEL_PATH=/path/to/checkpoint"
        )
        return
    request = DEFAULT_REQUEST
    if argv:
        path = Path(argv[0])
        request = json.loads(path.read_text(encoding="utf-8"))
    result = run_local_predict(request)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

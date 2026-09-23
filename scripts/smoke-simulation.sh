#!/usr/bin/env bash
# Smoke test for a running backend-akim: posts the dataset example and checks the v2-saraishyk numbers.
# Usage: scripts/smoke-simulation.sh [base-url]   (default http://localhost:8080)
# EXPECT_SOURCE=llm|template additionally checks explanation.source.
set -euo pipefail
cd "$(dirname "$0")/.."
BASE_URL="${1:-${BACKEND_URL:-http://localhost:8080}}"
REQUEST="${SMOKE_REQUEST:-docs1/simulation-example.json}"

body="$(mktemp)"
trap 'rm -f "$body"' EXIT
timing="$(curl -sS -o "$body" -w '%{http_code} %{time_total}' \
  -H 'Content-Type: application/json' --data @"$REQUEST" \
  "$BASE_URL/api/v1/simulation/calculate")"
echo "POST /api/v1/simulation/calculate -> HTTP ${timing% *} in ${timing#* }s"

python3 - "$body" "${timing% *}" "${EXPECT_SOURCE:-}" <<'PY'
import json, sys
from decimal import Decimal

path, status, expect_source = sys.argv[1], sys.argv[2], sys.argv[3]
if status != "200":
    sys.exit(f"FAIL: expected HTTP 200, got {status}: {open(path).read()[:500]}")
r = json.load(open(path), parse_float=Decimal)
e, best, cmp = r["explanation"], r.get("bestSolution"), r.get("comparison")
checks = [
    ("modelVersion == v2-saraishyk", r["modelVersion"] == "v2-saraishyk"),
    ("6 districts incl. saraishyk", len(r["districts"]) == 6 and any(d["id"] == "saraishyk" for d in r["districts"])),
    ("finalScore == 56.31781049", r["finalScore"] == Decimal("56.31781049")),
    ("displayScore == 56.32", r["displayScore"] == Decimal("56.32")),
    ("baselineScore == 52.33242049", r["baselineScore"] == Decimal("52.33242049")),
    ("budget spent 95 of 100", (r["budget"]["spent"], r["budget"]["limit"]) == (95, 100)),
    ("synergy M10+M12 applied", any(s["measureIds"] == ["M10", "M12"] for s in r["synergies"])),
    ("explanation.summary non-empty", bool(e["summary"].strip())),
    ("explanation.source in llm/template", e["source"] in ("llm", "template")),
    ("bestSolution.finalScore == 57.01147549", best is not None and best["finalScore"] == Decimal("57.01147549")),
    ("bestSolution.provenOptimal", best is not None and best["provenOptimal"] is True),
    ("comparison.scoreGap == 0.693665", cmp is not None and cmp["scoreGap"] == Decimal("0.693665")),
    ("comparison.isOptimal is false", cmp is not None and cmp["isOptimal"] is False),
]
if expect_source:
    checks.append((f"explanation.source == {expect_source}", e["source"] == expect_source))
failed = [name for name, ok in checks if not ok]
for name, ok in checks:
    print(("ok   " if ok else "FAIL ") + name)
print(f"explanation.source = {e['source']}")
sys.exit(1 if failed else 0)
PY

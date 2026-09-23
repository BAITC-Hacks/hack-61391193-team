#!/usr/bin/env bash
# Runs backend-akim locally with SIMULATION_LLM_* from .env (without a database).
# SIMULATION_LLM_API_KEY falls back to OPENAI_API_KEY; the key is never printed.
set -euo pipefail
cd "$(dirname "$0")/.."

env_value() {
  [ -f .env ] || return 0
  grep -E "^[[:space:]]*(export[[:space:]]+)?$1=" .env | tail -1 \
    | sed -E 's/^[^=]*=//; s/^["'\'']//; s/["'\''][[:space:]]*$//' || true
}

# Precedence: process environment, then .env, then the demo defaults. Empty values are never exported:
# Spring treats an empty SIMULATION_LLM_TIMEOUT_MS as set and fails to start.
declare -A defaults=(
  [SIMULATION_LLM_URL]=https://api.openai.com/v1/chat/completions
  [SIMULATION_LLM_MODEL]=gpt-4.1-mini-2025-04-14
  [SIMULATION_LLM_TIMEOUT_MS]=10000
)
for name in "${!defaults[@]}"; do
  value="${!name:-}"
  [ -n "$value" ] || value="$(env_value "$name")"
  [ -n "$value" ] || value="${defaults[$name]}"
  export "$name=$value"
done
if [ -z "${SIMULATION_LLM_API_KEY:-}" ]; then
  SIMULATION_LLM_API_KEY="$(env_value SIMULATION_LLM_API_KEY)"
  [ -n "$SIMULATION_LLM_API_KEY" ] || SIMULATION_LLM_API_KEY="${OPENAI_API_KEY:-$(env_value OPENAI_API_KEY)}"
fi
if [ -n "$SIMULATION_LLM_API_KEY" ]; then export SIMULATION_LLM_API_KEY; else unset SIMULATION_LLM_API_KEY; fi

echo "LLM url:   $SIMULATION_LLM_URL"
echo "LLM model: $SIMULATION_LLM_MODEL"
echo "LLM key:   $([ -n "${SIMULATION_LLM_API_KEY:-}" ] && echo set || echo "empty (explanations use the template)")"

JAR=backend-akim/target/backend-akim-0.0.1-SNAPSHOT.jar
[ -f "$JAR" ] || (cd backend-akim && ./mvnw -q -DskipTests package)
exec java -jar "$JAR"

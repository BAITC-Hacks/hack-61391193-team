#!/usr/bin/env bash
# Run from the local machine after the Docker 1-Click VPS is available.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <server-ip> <ssh-user> <local-ssh-private-key-path>" >&2
  exit 2
fi
server_ip=$1
ssh_user=$2
ssh_key=$3
[[ $server_ip =~ ^[A-Za-z0-9.:-]+$ ]] || { echo "Invalid server address" >&2; exit 2; }
[[ $ssh_user =~ ^[a-z_][a-z0-9_-]*$ ]] || { echo "Invalid SSH user" >&2; exit 2; }
[ -r "$ssh_key" ] || { echo "SSH key is not readable" >&2; exit 2; }

project_root=$(cd "$(dirname "$0")/.." && pwd)
frontend_root=$(cd "$project_root/../hack-61391193-team-front-end/frontend" && pwd)
[ -f "$project_root/.env" ] || { echo "Missing local .env" >&2; exit 2; }
[ -f "$frontend_root/package-lock.json" ] || { echo "Missing frontend worktree" >&2; exit 2; }

ssh_options=(-i "$ssh_key" -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30)
remote="$ssh_user@$server_ip"

ssh "${ssh_options[@]}" "$remote" 'docker compose version >/dev/null && mkdir -p "$HOME/akim-demo/backend" "$HOME/akim-demo/frontend/frontend"'

tar -C "$project_root" --exclude='backend-akim/target' -czf - backend-akim data1/overture/astana deploy \
  | ssh "${ssh_options[@]}" "$remote" 'tar -xzf - -C "$HOME/akim-demo/backend"'
tar -C "$frontend_root" --exclude='./node_modules' --exclude='./.next' --exclude='./.git' --exclude='./.env*' -czf - . \
  | ssh "${ssh_options[@]}" "$remote" 'tar -xzf - -C "$HOME/akim-demo/frontend/frontend"'
scp -q "${ssh_options[@]}" "$project_root/.env" "$remote:akim-demo/backend/.env"

ssh "${ssh_options[@]}" "$remote" 'set -e
  chmod 600 "$HOME/akim-demo/backend/.env"
  cp "$HOME/akim-demo/backend/deploy/Frontend.Dockerfile" "$HOME/akim-demo/frontend/frontend/Dockerfile.deploy"
  cp "$HOME/akim-demo/backend/deploy/Frontend.Dockerfile.dockerignore" "$HOME/akim-demo/frontend/frontend/Dockerfile.deploy.dockerignore"
  cd "$HOME/akim-demo/backend"
  export FRONTEND_CONTEXT="$HOME/akim-demo/frontend/frontend"
  docker compose --env-file .env -f deploy/compose.yaml config --quiet
  docker compose --env-file .env -f deploy/compose.yaml build backend-akim
  docker compose --env-file .env -f deploy/compose.yaml build frontend
  docker compose --env-file .env -f deploy/compose.yaml up -d
  docker compose --env-file .env -f deploy/compose.yaml ps
  for attempt in $(seq 1 30); do
    url=$(docker compose --env-file .env -f deploy/compose.yaml logs --no-color tunnel 2>/dev/null \
      | grep -Eo "https://[A-Za-z0-9-]+[.]trycloudflare[.]com" | tail -1 || true)
    if [ -n "$url" ]; then
      echo "Demo URL: $url"
      exit 0
    fi
    sleep 2
  done
  docker compose --env-file .env -f deploy/compose.yaml logs --tail=30 tunnel
  exit 1
'

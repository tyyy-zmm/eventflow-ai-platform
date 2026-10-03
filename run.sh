#!/bin/bash
set -euo pipefail
project="$(cd "$(dirname "$0")" && pwd)"
cd "$project"
export PATH="$HOME/.docker/bin:$PATH"
case "${1:-help}" in
  init) node backend/scripts/init-env.mjs ;;
  up|stop|test|package) exec bash backend/scripts/local.sh "$@" ;;
  build) bash backend/scripts/local.sh package; (cd frontend; npm ci; npm run build) ;;
  backend) exec bash backend/scripts/local.sh start --upgrade.demo-data=true "${@:2}" ;;
  frontend) exec npm --prefix frontend run dev ;;
  dev)
    bash backend/scripts/local.sh start --upgrade.demo-data=true & backend_pid=$!
    npm --prefix frontend run dev & frontend_pid=$!
    trap 'kill "$backend_pid" "$frontend_pid" 2>/dev/null || true; wait || true' EXIT
    trap 'exit 130' INT TERM
    # Stop the other process if either service exits, on macOS Bash as well.
    while kill -0 "$backend_pid" 2>/dev/null && kill -0 "$frontend_pid" 2>/dev/null; do sleep 1; done
    exit 1 ;;
  verify)
    node backend/scripts/init-env.mjs
    docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot' <<'SQL'
CREATE DATABASE IF NOT EXISTS life_choice_verification;
GRANT ALL PRIVILEGES ON life_choice_verification.* TO 'life_choice'@'%';
SQL
    exec bash backend/scripts/verification.sh package ;;
  *) echo 'Usage: bash run.sh init|up|build|dev|backend|frontend|test|verify|stop' ;;
esac

#!/bin/bash
set -euo pipefail
backend="$(cd "$(dirname "$0")/.." && pwd)"
project="$(cd "$backend/.." && pwd)"
cd "$backend"
node scripts/init-env.mjs
set -a
source "$project/.env"
set +a
export UPGRADE_ROOT_PASSWORD="${UPGRADE_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD}"
export UPGRADE_DB_PASSWORD="${UPGRADE_DB_PASSWORD:-$MYSQL_PASSWORD}"
export UPGRADE_REDIS_PASSWORD="${UPGRADE_REDIS_PASSWORD:-$REDIS_PASSWORD}"
export UPGRADE_AUTH_SECRET="${UPGRADE_AUTH_SECRET:-$APP_AUTH_SECRET}"
export DATABASE_URL="${DATABASE_URL:-jdbc:mysql://127.0.0.1:${MYSQL_PORT:-23307}/life_choice?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000}"
export DATABASE_USERNAME="${DATABASE_USERNAME:-life_choice}"
export DATABASE_PASSWORD="${DATABASE_PASSWORD:-$MYSQL_PASSWORD}"
export REDIS_PORT="${REDIS_PORT:-26380}"
export KAFKA_BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:${KAFKA_PORT:-29093}}"
tools="$HOME/Library/Application Support/EventFlow/tools"
if [[ -d "$tools/jdk17/Contents/Home" ]]; then
  export JAVA_HOME="$tools/jdk17/Contents/Home"
  export PATH="$JAVA_HOME/bin:$tools/maven/bin:$HOME/.docker/bin:$PATH"
fi
maven=(mvn)
if [[ -d "$project/.m2" ]]; then maven+=("-Dmaven.repo.local=$project/.m2");
elif [[ -d "$tools/m2" ]]; then maven+=("-Dmaven.repo.local=$tools/m2"); fi
case "${1:-test}" in
  up) docker compose -f "$project/compose.yaml" up -d --wait --wait-timeout 240 mysql redis kafka ;;
  stop) docker compose -f "$project/compose.yaml" stop mysql redis kafka ;;
  test) exec "${maven[@]}" test ;;
  package) exec "${maven[@]}" clean package ;;
  integration) export UPGRADE_INTEGRATION=true; exec "${maven[@]}" -Dtest=MiddlewareTest test ;;
  reliability-test) export UPGRADE_RELIABILITY_INTEGRATION=true; exec "${maven[@]}" -Dtest=ReliabilityIntegrationTest test ;;
  customer-test) export UPGRADE_INTEGRATION=true; exec "${maven[@]}" -Dtest=CustomerHttpTest test ;;
  start) exec java -Xmx384m -jar target/life-choice-backend-1.0.0.jar "${@:2}" ;;
  hotspot-experiment|pipeline-experiment|pipeline-crash|verification-experiment|multilevel-benchmark) exec node "scripts/$1.mjs" "${@:2}" ;;
  benchmark) exec node scripts/benchmark.mjs "${@:2}" ;;
  redis-db-benchmark) exec node scripts/redis-db-benchmark.mjs "${@:2}" ;;
  full-chain-benchmark) exec node scripts/full-chain-benchmark.mjs "${@:2}" ;;
  order-capacity-benchmark) exec node scripts/order-capacity-benchmark.mjs "${@:2}" ;;
  faults) exec node scripts/faults.mjs ;;
  *) echo 'Usage: local.sh up|stop|test|package|integration|customer-test|start|benchmark|redis-db-benchmark|full-chain-benchmark|order-capacity-benchmark|faults'; exit 2 ;;
esac

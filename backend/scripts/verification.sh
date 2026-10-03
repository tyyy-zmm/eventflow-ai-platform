#!/bin/bash
set -euo pipefail
backend="$(cd "$(dirname "$0")/.." && pwd)"
export DATABASE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT:-23307}/life_choice_verification?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=5000"
export UPGRADE_DB_SCHEMA=life_choice_verification
export UPGRADE_BASE_URL=http://127.0.0.1:18096
export UPGRADE_DB_URL="$DATABASE_URL"
export UPGRADE_TEST_DB_URL="$DATABASE_URL"
export UPGRADE_TEST_REDIS_DB=13
export SPRING_DATA_REDIS_DATABASE=13
export UPGRADE_TOPIC=life-choice-verification-v1
export UPGRADE_GROUP=life-choice-verification-v1
export CLOSE_QUEUE_KEY=ux:verification:close:due
export UPGRADE_INTEGRATION=true
export UPGRADE_RELIABILITY_INTEGRATION=true
exec bash "$backend/scripts/local.sh" "${@:-test}"

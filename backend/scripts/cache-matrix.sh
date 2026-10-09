#!/bin/bash
set -euo pipefail
backend="$(cd "$(dirname "$0")/.." && pwd)"
out="$backend/evidence/cache-matrix-$(date +%s)"
mkdir -p "$out"
export CACHE_BENCH_SECONDS="${CACHE_BENCH_SECONDS:-20}"
export CACHE_BENCH_ROUNDS="${CACHE_BENCH_ROUNDS:-4}"
export CACHE_BENCH_WARMUP_SECONDS="${CACHE_BENCH_WARMUP_SECONDS:-5}"
export CACHE_BENCH_MODES=ttl,optimized
run_case() {
  name="$1"
  export CACHE_BENCH_AUTH="$2" CACHE_BENCH_CONCURRENCY="$3" CACHE_BENCH_SHOPS="$4" CACHE_BENCH_DISTRIBUTION="$5"
  echo "Starting $name"
  bash "$backend/scripts/verification.sh" multilevel-benchmark > "$out/$name.log" 2>&1
  tail -n 2 "$out/$name.log"
}
run_case cookie-c8-uniform20 cookie 8 20 uniform
run_case cookie-c32-uniform20 cookie 32 20 uniform
run_case cookie-c32-hot100 cookie 32 100 hot80
run_case bearer-c8-uniform20 bearer 8 20 uniform
printf 'Matrix evidence: %s\n' "$out"

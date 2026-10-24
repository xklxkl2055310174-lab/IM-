#!/usr/bin/env bash
# 运行 IM 压测
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/im-bench/target/im-bench.jar"

if [[ ! -f "$JAR" ]]; then
  echo "未找到 $JAR，请先执行: mvn clean package" >&2
  exit 1
fi

HOST="${1:-127.0.0.1}"
PORT="${2:-8080}"
CONNECTIONS="${3:-8}"
MESSAGES="${4:-500}"
SIZE="${5:-64}"

exec java -jar "$JAR" --host "$HOST" --port "$PORT" \
  --connections "$CONNECTIONS" --messages "$MESSAGES" --size "$SIZE"

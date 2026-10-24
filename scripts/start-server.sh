#!/usr/bin/env bash
# 启动 IM 服务端
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/im-server/target/im-server.jar"

if [[ ! -f "$JAR" ]]; then
  echo "未找到 $JAR，请先执行: mvn clean package" >&2
  exit 1
fi

PORT="${1:-8080}"
echo "启动 IM 服务端: 0.0.0.0:${PORT}"
exec java -jar "$JAR" --port "$PORT" --data "${DATA_DIR:-data}"

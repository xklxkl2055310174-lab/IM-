#!/usr/bin/env bash
# 启动 IM 命令行客户端
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/im-client/target/im-client.jar"

if [[ ! -f "$JAR" ]]; then
  echo "未找到 $JAR，请先执行: mvn clean package" >&2
  exit 1
fi

HOST="${1:-127.0.0.1}"
PORT="${2:-8080}"
USER_NAME="${3:-alice}"
echo "连接 IM 服务端: ${HOST}:${PORT}，用户: ${USER_NAME}"
exec java -jar "$JAR" --host "$HOST" --port "$PORT" --user "$USER_NAME"

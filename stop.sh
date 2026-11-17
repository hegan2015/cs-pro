#!/bin/bash
# 停止 cs-gateway：SIGTERM + 15s 等待 + SIGKILL 兜底。数据保留在 data/。
cd "$(dirname "$0")"
f="run/cs-gateway.pid"
if [ ! -f "$f" ]; then
  echo "==> 无 pid 文件，未在运行"
  exit 0
fi
pid=$(cat "$f")
rm -f "$f"
if ! kill -0 "$pid" 2>/dev/null; then
  echo "==> 进程 $pid 已不存在"
  exit 0
fi
kill "$pid" 2>/dev/null
echo "==> 停止 cs-gateway (pid $pid)"
for ((i=0; i<15; i++)); do
  kill -0 "$pid" 2>/dev/null || break
  sleep 1
done
if kill -0 "$pid" 2>/dev/null; then
  kill -9 "$pid" 2>/dev/null
  echo "==> 强制结束残留进程 (pid $pid)"
fi
echo "==> 已停止。数据保留在 data/ 目录，重新启动 run.sh 可恢复。"
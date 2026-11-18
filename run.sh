#!/bin/bash
# cs-pro 一键编译并启动（单进程：REST + SSE + H5，端口 8096）。
# 零外部依赖：ride-pro/payment-pro 未启动时自动降级（FAQ 模式 / 禁用对应查询）。
set -e
cd "$(dirname "$0")"

if [ -d "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home" ]; then
  export JAVA_HOME="/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"
else
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
echo "JAVA_HOME=$JAVA_HOME"

mkdir -p run logs data config

PORT="${CS_PORT:-8096}"
PIDF="run/cs-gateway-$PORT.pid"
LOGF="logs/cs-gateway-$PORT.log"
PROFILES=()
[ "${CS_DB_MODE:-h2}" = "mysql" ] && PROFILES+=(mysql)
[ "${CS_MQ_ENABLED:-false}" = "true" ] && PROFILES+=(mq)
PROFILE_ARGS=()
if [ ${#PROFILES[@]} -gt 0 ]; then
  PROFILE_ARGS+=(--spring.profiles.active="$(IFS=,; echo "${PROFILES[*]}")")
  echo "==> 启动 profile: ${PROFILES[*]}"
fi

if [ -f "$PIDF" ] && kill -0 "$(cat "$PIDF")" 2>/dev/null; then
  echo "==> cs-gateway:$PORT 已在运行 (pid $(cat "$PIDF"))，如需重启请先 CS_PORT=$PORT ./stop.sh"
  exit 0
fi

if [ "$1" != "--no-build" ]; then
  echo "==> 编译打包（Maven Wrapper）..."
  ./mvnw package -DskipTests -q
fi

nohup "$JAVA_HOME/bin/java" -jar cs-gateway/target/cs-gateway.jar "${PROFILE_ARGS[@]}" > "$LOGF" 2>&1 &
pid=$!
echo "$pid" > "$PIDF"
echo "==> 启动 cs-gateway:$PORT (pid $pid)"

for ((i=0; i<90; i++)); do
  if curl -sf -o /dev/null --max-time 2 "http://127.0.0.1:$PORT/api/health"; then
    echo "==> cs-gateway 就绪"
    echo "==> 首页:     http://127.0.0.1:$PORT/"
    echo "==> 咨询:     http://127.0.0.1:$PORT/chat.html"
    echo "==> 订阅:     http://127.0.0.1:$PORT/subscription.html"
    echo "==> 工作台:   http://127.0.0.1:$PORT/agent.html"
    echo "==> 管理后台: http://127.0.0.1:$PORT/admin.html（令牌默认 cs-admin）"
    echo "==> 健康状态: http://127.0.0.1:$PORT/health.html"
    if [ ! -f config/llm.properties ] && [ ! -f "$HOME/.config/cs-pro/llm.properties" ]; then
      echo "==> 提示: 未配置 LLM Key，当前为 FAQ 知识库模式（~/.config/cs-pro/llm.properties 可启用智能助手）"
    fi
    exit 0
  fi
  sleep 1
done
echo "!! cs-gateway 在 90s 内未就绪，查看 logs/cs-gateway.log"
exit 1
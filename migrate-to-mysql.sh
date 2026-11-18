#!/bin/bash
# H2 → MySQL 迁移：需先 ./stop.sh（H2 文件独占锁），全程只读打开 H2，绝不改原库。
set -e
cd "$(dirname "$0")"

# FORCE=1：源库为显式传入的 H2 副本（非 ./data/cs）时跳过运行检查
if [ "${FORCE:-0}" != "1" ]; then
  for f in run/cs-gateway-*.pid run/cs-gateway.pid; do
    if [ -f "$f" ] && kill -0 "$(cat "$f")" 2>/dev/null; then
      echo "!! cs-gateway 还在运行（$f），请先 ./stop.sh（或 FORCE=1 且源为副本）"
      exit 1
    fi
  done
fi

if [ -d "/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home" ]; then
  export JAVA_HOME="/Users/hegan/Library/Java/JavaVirtualMachines/corretto-18.0.2/Contents/Home"
else
  export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi

H2JAR="$(ls "$HOME/.m2/repository/com/h2database/h2/"*"/h2-"*".jar" 2>/dev/null | grep -v sources | tail -1)"
MYJAR="$(ls "$HOME/.m2/repository/com/mysql/mysql-connector-j/"*"/mysql-connector-j-"*".jar" 2>/dev/null | grep -v sources | tail -1)"
[ -f "$H2JAR" ] && [ -f "$MYJAR" ] || { echo "!! 缺 JDBC 驱动（先 ./mvnw package 一次）"; exit 1; }

mkdir -p tools/out
"$JAVA_HOME/bin/javac" -d tools/out tools/MigrateToMysql.java
echo "==> 迁移 ./data/cs → MySQL cs_pro"
"$JAVA_HOME/bin/java" -cp "tools/out:$H2JAR:$MYJAR" MigrateToMysql \
  "${1:-./data/cs}" \
  "${CS_MYSQL_URL:-jdbc:mysql://127.0.0.1:3306/cs_pro?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}" \
  "${CS_MYSQL_USER:-cspro}" "${CS_MYSQL_PASSWORD:-cspro-dev-2026}"

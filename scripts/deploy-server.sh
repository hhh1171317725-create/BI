#!/usr/bin/env sh
set -eu

PROJECT_DIR="${BI_PROJECT_DIR:-/www/wwwroot/BI}"
SERVICE_NAME="${BI_SERVICE_NAME:-dahanghai-analysis}"
REMOTE_NAME="${BI_GIT_REMOTE:-origin}"
BRANCH_NAME="${BI_GIT_BRANCH:-main}"

resolve_java_home() {
  for candidate in "${JAVA_HOME:-}" /www/server/java/jdk-21.0.2; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] && [ -x "$candidate/bin/javac" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  for compiler in /www/server/java/jdk-21*/bin/javac /usr/lib/jvm/java-21*/bin/javac; do
    if [ -x "$compiler" ]; then
      dirname "$(dirname "$compiler")"
      return 0
    fi
  done

  if command -v javac >/dev/null 2>&1; then
    compiler="$(readlink -f "$(command -v javac)")"
    dirname "$(dirname "$compiler")"
    return 0
  fi

  return 1
}

if ! RESOLVED_JAVA_HOME="$(resolve_java_home)"; then
  printf '%s\n' '未找到完整的 Java 21 JDK。请先在宝塔“网站-Java项目”中安装 JDK 21。' >&2
  exit 1
fi

export JAVA_HOME="$RESOLVED_JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

JAVA_MAJOR="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.specification.version = //p' | head -n 1)"
if [ "$JAVA_MAJOR" != "21" ]; then
  printf '当前 JDK 为 Java %s，项目需要 Java 21：%s\n' "$JAVA_MAJOR" "$JAVA_HOME" >&2
  exit 1
fi

cd "$PROJECT_DIR"
git pull --ff-only "$REMOTE_NAME" "$BRANCH_NAME"
chmod +x mvnw
./mvnw clean package -DskipTests
systemctl restart "$SERVICE_NAME"
systemctl is-active --quiet "$SERVICE_NAME"
printf '部署完成：%s，JAVA_HOME=%s\n' "$(git rev-parse --short HEAD)" "$JAVA_HOME"

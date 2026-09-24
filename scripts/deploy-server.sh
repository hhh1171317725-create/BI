#!/usr/bin/env sh
set -eu

PROJECT_DIR="${BI_PROJECT_DIR:-/www/wwwroot/BI}"
SERVICE_NAME="${BI_SERVICE_NAME:-dahanghai-analysis}"
REMOTE_NAME="${BI_GIT_REMOTE:-origin}"
BRANCH_NAME="${BI_GIT_BRANCH:-main}"
MIN_FREE_MB="${BI_MIN_FREE_MB:-1024}"

prepare_disk_space() {
  case "$PROJECT_DIR" in
    /*) ;;
    *)
      printf '部署目录必须是绝对路径：%s\n' "$PROJECT_DIR" >&2
      exit 1
      ;;
  esac
  if [ ! -d "$PROJECT_DIR" ]; then
    printf '部署目录不存在：%s\n' "$PROJECT_DIR" >&2
    exit 1
  fi
  case "$MIN_FREE_MB" in
    ''|*[!0-9]*)
      printf 'BI_MIN_FREE_MB 必须是正整数：%s\n' "$MIN_FREE_MB" >&2
      exit 1
      ;;
  esac

  available_kb="$(df -Pk "$PROJECT_DIR" | awk 'NR == 2 { print $4 }')"
  required_kb="$((MIN_FREE_MB * 1024))"
  if [ -z "$available_kb" ] || [ "$available_kb" -lt "$required_kb" ]; then
    printf '磁盘空间不足：部署至少需要 %s MB 可用空间。\n' "$MIN_FREE_MB" >&2
    df -h "$PROJECT_DIR" >&2 || true
    printf '%s\n' '请先清理日志、备份或其他大文件，再重新部署。可运行：sh scripts/check-disk-space.sh' >&2
    exit 1
  fi
  printf '磁盘预检通过：可用 %s MB，要求至少 %s MB。\n' "$((available_kb / 1024))" "$MIN_FREE_MB"
  # 空间不足时保留现有 JAR，避免一次失败的部署让服务无法重启。
  rm -rf -- "$PROJECT_DIR/target"
}

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

prepare_disk_space
cd "$PROJECT_DIR"
git pull --ff-only "$REMOTE_NAME" "$BRANCH_NAME"
chmod +x mvnw
./mvnw clean package -DskipTests
systemctl restart "$SERVICE_NAME"
ready=0
attempt=0
HEALTH_URL="${BI_HEALTH_URL:-http://127.0.0.1:8765/api/session}"
while [ "$attempt" -lt 45 ]; do
  if curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1; then
    ready=1
    break
  fi
  if ! systemctl is-active --quiet "$SERVICE_NAME"; then
    break
  fi
  attempt=$((attempt + 1))
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  printf '服务重启后未能通过接口检查：%s\n' "$HEALTH_URL" >&2
  systemctl status "$SERVICE_NAME" --no-pager -l >&2 || true
  exit 1
fi
printf '部署完成：%s，JAVA_HOME=%s\n' "$(git rev-parse --short HEAD)" "$JAVA_HOME"

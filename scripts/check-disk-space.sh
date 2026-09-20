#!/usr/bin/env sh
set -u

PROJECT_DIR="${BI_PROJECT_DIR:-/www/wwwroot/BI}"

printf '%s\n' '=== 文件系统空间 ==='
df -hT "$PROJECT_DIR" /var/lib/mysql /var/log /tmp 2>/dev/null || df -h

printf '\n%s\n' '=== inode 使用量 ==='
df -ih "$PROJECT_DIR" /var/lib/mysql /var/log /tmp 2>/dev/null || df -ih

printf '\n%s\n' '=== 常见目录占用 ==='
for path in "$PROJECT_DIR" /www/backup /var/lib/mysql /var/log /root/.m2 /tmp; do
  if [ -e "$path" ]; then
    du -sxh "$path" 2>/dev/null || true
  fi
done

if command -v journalctl >/dev/null 2>&1; then
  printf '\n%s\n' '=== systemd 日志占用 ==='
  journalctl --disk-usage 2>/dev/null || true
fi

printf '\n%s\n' '=== 部署目录最大的 20 个文件 ==='
if [ -d "$PROJECT_DIR" ]; then
  find "$PROJECT_DIR" -xdev -type f -printf '%s\t%p\n' 2>/dev/null \
    | sort -n \
    | tail -n 20 \
    | awk '{ size=$1; $1=""; printf "%.1f MB%s\n", size/1048576, $0 }'
fi

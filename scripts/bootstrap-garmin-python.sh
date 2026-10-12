#!/usr/bin/env sh
# Download a pinned, verified interpreter only into this project's private runtime.
set -eu
umask 077
fail() { printf '%s\n' "$1" >&2; exit 1; }
[ "$#" -eq 0 ] || fail '独立 Python 安装不接受目录参数。'
[ "$(id -u)" -ne 0 ] || fail '请通过 scripts/install-garmin.sh 安装，以 Java 服务用户运行独立 Python。'
[ "$(uname -s)" = Linux ] || fail '自动安装独立 Python 仅支持 Linux；其他系统请设置 GARMIN_BOOTSTRAP_PYTHON。'

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
project_dir=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
runtime_dir="$project_dir/.runtime"
managed_dir="$runtime_dir/garmin-interpreter"
version=3.12.15
release=20261009
arch=$(uname -m)
case "$arch" in
  x86_64) checksum=ffa8f85f1b56e88b687f08d743d817015524b86a9365fd921243132b80f1a36d ;;
  aarch64) checksum=2e1ba0e7f22e01c534caebf7f09e7820322bc0d055271d3434af671e8400f96f ;;
  *) fail '自动安装仅支持 Linux x86_64/aarch64；请设置 GARMIN_BOOTSTRAP_PYTHON。' ;;
esac
libc=$(getconf GNU_LIBC_VERSION 2>/dev/null || true)
printf '%s\n' "$libc" | awk '$1=="glibc" {split($2,v,"."); if(v[1]>2 || (v[1]==2 && v[2]>=17)) ok=1} END {exit !ok}' || fail '独立 Python 需要 glibc 2.17+；不支持 musl/Alpine。'
prefix="$managed_dir/$version-$release-$arch"
python="$prefix/python/bin/python3.12"
for directory in "$runtime_dir" "$managed_dir" "$prefix"; do
  [ ! -L "$directory" ] || fail '独立 Python 的运行目录不能是符号链接。'
  [ ! -e "$directory" ] || [ -d "$directory" ] || fail '独立 Python 的运行路径不是目录。'
done
python_ok() { "$1" -I -c 'import sys,ssl,venv,ensurepip; sys.exit(0 if sys.version_info[:3] == (3,12,15) else 1)' >/dev/null 2>&1; }
if [ -e "$prefix" ]; then
  [ ! -L "$prefix/python" ] && [ -x "$python" ] && python_ok "$python" || fail '已有独立 Python 不可用；为保留现有环境，脚本未覆盖或删除。'
  printf '%s\n' "$python"
  exit 0
fi
for command_name in curl sha256sum tar mktemp; do
  command -v "$command_name" >/dev/null 2>&1 || fail "缺少 $command_name；请先通过系统包管理器安装该工具。"
done
mkdir -p "$managed_dir"
chmod 0700 "$managed_dir"
available_kb=$(df -Pk "$managed_dir" | awk 'NR==2 {print $4}')
case "$available_kb" in ''|*[!0-9]*) fail '无法确认独立 Python 安装目录的剩余空间。' ;; esac
[ "$available_kb" -ge 524288 ] || fail '安装独立 Python 需要至少 512 MB 可用空间，请先释放磁盘空间。'
lock="$managed_dir/.install-lock"
[ ! -L "$lock" ] && mkdir "$lock" 2>/dev/null || fail '独立 Python 已有安装任务；若确认没有任务运行，请检查 .runtime/garmin-interpreter/.install-lock。'
temporary=''
cleanup() {
  if [ -n "$temporary" ]; then
    case "$temporary" in "$managed_dir"/.install-*) [ ! -L "$temporary" ] && rm -rf -- "$temporary" ;; esac
  fi
  rmdir "$lock" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
temporary=$(mktemp -d "$managed_dir/.install-XXXXXX")
archive="$temporary/python.tar.gz"
url="https://github.com/astral-sh/python-build-standalone/releases/download/$release/cpython-$version%2B$release-$arch-unknown-linux-gnu-install_only_stripped.tar.gz"
printf '正在下载项目独立 Python %s（%s，约 30 MB），不修改系统 Python…\n' "$version" "$arch" >&2
if ! curl --fail --location --show-error --proto '=https' --proto-redir '=https' --connect-timeout 20 --max-time 900 --retry 2 --retry-delay 2 --output "$archive" "$url"; then
  fail 'Python 下载失败；请确认服务器能访问 GitHub Releases，再运行原安装命令重试。'
fi
printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c - >/dev/null 2>&1 || fail 'Python 下载文件 SHA256 校验失败；未执行或安装下载内容。'
tar -xzf "$archive" -C "$temporary" --no-same-owner --no-same-permissions || fail '独立 Python 解压失败，原环境未改动。'
[ ! -L "$temporary/python" ] && [ -x "$temporary/python/bin/python3.12" ] && python_ok "$temporary/python/bin/python3.12" || fail '独立 Python 无法在本机运行或缺少 SSL/venv/ensurepip，原环境未改动。'
rm -f -- "$archive"
# Publish the base interpreter before creating a venv; venv paths must never be moved.
[ ! -e "$prefix" ] && [ ! -L "$prefix" ] || fail '独立 Python 目标已存在，未覆盖。'
mv -- "$temporary" "$prefix"
temporary=''
python_ok "$python" || fail '独立 Python 安装后的本地检查失败，请检查 .runtime/garmin-interpreter。'
printf '%s\n' "$python"

#!/usr/bin/env sh
# Install only the repository-local Garmin Python runtime; never log in to Garmin.
set -eu
umask 077

fail() { printf '%s\n' "$1" >&2; exit 1; }
[ "$#" -eq 0 ] || fail '此脚本不接受目录参数；虚拟环境固定在本仓库 .runtime/garmin-python。'
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
project_dir=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
runtime_dir="$project_dir/.runtime"
venv_dir="$runtime_dir/garmin-python"
requirements="$project_dir/scripts/garmin/requirements.txt"
[ -f "$requirements" ] || fail '缺少 scripts/garmin/requirements.txt，请先更新完整仓库。'
[ ! -L "$runtime_dir" ] && [ ! -L "$venv_dir" ] || fail '拒绝使用符号链接的 .runtime 或 garmin-python 目录。'
[ ! -e "$runtime_dir" ] || [ -d "$runtime_dir" ] || fail '.runtime 不是目录。'
[ ! -e "$venv_dir" ] || [ -d "$venv_dir" ] || fail 'garmin-python 不是目录。'

# The supplied Java systemd unit runs as www. Do not leave a root-owned private venv.
if [ "$(id -u)" -eq 0 ]; then
  id www >/dev/null 2>&1 || fail '未找到 www 用户；请用实际 Java 服务运行用户执行安装。'
  command -v runuser >/dev/null 2>&1 || fail '缺少 runuser；请切换为实际 Java 服务用户后执行安装。'
  www_group=$(id -gn www)
  [ -d "$runtime_dir" ] || install -d -m 0750 -o www -g "$www_group" "$runtime_dir"
  [ -d "$venv_dir" ] || install -d -m 0700 -o www -g "$www_group" "$venv_dir"
  [ "$(stat -c %u "$venv_dir")" = "$(id -u www)" ] || fail '已有 garmin-python 不属于 www；请仅修复该虚拟环境的所有权后重试，勿修改整个 .runtime。'
  exec runuser -u www -- sh "$script_dir/install-garmin.sh"
fi

python_ok() { "$1" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 12) else 1)' >/dev/null 2>&1; }
if [ -f "$venv_dir/pyvenv.cfg" ]; then
  [ -x "$venv_dir/bin/python" ] && python_ok "$venv_dir/bin/python" || fail '已有虚拟环境不可用或 Python 低于 3.12；脚本不会删除或替换它。'
else
  if [ -d "$venv_dir" ] && [ -n "$(find "$venv_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
    fail 'garmin-python 目录已有非虚拟环境内容；脚本不会覆盖它。'
  fi
  bootstrap_python=''
  if [ -n "${GARMIN_BOOTSTRAP_PYTHON:-}" ]; then
    command -v "$GARMIN_BOOTSTRAP_PYTHON" >/dev/null 2>&1 && python_ok "$GARMIN_BOOTSTRAP_PYTHON" || fail 'GARMIN_BOOTSTRAP_PYTHON 必须指向 Python 3.12 或更新版本。'
    bootstrap_python="$GARMIN_BOOTSTRAP_PYTHON"
  else
    for candidate in python3.12 python3; do
      if command -v "$candidate" >/dev/null 2>&1 && python_ok "$candidate"; then bootstrap_python="$candidate"; break; fi
    done
  fi
  [ -n "$bootstrap_python" ] || fail '需要 Python 3.12+ 及其 venv 模块；先通过操作系统安装，再重试。'
  mkdir -p "$runtime_dir" "$venv_dir"
  if ! "$bootstrap_python" -m venv "$venv_dir" >/dev/null 2>&1; then
    fail '创建虚拟环境失败；请确认对应 Python 的 venv/ensurepip 包已安装。已有文件未删除。'
  fi
fi

chmod 0700 "$venv_dir"
"$venv_dir/bin/python" -c 'import pathlib,sys,sysconfig; root=pathlib.Path(sys.argv[1]).resolve(); paths=[pathlib.Path(sysconfig.get_path(key)).resolve() for key in ("purelib","platlib")]; sys.exit(0 if sys.prefix != sys.base_prefix and pathlib.Path(sys.prefix).resolve() == root and all(path.is_relative_to(root) for path in paths) else 1)' "$venv_dir" >/dev/null 2>&1 || fail '解释器或依赖目录未指向指定虚拟环境，拒绝安装。'
for private_path in "$venv_dir/.pip-cache" "$venv_dir/.tmp" "$venv_dir/install.log"; do
  [ ! -L "$private_path" ] || fail '安装缓存、临时目录或日志不可是符号链接。'
done
mkdir -p "$venv_dir/.pip-cache" "$venv_dir/.tmp"
install_log="$venv_dir/install.log"
if ! TMPDIR="$venv_dir/.tmp" "$venv_dir/bin/python" -m pip --isolated --disable-pip-version-check install --no-input --index-url https://pypi.org/simple --cache-dir "$venv_dir/.pip-cache" -r "$requirements" >"$install_log" 2>&1; then
  chmod 0600 "$install_log"
  fail '依赖安装失败；请在服务器私有终端检查 .runtime/garmin-python/install.log。错误日志不会自动输出。'
fi
chmod 0600 "$install_log"
"$venv_dir/bin/python" -c 'from importlib.metadata import version; import sys; sys.exit(0 if version("garminconnect") == "0.3.17" else 1)' >/dev/null 2>&1 || fail 'garminconnect 版本核验失败，请核对固定版本 requirements.txt。'
"$venv_dir/bin/python" "$project_dir/scripts/garmin_worker.py" --check >/dev/null 2>&1 || fail '本地佳明 worker 检查失败；请确认 Python 版本、依赖及完整仓库均已就绪。'
printf '佳明 Python 环境已安装：%s/bin/python\n' "$venv_dir"
printf '%s\n' '仅安装本地依赖；未登录佳明、未读取健康数据、未修改 Java 服务或数据库。'

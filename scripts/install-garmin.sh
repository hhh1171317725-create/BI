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
managed_dir="$runtime_dir/garmin-interpreter"
requirements="$project_dir/scripts/garmin/requirements.txt"
[ -f "$requirements" ] || fail '缺少 scripts/garmin/requirements.txt，请先更新完整仓库。'
[ ! -L "$runtime_dir" ] && [ ! -L "$venv_dir" ] && [ ! -L "$managed_dir" ] || fail '拒绝使用符号链接的佳明运行目录。'
[ ! -e "$runtime_dir" ] || [ -d "$runtime_dir" ] || fail '.runtime 不是目录。'
[ ! -e "$venv_dir" ] || [ -d "$venv_dir" ] || fail 'garmin-python 不是目录。'
[ ! -e "$managed_dir" ] || [ -d "$managed_dir" ] || fail 'garmin-interpreter 不是目录。'

# The supplied Java systemd unit runs as www. Do not leave a root-owned private venv.
if [ "$(id -u)" -eq 0 ]; then
  id www >/dev/null 2>&1 || fail '未找到 www 用户；请用实际 Java 服务运行用户执行安装。'
  command -v runuser >/dev/null 2>&1 || fail '缺少 runuser；请切换为实际 Java 服务用户后执行安装。'
  www_group=$(id -gn www)
  [ -d "$runtime_dir" ] || install -d -m 0750 -o www -g "$www_group" "$runtime_dir"
  [ -d "$venv_dir" ] || install -d -m 0700 -o www -g "$www_group" "$venv_dir"
  [ -d "$managed_dir" ] || install -d -m 0700 -o www -g "$www_group" "$managed_dir"
  [ "$(stat -c %u "$venv_dir")" = "$(id -u www)" ] || fail '已有 garmin-python 不属于 www；请仅修复该虚拟环境的所有权后重试，勿修改整个 .runtime。'
  [ "$(stat -c %u "$managed_dir")" = "$(id -u www)" ] || fail '已有 garmin-interpreter 不属于 www；请仅修复该目录所有权后重试。'
  exec runuser -u www -- sh "$script_dir/install-garmin.sh"
fi

mkdir -p "$managed_dir"
chmod 0700 "$managed_dir"
install_lock="$managed_dir/.venv-install-lock"
[ ! -L "$install_lock" ] && mkdir "$install_lock" 2>/dev/null || fail '已有佳明环境安装任务；请等待完成后重试。若上次被强制终止，请先确认无安装进程再移除 .runtime/garmin-interpreter/.venv-install-lock 空目录。'
trap 'rmdir "$install_lock" 2>/dev/null || true' EXIT
trap 'exit 1' HUP INT TERM

python_ok() { "$1" -I -c 'import sys,ssl,venv,ensurepip; sys.exit(0 if sys.version_info >= (3, 12) else 1)' >/dev/null 2>&1; }
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
    for candidate in python3.12 python3.13 python3.14 python3; do
      if command -v "$candidate" >/dev/null 2>&1 && python_ok "$candidate"; then bootstrap_python="$candidate"; break; fi
    done
  fi
  if [ -z "$bootstrap_python" ]; then
    [ -f "$script_dir/bootstrap-garmin-python.sh" ] || fail '缺少独立 Python 安装脚本，请先拉取最新完整仓库。'
    bootstrap_python=$(sh "$script_dir/bootstrap-garmin-python.sh") || fail '独立 Python 未安装完成，请按上方原因处理后重试。'
  fi
  mkdir -p "$runtime_dir" "$venv_dir"
  if ! "$bootstrap_python" -I -m venv "$venv_dir" >/dev/null 2>&1; then
    fail '创建虚拟环境失败；请检查磁盘空间与 Python 的 venv/ensurepip。已有文件未删除，处理后可重跑以恢复 pip。'
  fi
fi

chmod 0700 "$venv_dir"
"$venv_dir/bin/python" -c 'import pathlib,sys,sysconfig; root=pathlib.Path(sys.argv[1]).resolve(); paths=[pathlib.Path(sysconfig.get_path(key)).resolve() for key in ("purelib","platlib")]; sys.exit(0 if sys.prefix != sys.base_prefix and pathlib.Path(sys.prefix).resolve() == root and all(path.is_relative_to(root) for path in paths) else 1)' "$venv_dir" >/dev/null 2>&1 || fail '解释器或依赖目录未指向指定虚拟环境，拒绝安装。'
for private_path in "$venv_dir/.pip-cache" "$venv_dir/.tmp" "$venv_dir/install.log"; do
  [ ! -L "$private_path" ] || fail '安装缓存、临时目录或日志不可是符号链接。'
done
mkdir -p "$venv_dir/.pip-cache" "$venv_dir/.tmp"
install_log="$venv_dir/install.log"
if ! "$venv_dir/bin/python" -I -m pip --version >/dev/null 2>&1; then
  printf '%s\n' '正在恢复佳明虚拟环境的 pip…'
  if ! TMPDIR="$venv_dir/.tmp" "$venv_dir/bin/python" -I -m ensurepip --upgrade >"$install_log" 2>&1; then
    chmod 0600 "$install_log"
    fail '虚拟环境 pip 恢复失败；请检查磁盘空间及私有 install.log，已有环境未删除。'
  fi
fi
if ! TMPDIR="$venv_dir/.tmp" "$venv_dir/bin/python" -I -m pip --isolated --disable-pip-version-check install --no-input --index-url https://pypi.org/simple --cache-dir "$venv_dir/.pip-cache" -r "$requirements" >"$install_log" 2>&1; then
  chmod 0600 "$install_log"
  fail '依赖安装失败；请在服务器私有终端检查 .runtime/garmin-python/install.log。错误日志不会自动输出。'
fi
chmod 0600 "$install_log"
"$venv_dir/bin/python" -c 'from importlib.metadata import version; import sys; sys.exit(0 if version("garminconnect") == "0.3.17" else 1)' >/dev/null 2>&1 || fail 'garminconnect 版本核验失败，请核对固定版本 requirements.txt。'
"$venv_dir/bin/python" "$project_dir/scripts/garmin_worker.py" --check >/dev/null 2>&1 || fail '本地佳明 worker 检查失败；请确认 Python 版本、依赖及完整仓库均已就绪。'
printf '佳明 Python 环境已安装：%s/bin/python\n' "$venv_dir"
printf '%s\n' '仅安装本地依赖；未登录佳明、未读取健康数据、未修改 Java 服务或数据库。'

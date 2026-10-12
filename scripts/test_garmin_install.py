#!/usr/bin/env python3
"""Offline shell regression tests; fixtures do not execute a Linux interpreter.

Run with Python 3.12+ on Windows (Git Bash) or Linux. Download, checksum,
extraction and Python are deliberately replaced, so this checks installer
control flow and filesystem safety, not archive integrity or ABI compatibility.
"""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[1]
BASH = Path("C:/Program Files/Git/bin/bash.exe") if os.name == "nt" else Path("/bin/bash")
HASHES = {
    "x86_64": "ffa8f85f1b56e88b687f08d743d817015524b86a9365fd921243132b80f1a36d",
    "aarch64": "2e1ba0e7f22e01c534caebf7f09e7820322bc0d055271d3434af671e8400f96f",
}


def shell_path(path: Path) -> str:
    value = path.resolve().as_posix()
    return f"/{value[0].lower()}{value[2:]}" if os.name == "nt" else value


COMMON = """#!/bin/sh
set -eu
record() {
  printf '%s' "$1" >> "$FAKE_TRACE"
  shift
  for value do printf '\\t%s' "$value" >> "$FAKE_TRACE"; done
  printf '\\n' >> "$FAKE_TRACE"
}
"""

PYTHON = COMMON + r"""
record python "$0" "$@"
if [ "${1:-}" = -I ]; then shift; fi
case "${1:-}" in
  -c)
    case "${2:-}" in
      *sys.version_info*) [ "${FAKE_BAD_IMPORT:-0}" != 1 ] ;;
      *sysconfig.get_path*) [ -f "$FAKE_ROOT/.runtime/garmin-python/pyvenv.cfg" ] ;;
      *'version("garminconnect")'*) exit 0 ;;
      *) record unexpected "$@"; exit 90 ;;
    esac ;;
  -m)
    case "${2:-}" in
      venv)
        target=$3
        # An absolute home captured here must point to the published interpreter.
        expected="$FAKE_ROOT/.runtime/garmin-interpreter/3.12.15-20261009-$FAKE_ARCH/python/bin/python3.12"
        [ "$0" = "$expected" ] && [ -x "$expected" ] || exit 91
        record venv "$0" "$target"
        mkdir -p "$target/bin"
        cp "$0" "$target/bin/python"
        chmod 700 "$target/bin/python"
        printf 'home = %s\n' "${expected%/bin/python3.12}" > "$target/pyvenv.cfg" ;;
      pip)
        if [ "${3:-}" = --version ]; then
          record pip-version
          [ "${FAKE_MISSING_PIP:-0}" != 1 ] || [ -f "$FAKE_ROOT/pip-restored" ]
        else
          record pip-install "$@"
          if [ "${FAKE_SLOW_PIP:-0}" = 1 ]; then
            printf 'private fixture dependency output\n'
            /bin/sleep 6
          fi
          [ "${FAKE_PIP_FAIL:-0}" != 1 ]
        fi ;;
      ensurepip)
        record ensurepip "$@"
        [ "${FAKE_ENSUREPIP_FAIL:-0}" != 1 ] || exit 92
        : > "$FAKE_ROOT/pip-restored" ;;
      *) record unexpected "$@"; exit 93 ;;
    esac ;;
  */garmin_worker.py)
    [ "${2:-}" = --check ] || exit 94
    record worker-check ;;
  *) record unexpected "$@"; exit 95 ;;
esac
"""

STUBS = {
    "id": 'case "$1" in -u) printf "1001\\n" ;; *) exit 96 ;; esac\n',
    "uname": 'case "$1" in -s) printf "Linux\\n" ;; -m) printf "%s\\n" "$FAKE_ARCH" ;; *) exit 96 ;; esac\n',
    "getconf": 'printf "%s\\n" "${FAKE_LIBC:-glibc 2.32}"\n',
    "df": 'printf "Filesystem 1024-blocks Used Available Capacity Mounted\\nfixture 2097152 0 %s 0%% /\\n" "${FAKE_FREE_KB:-1048576}"\n',
    "sleep": 'case "$1" in 2) record retry-delay "$@" ;; 1) record install-poll ;; *) exit 97 ;; esac\n',
    "timeout": r"""
record timeout "$@"
[ "$1" = --kill-after=10s ] && [ "$2" = 1800 ] || exit 97
[ "${FAKE_DEPENDENCY_TIMEOUT:-0}" != 1 ] || exit 124
[ "${FAKE_DEPENDENCY_KILLED:-0}" != 1 ] || exit 137
shift 2
exec "$@"
""",
    "curl": r"""
record curl "$@"
output=''
url=''
resume=''
while [ "$#" -gt 0 ]; do
  case "$1" in --output) output=$2; shift ;; --continue-at) resume=$2; shift ;; https://*) url=$1 ;; esac
  shift
done
[ -n "$output" ] && [ -n "$url" ] || exit 97
[ "${FAKE_DOWNLOAD_FAIL:-0}" != 1 ] || exit 22
if [ "${FAKE_RESUME_UNSUPPORTED:-0}" = 1 ] && [ -s "$output" ]; then exit 33; fi
if [ "${FAKE_TIMEOUT_ONCE:-0}" = 1 ] && [ ! -f "$FAKE_ROOT/timeout-seen" ]; then
  printf 'partial archive fixture\n' > "$output"
  : > "$FAKE_ROOT/timeout-seen"
  exit 28
fi
if [ "${FAKE_ALWAYS_TIMEOUT:-0}" = 1 ]; then
  printf 'partial archive fixture\n' > "$output"
  exit 28
fi
if [ -s "$output" ]; then
  [ "$resume" = - ] || exit 98
  [ "$(cat "$output")" = 'partial archive fixture' ] || exit 99
  record resumed "$output"
fi
printf 'offline archive fixture\n' > "$output"
if [ "${FAKE_TIMEOUT_AFTER_COMPLETE:-0}" = 1 ]; then exit 28; fi
""",
    "sha256sum": r"""
[ "$1" = -c ] && [ "$2" = - ] || exit 97
IFS=' ' read -r checksum archive
record sha256 "$checksum" "$archive"
case "$FAKE_ARCH" in
  x86_64) expected=ffa8f85f1b56e88b687f08d743d817015524b86a9365fd921243132b80f1a36d ;;
  aarch64) expected=2e1ba0e7f22e01c534caebf7f09e7820322bc0d055271d3434af671e8400f96f ;;
  *) exit 97 ;;
esac
[ "$checksum" = "$expected" ] && [ -s "$archive" ] || exit 97
[ "$(cat "$archive")" = 'offline archive fixture' ] || exit 1
[ "${FAKE_HASH_BAD:-0}" != 1 ]
""",
    "tar": r"""
record tar "$@"
destination=''
while [ "$#" -gt 0 ]; do
  case "$1" in -C) destination=$2; shift ;; esac
  shift
done
[ -n "$destination" ] || exit 97
[ "${FAKE_TAR_FAIL:-0}" != 1 ] || exit 2
mkdir -p "$destination/python/bin"
cp "$FAKE_ROOT/fake-python" "$destination/python/bin/python3.12"
chmod 700 "$destination/python/bin/python3.12"
""",
}


class GarminInstallTest(unittest.TestCase):
    def setUp(self) -> None:
        self.assertTrue(BASH.is_file(), f"Bash not found: {BASH}")
        parent = REPO / ".runtime" / "garmin-install-tests"
        parent.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="fixture-", dir=parent)
        self.root = Path(self.temporary.name)
        self.assertTrue(self.root.resolve().is_relative_to(parent.resolve()))
        self.addCleanup(self.temporary.cleanup)
        (self.root / "scripts" / "garmin").mkdir(parents=True)
        for name in ("install-garmin.sh", "bootstrap-garmin-python.sh"):
            shutil.copyfile(REPO / "scripts" / name, self.root / "scripts" / name)
        shutil.copyfile(REPO / "scripts" / "garmin" / "requirements.txt",
                        self.root / "scripts" / "garmin" / "requirements.txt")
        (self.root / "scripts" / "garmin_worker.py").write_text("# Offline placeholder\n", encoding="utf-8")
        self.trace = self.root / "trace.tsv"
        self.trace.touch()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.write_executable(self.root / "fake-python", PYTHON)
        for name, body in STUBS.items():
            self.write_executable(self.bin / name, COMMON + body)
        # Shadow all supported candidates, including any host Python in /usr/bin.
        for name in ("python3.12", "python3.13", "python3.14", "python3"):
            self.write_executable(self.bin / name, COMMON + 'record candidate "$0" "$@"\nexit 1\n')
        self.env = {k: v for k, v in os.environ.items()
                    if k.lower() != "path" and not k.startswith("GARMIN_")}
        self.env.update(PATH=f"{shell_path(self.bin)}:/usr/bin:/bin", FAKE_PATH=f"{shell_path(self.bin)}:/usr/bin:/bin", FAKE_ROOT=shell_path(self.root),
                        FAKE_TRACE=shell_path(self.trace), FAKE_ARCH="x86_64")
        self.runtime = self.root / ".runtime"
        self.runtime.mkdir()
        self.sentinel = self.runtime / "unrelated-old-data.txt"
        self.sentinel.write_text("existing data must survive\n", encoding="utf-8")

    @staticmethod
    def write_executable(path: Path, contents: str) -> None:
        with path.open("w", encoding="utf-8", newline="\n") as stream:
            stream.write(contents)
        path.chmod(0o700)

    def run_install(self, **settings: str) -> subprocess.CompletedProcess[str]:
        # Git Bash prepends its own tools to inherited PATH; override after startup
        # so fixtures also shadow uname/curl and never contact the real network.
        return subprocess.run([str(BASH), "--noprofile", "--norc", "-c",
                               'export PATH="$FAKE_PATH"; exec /bin/sh "$1"', "fixture-install",
                               shell_path(self.root / "scripts" / "install-garmin.sh")],
                              cwd=self.root, env=self.env | settings, text=True, encoding="utf-8",
                              errors="replace", capture_output=True, timeout=30)

    def events(self, name: str) -> list[list[str]]:
        return [fields[1:] for line in self.trace.read_text(encoding="utf-8").splitlines()
                if (fields := line.split("\t"))[0] == name]

    def remove_fixture_venv(self) -> None:
        target = self.runtime / "garmin-python"
        self.assertTrue(target.resolve().is_relative_to(self.root.resolve()))
        if target.exists():
            shutil.rmtree(target)

    def base(self, arch: str = "x86_64") -> Path:
        return self.runtime / "garmin-interpreter" / f"3.12.15-20261009-{arch}" / "python" / "bin" / "python3.12"

    def assert_success(self, result: subprocess.CompletedProcess[str]) -> None:
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr + self.trace.read_text(encoding="utf-8"))
        self.assertEqual(self.events("unexpected"), [])
        self.assertEqual(self.sentinel.read_text(encoding="utf-8"), "existing data must survive\n")
        self.assertFalse((self.runtime / "garmin-interpreter" / ".venv-install-lock").exists())

    def assert_failed_without_venv(self, result: subprocess.CompletedProcess[str]) -> None:
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.events("venv"), [])
        self.assertEqual(self.events("pip-install"), [])
        self.assertFalse((self.runtime / "garmin-python" / "pyvenv.cfg").exists())
        self.assertFalse(self.base().exists())
        self.assertEqual(self.sentinel.read_text(encoding="utf-8"), "existing data must survive\n")
        managed = self.runtime / "garmin-interpreter"
        self.assertFalse((managed / ".venv-install-lock").exists())
        self.assertFalse((managed / ".install-lock").exists())
        self.assertEqual(list(managed.glob(".install-*")), [])

    def test_fallback_arch_hash_and_published_base(self) -> None:
        for arch, checksum in HASHES.items():
            with self.subTest(arch=arch):
                self.trace.write_text("", encoding="utf-8")
                self.remove_fixture_venv()
                result = self.run_install(FAKE_ARCH=arch)
                self.assert_success(result)
                curl = self.events("curl")
                self.assertEqual(len(curl), 1)
                self.assertIn("--fail", curl[0])
                self.assertEqual(curl[0][curl[0].index("--proto") + 1], "=https")
                self.assertEqual(curl[0][curl[0].index("--proto-redir") + 1], "=https")
                self.assertEqual(curl[0][curl[0].index("--continue-at") + 1], "-")
                self.assertEqual(curl[0][curl[0].index("--max-time") + 1], "3600")
                self.assertNotIn("--retry", curl[0])
                self.assertEqual(curl[0][-1], "https://github.com/astral-sh/python-build-standalone/releases/download/20261009/"
                                 f"cpython-3.12.15%2B20261009-{arch}-unknown-linux-gnu-install_only_stripped.tar.gz")
                self.assertEqual(self.events("sha256")[0][0], checksum)
                self.assertEqual(self.events("venv"), [[shell_path(self.base(arch)), shell_path(self.runtime / "garmin-python")]])
                cfg = (self.runtime / "garmin-python" / "pyvenv.cfg").read_text(encoding="utf-8")
                self.assertIn(shell_path(self.base(arch).parents[1]), cfg)
                self.assertNotIn(".install-", cfg)
                self.assertEqual(len(self.events("pip-install")), 1)
                pip = self.events("pip-install")[0]
                self.assertEqual(pip[pip.index("--timeout") + 1], "30")
                self.assertEqual(pip[pip.index("--retries") + 1], "2")
                self.assertIn('SHA256 校验通过', result.stderr)
                self.assertIn('正在安装佳明依赖', result.stderr)
                self.assertIn('依赖安装完成', result.stderr)
                self.assertEqual(self.events("worker-check"), [[]])

    def test_cached_base_reused_without_download(self) -> None:
        self.assert_success(self.run_install())
        old_base = self.base().read_bytes()
        self.remove_fixture_venv()
        self.trace.write_text("", encoding="utf-8")
        self.assert_success(self.run_install(FAKE_DOWNLOAD_FAIL="1"))
        self.assertEqual(self.events("curl") + self.events("sha256") + self.events("tar"), [])
        self.assertEqual(self.base().read_bytes(), old_base)
        self.assertEqual(len(self.events("venv")), 1)

    def test_download_failure_preserves_old_files(self) -> None:
        self.assert_failed_without_venv(self.run_install(FAKE_DOWNLOAD_FAIL="1"))
        self.assertEqual(len(self.events("curl")), 1)
        self.assertEqual(self.events("sha256") + self.events("tar"), [])

    def test_timeout_retries_using_saved_partial_bytes(self) -> None:
        self.assert_success(self.run_install(FAKE_TIMEOUT_ONCE="1"))
        self.assertEqual(len(self.events("curl")), 2)
        self.assertEqual(len(self.events("resumed")), 1)
        self.assertEqual(len(self.events("tar")), 1)
        self.assertEqual(list((self.runtime / "garmin-interpreter").glob("*.part")), [])

    def test_exhausted_timeout_preserves_bytes_for_next_run(self) -> None:
        self.assert_failed_without_venv(self.run_install(FAKE_ALWAYS_TIMEOUT="1"))
        self.assertEqual(len(self.events("curl")), 3)
        archive = self.runtime / "garmin-interpreter" / ".python-3.12.15-20261009-x86_64.tar.gz.part"
        self.assertEqual(archive.read_text(encoding="utf-8"), "partial archive fixture\n")
        self.trace.write_text("", encoding="utf-8")
        self.assert_success(self.run_install())
        self.assertEqual(len(self.events("resumed")), 1)
        self.assertFalse(archive.exists())

    def test_complete_download_accepted_even_if_curl_times_out(self) -> None:
        self.assert_success(self.run_install(FAKE_TIMEOUT_AFTER_COMPLETE="1"))
        self.assertEqual(len(self.events("curl")), 1)
        self.assertEqual(self.events("retry-delay"), [])

    def test_range_refused_retries_with_fresh_archive(self) -> None:
        managed = self.runtime / "garmin-interpreter"
        managed.mkdir()
        archive = managed / ".python-3.12.15-20261009-x86_64.tar.gz.part"
        archive.write_text("partial archive fixture\n", encoding="utf-8")
        self.assert_success(self.run_install(FAKE_RESUME_UNSUPPORTED="1"))
        self.assertEqual(len(self.events("curl")), 2)
        self.assertEqual(self.events("resumed"), [])
        self.assertFalse(archive.exists())

    def test_verified_archive_reused_after_extraction_failure(self) -> None:
        self.assert_failed_without_venv(self.run_install(FAKE_TAR_FAIL="1"))
        self.trace.write_text("", encoding="utf-8")
        self.assert_success(self.run_install(FAKE_DOWNLOAD_FAIL="1"))
        self.assertEqual(self.events("curl"), [])

    def test_concurrent_install_is_refused_before_mutating_venv(self) -> None:
        self.assert_success(self.run_install())
        old_cfg = (self.runtime / "garmin-python" / "pyvenv.cfg").read_bytes()
        lock = self.runtime / "garmin-interpreter" / ".venv-install-lock"
        lock.mkdir()
        self.trace.write_text("", encoding="utf-8")
        result = self.run_install()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.events("python") + self.events("curl") + self.events("venv") + self.events("pip-install"), [])
        self.assertTrue(lock.is_dir())
        self.assertEqual((self.runtime / "garmin-python" / "pyvenv.cfg").read_bytes(), old_cfg)

    def test_hash_mismatch_never_extracts_or_executes_payload(self) -> None:
        self.assert_failed_without_venv(self.run_install(FAKE_HASH_BAD="1"))
        self.assertEqual(len(self.events("sha256")), 1)
        self.assertEqual(self.events("tar") + self.events("python"), [])
        self.assertFalse((self.runtime / "garmin-interpreter" / ".python-3.12.15-20261009-x86_64.tar.gz.part").exists())

    def test_unsupported_arch_and_libc_stop_before_download(self) -> None:
        for settings in ({"FAKE_ARCH": "mips64"}, {"FAKE_LIBC": "glibc 2.16"}, {"FAKE_LIBC": "musl 1.2"}):
            with self.subTest(settings=settings):
                self.trace.write_text("", encoding="utf-8")
                self.assert_failed_without_venv(self.run_install(**settings))
                self.assertEqual(self.events("curl"), [])

    def test_disk_extraction_and_import_failures_preserve_old_files(self) -> None:
        for settings in ({"FAKE_FREE_KB": "524287"}, {"FAKE_TAR_FAIL": "1"}, {"FAKE_BAD_IMPORT": "1"}):
            with self.subTest(settings=settings):
                self.trace.write_text("", encoding="utf-8")
                self.assert_failed_without_venv(self.run_install(**settings))

    def test_existing_invalid_base_is_not_replaced(self) -> None:
        prefix = self.base().parents[2]
        prefix.mkdir(parents=True)
        old = prefix / "keep.txt"
        old.write_text("old incomplete install", encoding="utf-8")
        self.assert_failed_without_venv(self.run_install())
        self.assertEqual(self.events("curl"), [])
        self.assertEqual(old.read_text(encoding="utf-8"), "old incomplete install")

    def test_existing_venv_recovers_missing_pip_without_download(self) -> None:
        self.assert_success(self.run_install())
        old_cfg = (self.runtime / "garmin-python" / "pyvenv.cfg").read_bytes()
        self.trace.write_text("", encoding="utf-8")
        self.assert_success(self.run_install(FAKE_MISSING_PIP="1"))
        self.assertEqual(self.events("curl") + self.events("venv"), [])
        self.assertEqual(len(self.events("ensurepip")), 1)
        self.assertEqual(len(self.events("pip-install")), 1)
        self.assertEqual((self.runtime / "garmin-python" / "pyvenv.cfg").read_bytes(), old_cfg)

    def test_dependency_timeout_releases_lock_and_allows_retry(self) -> None:
        result = self.run_install(FAKE_DEPENDENCY_TIMEOUT="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('超过 30 分钟', result.stderr)
        self.assertEqual(self.events("pip-install") + self.events("worker-check"), [])
        self.assertTrue((self.runtime / "garmin-python" / "pyvenv.cfg").is_file())
        self.assertFalse((self.runtime / "garmin-interpreter" / ".venv-install-lock").exists())
        self.trace.write_text("", encoding="utf-8")
        self.assert_success(self.run_install())
        self.assertEqual(self.events("curl") + self.events("venv"), [])

    def test_dependency_failure_is_not_reported_as_success(self) -> None:
        result = self.run_install(FAKE_PIP_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('依赖安装失败', result.stderr)
        self.assertNotIn('环境已安装', result.stdout)
        self.assertNotIn('依赖安装完成', result.stderr)
        self.assertEqual(self.events("worker-check"), [])
        self.assertFalse((self.runtime / "garmin-interpreter" / ".venv-install-lock").exists())

    def test_slow_dependency_install_shows_status_without_private_log(self) -> None:
        result = self.run_install(FAKE_SLOW_PIP="1")
        self.assert_success(result)
        self.assertIn('依赖安装仍在运行', result.stderr)
        self.assertNotIn('private fixture dependency output', result.stdout + result.stderr)
        self.assertIn('private fixture dependency output', (self.runtime / "garmin-python" / "install.log").read_text())

    def test_force_killed_dependency_is_not_assumed_to_be_timeout(self) -> None:
        result = self.run_install(FAKE_DEPENDENCY_KILLED="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('被强制终止', result.stderr)
        self.assertNotIn('超过 30 分钟', result.stderr)
        self.assertFalse((self.runtime / "garmin-interpreter" / ".venv-install-lock").exists())

    def test_failed_pip_recovery_preserves_venv_without_pip_install(self) -> None:
        self.assert_success(self.run_install())
        old_cfg = (self.runtime / "garmin-python" / "pyvenv.cfg").read_bytes()
        self.trace.write_text("", encoding="utf-8")
        result = self.run_install(FAKE_MISSING_PIP="1", FAKE_ENSUREPIP_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.events("ensurepip")), 1)
        self.assertEqual(self.events("curl") + self.events("venv") + self.events("pip-install"), [])
        self.assertEqual((self.runtime / "garmin-python" / "pyvenv.cfg").read_bytes(), old_cfg)
        self.assertEqual(self.sentinel.read_text(encoding="utf-8"), "existing data must survive\n")

    def test_symlink_runtime_paths_are_rejected_without_target_changes(self) -> None:
        outside = self.root / "outside"
        outside.mkdir()
        marker = outside / "keep.txt"
        marker.write_text("symlink target untouched", encoding="utf-8")
        for relative in ("garmin-python", "garmin-interpreter", "garmin-interpreter/3.12.15-20261009-x86_64",
                         "garmin-interpreter/.python-3.12.15-20261009-x86_64.tar.gz.part"):
            with self.subTest(path=relative):
                link = self.runtime / relative
                link.parent.mkdir(parents=True, exist_ok=True)
                result = subprocess.run([str(BASH), "--noprofile", "--norc", "-c", 'ln -s "$1" "$2" && test -L "$2"',
                                         "fixture-link", shell_path(outside), shell_path(link)],
                                        env=self.env | {"MSYS": "winsymlinks"}, cwd=self.root,
                                        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10)
                self.assertEqual(result.returncode, 0, "Fixture needs a Bash-recognized symlink: " + result.stderr)
                try:
                    self.trace.write_text("", encoding="utf-8")
                    result = self.run_install()
                    self.assertNotEqual(result.returncode, 0)
                    self.assertEqual(self.events("curl") + self.events("venv") + self.events("pip-install"), [])
                    self.assertEqual(marker.read_text(encoding="utf-8"), "symlink target untouched")
                    self.assertEqual(sorted(path.name for path in outside.iterdir()), ["keep.txt"])
                finally:
                    subprocess.run([str(BASH), "--noprofile", "--norc", "-c", 'rm -- "$1"', "fixture-unlink", shell_path(link)],
                                   env=self.env, cwd=self.root, check=True, capture_output=True, timeout=10)


if __name__ == "__main__":
    unittest.main(verbosity=2)

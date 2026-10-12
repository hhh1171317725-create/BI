# 佳明个人自用接入与部署

这是 Java BI 工具中心内的佳明功能，沿用网站登录和工具权限；不需要 Mac、自研 iOS App，也不需要另行部署 `personal-life`。手机或手表先把记录同步到 Garmin Connect，本功能再由服务器读取。

当前使用非官方 [python-garminconnect](https://github.com/cyberjunky/python-garminconnect)，固定 `garminconnect==0.3.17`，需要 Python 3.12+。它不是 Garmin Health 企业 API，不保证长期兼容；佳明登录、MFA、风控或响应格式变化可能要求重新连接或更新适配。代码发布、安装依赖和运行状态检查都不会自动登录佳明；连接账户与同步由用户明确发起。此功能用于个人数据查看，不作为医学诊断。

## 路径与数据边界

服务器示例仓库为 `/www/wwwroot/BI`，Java systemd 模板运行账号为 `www:www`，工作目录为该仓库。

| 用途 | 默认位置 |
| --- | --- |
| Python 依赖清单 | `scripts/garmin/requirements.txt` |
| Python worker | `scripts/garmin_worker.py` |
| 专用虚拟环境 | `.runtime/garmin-python` |
| 缺少系统 Python 时的独立解释器 | `.runtime/garmin-interpreter/3.12.15-20261009-{架构}/python/` |
| 用户状态 | `.runtime/garmin/{网站用户ID}/state.json` |
| 账户会话与 token | `.runtime/garmin/{网站用户ID}/sessions/{会话UUID}/` |
| 数据缓存 | `.runtime/garmin/{网站用户ID}/data/{数据UUID}/{日期}.json` |

以上运行文件已由 `.runtime/` 的 Git 忽略规则隔离。佳明缓存按网站用户隔离，不写入广告业务表，也不写入独立 `personal-life` 的 PostgreSQL。后续接入那个健康服务需要单独的指标导入适配，不因本功能安装而自动完成。

账号密码只用于当前连接请求，不作为持久配置保存。token、状态和健康缓存仍属于敏感资料：Linux 用户/session/token 目录为 `0700`，文件为 `0600`，由 Java 运行账号拥有；Windows 依靠该账号的文件 ACL，不声称已设置 POSIX 权限。不要把 `.runtime` 加入 Nginx 静态目录。服务器管理员和备份持有人仍可访问这些文件。备份与故障排查请在私有环境进行，不把 token、密码、MFA 码或原始健康响应贴到聊天和公开日志。

## 1. 安装 Python 运行环境

更新完整仓库后，在 Linux 服务器执行原安装命令即可：

```sh
cd /www/wwwroot/BI
sh scripts/install-garmin.sh
```

脚本优先使用现有 Python 3.12+（包括 `venv`、`ensurepip`、SSL）。找不到时，`bootstrap-garmin-python.sh` 会从 [Astral Python Build Standalone 固定发行版](https://github.com/astral-sh/python-build-standalone/releases/tag/20261009) 下载 Python **3.12.15** 的 GNU `install_only_stripped` 包，校验内置 SHA256 后放到上表中的项目私有目录，再在最终路径创建虚拟环境。支持 Linux x86_64/aarch64、glibc 2.17+；需 `curl`、`tar`、`sha256sum` 和至少 512 MB 可用空间。下载约 30 MB，速度取决于服务器访问 GitHub Releases 的网络。

这适用于缺少新 Python 的阿里云 Linux 3 宝塔服务器。[阿里云官方说明](https://www.alibabacloud.com/help/en/alinux/support/install-a-newer-python-3-on-alibaba-cloud-linux-3) 中，标准版官方仓库列出 Python 3.8/3.11，AI 扩展版才列出 3.12。无需改系统 `python3`、安装编译工具或更换系统软件源。下载失败、校验失败、架构不支持时会停止，未通过校验的内容不会执行；原环境保留，可处理提示后重跑同一安装命令。

已有可用的独立解释器会复用，不重复下载。**不要删除 `.runtime/garmin-interpreter`**，已创建的虚拟环境依赖它；也不要移动虚拟环境目录。服务器已有其他 Python 3.12+ 时，可设置 `GARMIN_BOOTSTRAP_PYTHON=/绝对路径/bin/python3.12` 使用它。非 Linux 或 musl 系统请自行提供兼容解释器。

从 root 执行时，脚本会创建仅属于 `www` 的专用虚拟环境/解释器目录，再切换到 `www` 下载和安装；已有 `.runtime` 不更改所有权或权限。也可在确认 `.runtime` 对 Java 账号可写后明确执行：

```sh
runuser -u www -- sh /www/wwwroot/BI/scripts/install-garmin.sh
```

如果 Java 实际使用其他运行账号，请用那个账号执行安装。已有虚拟环境/解释器目录属于别的账号时脚本会退出，不会覆盖或删除。确认路径确实是本仓库的对应目录且不是符号链接后，只修复这些目录，例如：

```sh
chown -R www:www /www/wwwroot/BI/.runtime/garmin-python
chown -R www:www /www/wwwroot/BI/.runtime/garmin-interpreter
runuser -u www -- sh /www/wwwroot/BI/scripts/install-garmin.sh
```

不要对整个仓库或 `.runtime` 递归改权限。`GARMIN_BOOTSTRAP_PYTHON` 可在安装前设为已有 Python 3.12+ 的可执行文件绝对路径；这是创建虚拟环境的解释器，不是佳明账号配置。

脚本固定使用本仓库 `.runtime/garmin-python`，可重复运行；Python 下载访问 GitHub Releases，依赖安装访问 PyPI，不访问佳明。安装缓存、临时文件和私有错误记录位于项目私有运行目录；失败时不会自动输出依赖工具日志。它不会拉取 Git、重启 Java、运行同步、改数据库或安装系统包。

安装全程持有 `.runtime/garmin-interpreter/.venv-install-lock` 空目录锁，重复启动会退出，避免同时修改虚拟环境。正常退出或收到终止信号时会释放；若进程被强制杀死，先确认无安装进程运行，再用 `rmdir` 移除提示中的空锁目录后重试，不要删除解释器或虚拟环境。

如果首次创建虚拟环境时被磁盘或 IO 错误打断、留下缺少 pip 的环境，解决故障后重跑会尝试用该环境的 `ensurepip` 离线恢复，无需删除重建。下载/校验失败不会替换解释器；pip 更新依赖不是事务性回滚，依赖安装失败需检查私有 `install.log`，脚本不会删除已有虚拟环境或健康缓存。

## 2. 部署 Java BI

安装 Python 和部署 Java 是两个步骤。按现有部署方式更新代码、使用 Java 21 构建 JAR，再重启服务：

```sh
cd /www/wwwroot/BI
sh scripts/deploy-server.sh
```

该部署脚本会拉取配置的主分支、检查磁盘和 JDK、构建 `target/marketing-reports-1.0.0.jar`，成功后重启 `dahanghai-analysis`。它不会自动安装佳明 Python，也不会登录佳明。若 Java 版本已经部署、仅安装 Python，无需重新构建 Java；安装完成后稍等一分钟，再刷新佳明页面的状态。已有 BI 的 Java、MySQL、Nginx 配置沿用；不增加健康 PostgreSQL 或公开 Python 服务端口。

Java 默认从 `DHH_RUNTIME_DIR/garmin-python/bin/python` 找解释器，Windows 对应 `Scripts/python.exe`，最后才尝试 `python3`。安装脚本固定用仓库下的 `.runtime`；如果 `DHH_RUNTIME_DIR` 指向其他路径，或 Python/worker 位于其他位置，请在实际 Java 服务环境中设置非敏感路径配置：

```ini
[Service]
Environment=GARMIN_PYTHON=/www/wwwroot/BI/.runtime/garmin-python/bin/python
Environment=GARMIN_WORKER_SCRIPT=/www/wwwroot/BI/scripts/garmin_worker.py
```

可以通过 `systemctl edit dahanghai-analysis` 新建 drop-in；保存后执行 `systemctl daemon-reload` 与 `systemctl restart dahanghai-analysis`。宝塔直接运行 JAR 时，在对应 Java 项目环境配置相同变量。无需把佳明密码、邮箱或 MFA 码写入环境文件。

## 3. 检查与首次连接

1. 现有 Java 部署探针检查 `/api/session`；它仅确认 BI 服务响应，不确认佳明账号或依赖。
2. 浏览器登录 BI，在工具中心打开佳明页面。已有网站账号需要获得该工具权限。
3. 页面使用 `GET /api/garmin/status` 查看 `available`/`setupError`。这个接口需要网站登录和工具权限，只检查本地 worker 是否就绪，不会执行佳明登录或读取外部健康数据；没有新公共 `/healthz` 接口。
4. 准备自己的 Garmin Connect 账号后，在页面明确连接；如有 MFA，按页面提示填写验证码。连接与数据读取依赖服务器可达佳明服务，不能通过关闭验证或反复登录解决风控。
5. 先手动同步最近 7 天，对照 Garmin Connect 的日期、步数、睡眠与运动记录。无记录或未知格式应显示缺失，不能当作零或生成示例健康数据。

自动同步默认关闭。确认手动同步正常后，可选择每 1 小时或每天同步；查询范围默认 7 天、最多 90 天。佳明失败时保留已有缓存，不应反复提交密码或无限重试。检查页面提示；依赖缺失先修 Python，MFA/登录失效按连接提示处理，限流稍后重试，字段变化需要更新适配并验证。

断开连接会停止任务并删除账户 token，保留已有健康缓存供查看；断开连接不等于删除全部历史数据。

测试与部署不使用真实账号做自动登录。未经本人确认，不开启定时同步；不声称代码或本地依赖检查已验证真实佳明端到端连接。

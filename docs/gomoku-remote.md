# 五子棋远程对战

登录网站，进入工具中心 → 游戏大厅 → 五子棋，选择“双人远程”。房主点击“创建房间”，将房间号或复制的邀请链接发给对方；对方登录后输入房间号，或打开邀请链接加入。

房主执黑先行，加入者执白。同一个网站账号可在两台设备上分别入座；每个浏览器标签页的客户端标识保存在按登录用户隔离的 `sessionStorage` 中，不包含在邀请链接里。刷新本标签页可以恢复房间及自己的执子；清除浏览器会话数据后无法恢复原来的席位。

落子、胜负和回合以服务器为准。申请悔棋撤回申请者最近一手及其后的对方落子，双方确认后生效；“再来一局”也需要对方同意。请求待确认期间暂停落子。返回大厅或切换模式会暂停房间同步并保留房间，重新进入后读取最新棋局。离开房间会结束双方对局。

房间保存在服务进程内存，最多保留 256 个房间，每个账号最多参与 8 个未结束的房间；无人访问 2 小时后清理，服务器重启后失效。无需数据库迁移或新增 Nginx WebSocket 配置。

## 同步与校验

接口前缀 `/api/games/gomoku/rooms`，沿用网站登录 Cookie，每个请求还必须带 `X-Game-Client` UUID。服务端把 UUID 与登录用户绑定，读取或操作棋盘必须是本房间成员。

| 请求 | 用途 |
| --- | --- |
| `POST /` | 创建房间，重复创建返回本客户端已有的房主房间 |
| `POST /{code}/join` | 加入房间，已有席位的客户端重复加入返回原席位 |
| `GET /{code}?since=N&wait=true` | 有更新立即返回，否则异步等待至多 20 秒 |
| `POST /{code}/moves` | 落子，提交 `row`、`col`、`version` |
| `POST /{code}/requests` | 发起 `undo` 或 `restart`，提交 `type`、`version` |
| `POST /{code}/requests/respond` | 对方确认，提交 `accept`、`version` |
| `POST /{code}/leave` | 结束房间 |

状态版本随入座、落子、请求、确认及离开增加。版本不符返回 `409`，前端重读最新状态后允许继续操作；断网期间禁止落子并自动重连。同步采用 Spring MVC `DeferredResult`，等待时不占用 Servlet 工作线程；每个房间最多保留 8 个等待连接，响应完成或超时后释放。在线状态按最近 45 秒的访问判断。

## 验证

Java 测试覆盖成员身份、同账号双设备、并发版本冲突、四方向胜负、和棋、悔棋与重开确认、异步通知、上限和过期清理。

```bash
./mvnw -Dtest=GomokuRoomServiceTest,GomokuApiControllerTest test
./mvnw test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile=.runtime/gomoku-browser-classpath.txt
node scripts/gomoku-online-ui-test.cjs
node scripts/games-ui-test.cjs
```

Windows 使用 `mvnw.cmd`。浏览器测试使用本机 Chrome、JDK 21 与 Playwright，真实启动测试用 Java HTTP 服务及签名会话，运行两个独立浏览器环境检查同步、悔棋、重开、刷新、断线重连和手机布局；不连接生产网站或数据库。

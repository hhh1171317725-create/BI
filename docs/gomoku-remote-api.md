# 五子棋远程房间 API

远程对局通过同源 HTTP JSON 接口与异步长轮询同步。服务端负责落子顺序、位置、胜负、版本和双方确认；客户端棋盘只是服务端快照的显示。

房间仅保存在当前 Java 服务进程的内存中，不写数据库或运行目录。**服务器重启、重新部署或连续两小时无人访问都会结束房间。** 当前设计用于单个 Java 服务实例；多个独立实例需要共享房间存储或会话路由。

所有接口都需要现有登录 Cookie，以及 `X-Game-Client` 请求头。请求头是浏览器标签页生成并保存在 `sessionStorage` 的 UUID，不应放在房间链接中。账号 ID 与客户端 UUID 一起识别席位，同一个账号在两个设备或标签页中使用不同 UUID 可以分别执黑和执白。UUID 格式不合法返回 400，UUID 已绑定其他账号返回 400；读取或操作其他房间返回 403。

接口前缀：`/api/games/gomoku/rooms`。

| 方法与路径 | 请求内容 | 行为 |
| --- | --- | --- |
| `POST /` | 无 | 创建房间，创建者执黑。相同账号和 UUID 对未结束的自建房间重试时返回该房间。 |
| `POST /{code}/join` | 无 | 加入白方席位。已有席位重试返回自己的快照，第三位玩家返回 409。 |
| `GET /{code}` | `since=-1&wait=false` 为默认值 | 读取当前快照。`wait=true` 且版本未大于 `since` 时异步等待，最多 20 秒。 |
| `POST /{code}/moves` | `{"row":7,"col":7,"version":2}` | 提交 0–14 的棋盘坐标。版本过期、轮次错误、已有棋子或无法落子时返回 409。 |
| `POST /{code}/requests` | `{"type":"undo","version":3}` 或 `{"type":"restart","version":3}` | 发起悔棋或重新开始，等待对方同意，期间暂停落子。 |
| `POST /{code}/requests/respond` | `{"accept":true,"version":4}` | 仅对方可以同意或拒绝。 |
| `POST /{code}/leave` | 无 | 结束双方的房间并唤醒长轮询；重复退出返回同一结束状态。 |

房间码是六位大写字母或数字，排除易混淆的字符。输入房间码会去除首尾空白并转为大写。

所有成功响应都是相同结构的个性化快照：

```json
{
  "code": "ABCDEF",
  "version": 2,
  "seat": 1,
  "phase": "playing",
  "game": {
    "board": [[0, 0, 0]],
    "moves": [{"row": 7, "col": 7, "player": 1}],
    "currentPlayer": 2,
    "status": "playing",
    "winner": null,
    "winningLine": []
  },
  "players": [
    {"seat": 1, "name": "player-1", "online": true},
    {"seat": 2, "name": "player-2", "online": true}
  ],
  "request": null,
  "closedReason": null
}
```

示例棋盘省略了完整的 15×15 数据；实际响应总是完整棋盘，0 为空、1 为黑、2 为白。`phase` 可以为 `waiting`、`playing`、`won`、`draw` 或 `closed`；`game.status` 为 `playing`、`won` 或 `draw`。待确认请求是 `{"type":"undo"或"restart","by":1或2}`。快照不会包含账号 ID 或客户端 UUID。

连续五枚或更多棋子横向、纵向或斜向连线即可获胜，没有禁手。棋盘填满且无人获胜时为和棋。同意悔棋会撤掉请求者最近的一步及之后对方的一步，并允许从终局重新继续。同意重新开始会清空棋盘，保留双方席位，由黑方先行。等候对手时无法发起重新开始。

房间版本随对局状态变化单调递增。对在线状态的更新不改变版本：最后一次访问在 45 秒以内为在线，长轮询超时仍返回最新在线状态和当前快照。每个房间最多保留 8 个等待连接，超出返回 429；已完成、超时、出错或关闭的连接会移除。

服务最多保留 256 个房间、每个账号最多参加 8 个未结束房间。创建新房间时可淘汰已结束房间，因此旧的已结束房间也可能返回 404。过期房间移除前会以 `closed` 快照通知等待中的双方。全局或账号容量不足返回 429，房间不存在或过期返回 404，登录失效返回 401。错误响应复用现有 JSON 格式 `{"error":"说明"}`。

相关回归测试：`GomokuRoomServiceTest` 与 `GomokuApiControllerTest`。

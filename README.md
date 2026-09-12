# Simple P2P

Minecraft 1.20.1 Forge 联机模组。用**房间号**代替 IP 与端口转发配置，服务端与客户端都会自动下载并启动
[EasyTier](https://github.com/EasyTier/EasyTier) 官方客户端完成组网，玩家只需把房间号发给好友。

## 功能

- 服务端执行 `/p2p open` 生成房间号；好友在「多人游戏」的地址栏直接填房间号即可加入
- 自动下载、安装、启动 EasyTier 官方客户端，无需手动配置虚拟网卡或端口映射
- 自动从社区节点列表拉取公共节点并**实测延迟**，挑选可用节点组网
- 组网进度与结果实时显示在游戏右上角，失败时给出具体原因
- 同一个 jar 同时适用于客户端与服务端

## 环境要求

- Minecraft 1.20.1 + Forge 47 及以上
- Windows / Linux / macOS（Windows 无需管理员权限）
- 能够访问 EasyTier 社区公共节点

## 安装

将 `SimpleP2P-1.0.0.jar` 放入 `mods/` 目录。**服务端与客户端都要安装**。

## 使用

### 开房（服务端）

1. 进入单人世界并在游戏内执行「对局域网开放」，或启动专用服务器
2. 执行 `/p2p open`
3. 把聊天栏显示的房间号发给好友

若 MC 实际端口不是 25565，先执行 `/p2p setport <端口>` 再开房。

### 加入（客户端）

1. 进入「多人游戏」→「添加服务器」或「直接连接」
2. 服务器地址处填写房间号
3. 右上角提示组网完成（出现「已建立本地转发」）后会自动进入游戏

## 命令

| 命令 | 说明 |
| --- | --- |
| `/p2p open` | 开启房间，生成房间号 |
| `/p2p close` | 关闭房间 |
| `/p2p status` | 查看房间与组网状态 |
| `/p2p setport <端口>` | 设置本机 MC 端口（默认 25565） |
| `/p2p settoken <token>` | 设置 OpenP2P Token（备用通道，可选） |
| `/p2p sslignore on\|off` | 下载核心失败时是否忽略 SSL 证书校验 |

## 工作原理

1. 房间号派生出 EasyTier 的网络名与密钥：`sp2p-<房间号>` 与 `sp2p:<房间号>`
2. 服务端与客户端各自启动 EasyTier 官方客户端，加入同一个虚拟网络
3. 服务端在 `hostname` 中携带真实 MC 端口，并用 `--tcp-whitelist` 把该端口暴露到虚拟网络
4. 客户端通过 `easytier-cli port-forward` 将本地端口转发到服务端的 MC 端口
5. Minecraft 连接 `127.0.0.1:<本地端口>`，不依赖虚拟网卡路由，因此不需要管理员权限

组网参数参考了 [MinecraftConnectTool](https://github.com/MCZLF/MinecraftConnectTool) 的 ET 模式，
包含 `--no-tun`、`--use-smoltcp`、`--compression zstd`、KCP/QUIC 代理等，以适应高延迟与丢包链路。

## 配置

首次启动会自动生成 `config/simplep2p.json`，包含节点列表地址、下载源、超时时间等。

配置目录内还会出现 `mods/simplep2p/`，用于存放自动下载的 EasyTier 客户端与运行日志（`easytier.log`）。

## 构建

```bash
./gradlew build
```

产物：`build/libs/SimpleP2P-1.0.0.jar`

## 已知限制

- 组网依赖 EasyTier 社区公共节点，节点可用性直接影响连接成功率
- 双方 NAT 类型严格时会走中继，延迟较高
- 若所有公共节点均不可达，会回退到配置文件中的默认节点

# NetPump Mobile 1.0 — 安卓 4G / 5G 工程灌包工具

**项目性质：可编译的 Android 原生 App + 可直接运行的 PC/VPS 服务端。**

此仓库包含完整 Android Studio 工程、Python 标准库服务端、协议测试和 GitHub Actions APK 构建工作流。**本交付包中的 Android 客户端未经 Android SDK 编译，也未经过真机联调，因此不包含可直接安装的 APK。** 服务端已完成 TCP/UDP 协议自动化测试（见 `server/tests/`）。

## 1. 功能

| 项目 | 支持情况 |
| --- | --- |
| TCP 上行、下行、双向 | ✅ |
| UDP 上行、下行、双向 | ✅ |
| TCP 并发 | 1~16 路/方向 |
| UDP 并发 | 1~16 路/方向 |
| 目标速率 | TCP 可选 0 不限速，UDP 必填 Mbps |
| 测试时长 | 1~600 秒 |
| 实时上下行速率曲线 | ✅ |
| 平均、峰值速率与 MB 数 | ✅ |
| UDP 丢包和 jitter | ✅，上下行计算方法不同，详见下文 |
| 连接探测 RTT | ✅，含 TCP 建连耗时，仅用于估计连通性 |
| 手机蜂窝网络信息 | ✅，依设备及权限可用性 |
| 后台灌包 | ✅，前台服务 + 状态通知 + WakeLock |
| 历史记录 | 最近 100 次 |
| CSV 导出 | ✅，Android 文件选择器保存 |
| Root | 无需 |
| iperf3 协议 | **不支持**，这是轻量级自有协议，客户端需搭配本项目服务端 |

## 2. 使用（需要远端服务端）

在 Windows / Linux / macOS 装好 Python 3.9+。服务端无第三方依赖。Windows 用户也可直接双击 `server/start-server-windows.bat`，按提示输入口令。

局域网测试时在电脑运行：

```bash
python server/netpump_server.py --bind 0.0.0.0 --port 5201 --token MyPrivate123
```

防火墙放行 **TCP 5201 与 UDP 5201**，但只授权测试设备访问。Windows 要将计算机 IP 地址确认清楚（例如 `192.168.1.10`）。

手机端填入该 IP、端口 `5201`、连接口令 `MyPrivate123`，协议 `TCP` 或 `UDP`，方向 `UL` / `DL` / `BOTH`，测试秒数及流数，点击“开始测试”。

### 5G 蜂窝实测提示

* 如果电脑和手机都在同一个 Wi-Fi 下，测速是 Wi-Fi/局域网吞吐，**不能代表基站空口速率**。
* 要测 5G，请关闭手机 Wi-Fi，保持蜂窝数据连接；远端服务端需要手机**可以从互联网访问**。局域网内 `192.168.x.x` 的地址通常不可从 5G 网络直接连接。
* 手机、运营商 CGNAT、防火墙、服务器带宽、服务端 CPU 等都可能成为瓶颈。建议将服务端放在**有公网地址、高带宽**、获授权的电脑或 VPS 上。
* 绝对不要用未获授权的公网 IP 灌包。上行/下行测试都可能耗费大量套餐流量。
* 一个 100Mbps 持续 60 秒单向测试约 750MB，实际用量可能略高。
* 需要使用标准 `iperf3` 公网测试节点时，本 App 暂不可直连，需部署此包的自有服务端。

## 3. 编译 Android APK

### Android Studio（推荐）

1. 安装 Android Studio 和 Android SDK Platform 35（无需 Root 手机）。
2. 使用 **Open** 打开此目录 `NetPumpMobile`。
3. 确保 Gradle 8.9（项目使用 Android Gradle Plugin 8.7.3）和 JDK 17。
4. 执行菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
5. 产物默认在 `app/build/outputs/apk/debug/app-debug.apk`。
6. 传到安卓手机并安装，允许必要的安装权限。

项目不带 Gradle Wrapper JAR，建议优先使用下方 Windows 脚本完成首次构建；如果 Android Studio 无法直接同步，请在 Android Studio 的 Gradle 设置中指定本机 Gradle 8.9；GitHub Actions 会自动配置 Gradle 8.9。

### Windows PowerShell

项目自带 `scripts/build-windows.ps1`。安装 Android Studio 的 SDK Platform 35 后，在 PowerShell 运行该脚本；若系统没有 Gradle，脚本首次构建时会自动从官方下载 Gradle 8.9。需要可用的 Java（JDK 17 或 Android Studio 自带兼容 JBR）。

### GitHub Actions

将整个目录上传到**新建的独立 GitHub 仓库**默认分支，在 Actions 页面手动执行 **Build NetPump APK**；或者将文件推到 main 分支，工作流自动编译 debug APK。完成后在运行页的 Artifacts 中下载 `NetPumpMobile-debug-apk`。

> Debug APK 会由构建环境本地生成调试密钥并签名。长期使用或应用分发，应自行创建并安全保存 release 签名密钥。

## 4. 局域网 / 公网安全

- `netpump_server.py` 默认绑定 `127.0.0.1`，只接受本机访问。向其他设备开放须显式加 `--bind 0.0.0.0`。
- 公网环境务必开启 `--token`、防火墙源 IP 白名单，并尽可能部署在可控网络。测试口令基于**明文协议传输**，无法替代 TLS/VPN 安全保护。
- 服务端仅提供基于时长的 TCP/UDP 数据生成和接收，不包含命令执行与文件系统访问。
- 每条连接最多运行 600 秒，UDP 发送速率单连接最多为 2000 Mbps。
- 客户端不会静默自动启动流量测试，只有点击开始后才会主动发送。

## 5. 指标定义和精度

- `瞬时速率` = 过去采样周期内客户端 socket 已写入 / 收到的用户态数据字节 × 8 / 秒（每 250ms 更新）。
- `平均速率` = 测试累计数据量 × 8 / 实际测试秒数；因线程握手/调度，起始阶段平均值可能偏低。
- `峰值速率` = 250ms 采样窗口的最大速率，**不是** 3GPP 层吞吐量统计。
- `连接探测耗时`：测试前一次 TCP 连接 + PING/PONG 应用层往返的耗时。**包含连接建立时间，不能当作 ICMP ping 或纯网络 RTT。**
- `UDP 上行丢包率`：服务端按序号估算缺包，通过测试结束后的 TCP STATS 控制连接回传。若 STATS 控制连接失败，丢包与 jitter 可能显示 0，**不能视为零丢包**。
- `UDP 下行丢包率`：手机统计最大序号 + 1 与接收包数之差。报文重排、重复包、开始/结束处网络时延可能影响估计精度。
- `jitter`：接收时间间隔变化量的平滑估计（RFC 3550 同类算法），不是严格的 3GPP 时延抖动测试。
- 5G 网络参数采用 Android 系统 `TelephonyManager/CellInfo`，NSA、运营商系统限制及权限可能导致 5G/PCI/RSRP/SINR 缺项。若出现 `2147483647` 说明系统无可用数值。
- 当前不输出 HTTP、Ping/RTT、MCS、CQI、PRB、TA 等高阶网优指标；它们需要额外协议或设备系统接口。
- 若手机在后台，Android 15+ `dataSync` 前台服务受平台累计时长限制；单次时长上限设置为 600 秒。

## 6. 协议概要

TCP 控制/数据端口和 UDP 数据端口均为 5201（可自定义）。自定义协议不是 iperf3。单行 ASCII 控制协议：

```text
PING <token-or->                             -> PONG
UP <duration_ms> <token-or->                 -> OK + client->server raw stream
DOWN <duration_ms> <token-or-> <rate_mbps>   -> OK + server->client raw stream
STAT <session-hex> <token-or->              -> STATS <received> <lost> <jitter_ms>
UDP_DOWN <session-hex> <duration_ms> <mbps> <packet_size> <token-or-> -> OK + UDP download
```

UDP 客户端先发送 `HELLO <32 hex session id> <token-or->` 打洞。随后数据报头为 `>4sQQI`：magic `NPT1`、序号 `uint64`、单调时间戳纳秒 `uint64`、session id 的 CRC32 `uint32`，后接固定负载。网络报文未加密、未传输真实业务数据。

## 7. 服务端自动化测试

```bash
python -m unittest discover -s server/tests -v
```

目前覆盖 PING 探测、TCP 上行、TCP 下行、访问口令、UDP 上行统计和 UDP 下行报文收取。

## 8. 目录

```
app/src/main/java/com/netpump/engineering/
  MainActivity.java    - 原生配置界面、曲线、无线信息、记录/CSV
  PacketRunner.java    - TCP/UDP 多并发测速与计算
  TestService.java     - 前台服务、通知、后台运行及持久化
server/
  netpump_server.py   - Python 标准库 PC/VPS 服务端
  tests/              - Python 协议测试
.github/workflows/    - 云端 APK 编译流程
```

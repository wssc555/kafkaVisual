# Kafka Visualizer

<p align="right">
  <a href="README_EN.md">English</a> | 简体中文
</p>

多集群 Kafka 可视化管理工具。支持集群注册与连接管理、Dashboard 概览、Topic 管理、消息查询与生产、消费组监控、消息归档、ZooKeeper 浏览，兼容 SASL / mTLS / OAuth 等多种认证方式。即可作为 Web 应用部署，也可打包为桌面应用（Electron）本地运行。

## 功能特性

- **多集群管理** — UI 中增删改集群、连接/断开、测试连接；凭据加密存储
- **多种认证** — 无认证、SASL（PLAIN / SCRAM）、mTLS（PEM 证书）、OAuth2、自定义属性
- **Topic 管理** — 列表、创建、删除、分区/副本/ISR/Offset 详情、扩分区、配置修改
- **消息查询与生产** — 按分区/Offset/时间点浏览，JSON 美化，支持生产消息
- **消费组监控** — 列表、详情、Lag 监控、重置 Offset
- **消息归档** — 消息落库（SQLite / PostgreSQL / MySQL），支持时间范围回查
- **ZooKeeper 浏览** — ZK 模式集群的节点树浏览与管理
- **Dashboard** — 单集群总览 + 全部集群多总览
- **界面国际化** — 中文 / English 一键切换（vue-i18n，选择本地持久化）
- **收藏** — Topic / 消费组星标收藏，按集群隔离，离线可用
- **定时巡检与多通道告警** — 按集群定义巡检规则（指标 + 阈值 + 可选时间窗口 + 每规则独立 Cron），
  触发后经站内信 / 邮件 / Webhook / IM 机器人（钉钉 / 飞书 / 企业微信）通知；状态去重 + 恢复通知，
  告警中心跨集群汇总与确认，顶部未读徽标
- **站内通知中心** — 顶栏铃铛展示未读徽标与最近通知面板，支持单条/全部已读，点击跳转告警中心详情

## 架构

前后端分离架构，后端直连 Kafka（不依赖 spring-kafka）：

```
┌─────────────────────┐        REST /api          ┌──────────────────────────────┐
│      Frontend        │ ────────────────────────▶ │        Backend (Spring Boot)  │
│  Vue 3 + Element Plus│ ◀──────────────────────── │                              │
│      (Vite, pnpm)    │     统一 JSON 响应封装      │  controller ──▶ service      │
└─────────────────────┘                           │       │            │         │
                                                  │       ▼            ▼         │
                                                  │  kafka/          zk/         │
                                                  │  AdminClient     Curator     │
                                                  │  消费者连接池      (ZK 模式)    │
                                                  │       │                      │
                                                  │       ▼                      │
                                                  │  storage/  ◀── archive/      │
                                                  │  SQLite / PG / MySQL         │
                                                  └──────────────────────────────┘
```

**后端模块**（`src/main/java/com/example/kafkaviz/`）：

| 模块 | 职责 |
|------|------|
| `controller/` | REST 控制器，业务接口挂在 `/api/c/{clusterId}` 段下 |
| `service/` | 业务逻辑 |
| `kafka/` | AdminClient 管理、消费者连接池（commons-pool2，每集群独立） |
| `zk/` | ZooKeeper 浏览（Curator） |
| `inspection/` | 定时巡检引擎：规则调度、指标采集、阈值评估、多通道告警分发 |
| `archive/` | 消息归档管道（每集群一张归档表） |
| `storage/` | 三种存储方言（SQLite/PG/MySQL）、集群配置存储、Schema 迁移 |
| `security/` | 凭据加解密 |
| `config/` · `exception/` · `model/` · `web/` | 配置、错误码、DTO/VO、Web 支撑 |

**关键设计**：
- 集群连接参数存本地数据库（UI 管理），`application.yml` 仅提供首次启动种子与全局默认值
- 消息查询消费者使用 `assign()` 手动分配分区 + 随机 group.id，**不影响**真实消费组
- 存储后端可在 UI 中切换（存于 app-data 目录下的 `storage.json`，默认 `data/`），重启生效
- 空闲集群连接自动回收，按需重建
- 桌面模式经 `--app.db-path` 把数据库与密钥指向 OS 用户数据目录，与仓库目录隔离

## 技术栈

**后端**
- Spring Boot 3.4.6 / Java 21 / Maven
- `kafka-clients` 3.9.0 — Kafka 直连客户端
- `curator-framework` — ZooKeeper 客户端
- `commons-pool2` — 消费者连接池
- `spring-boot-starter-mail` — 巡检告警的 SMTP 通道
- `caffeine` — 本地缓存
- `sqlite-jdbc` / `postgresql` / `mysql-connector-j` — 存储后端

**前端**
- Vue 3.5 + TypeScript / Vite 6 / Element Plus / Vue Router 4 / vue-i18n 11 / Axios / Vitest 4
- 包管理器：pnpm 12

**桌面版**
- Electron 45 + electron-builder 26 — 主进程壳托管前端静态服务器，并拉起后端 sidecar
  （`app.jar` + jlink 精简 JRE），随应用退出自动回收

## 快速开始

### 环境要求

- JDK 21+、Maven 3.8+
- Node.js 18+、pnpm 12+（`npm i -g pnpm`）

### Web 模式

**1. 启动后端**（项目根目录）：

```bash
mvn spring-boot:run        # http://localhost:8080
```

**2. 启动前端**（`frontend/` 目录）：

```bash
pnpm install    # 首次
pnpm dev:web    # http://localhost:5173，/api 自动代理到 8080
```

浏览器访问 `http://localhost:5173`，首次进入页面会引导添加 Kafka 集群（填写地址与认证方式，可先测试连接）。集群配置存入本地数据库（默认 SQLite，位于 `data/`），凭据自动加密。

> 无人值守部署：设置环境变量 `KAFKA_BOOTSTRAP_SERVERS`，集群表为空时首次启动自动导入为首条集群记录。

### 桌面版（Electron）

桌面壳位于 `frontend/electron/`（Electron 主进程 + preload），**不需要 Rust 工具链**。
打包前须先准备 sidecar 产物到仓库根目录 `build/resources/`：`app.jar`（`mvn clean package` 产物）
与 `runtime/`（jlink 精简 JRE），两者缺一会导致构建失败：

```bash
mvn clean package                                   # 产出 app.jar
mkdir -p build/resources
cp target/kafka-visualizer-1.0.0.jar build/resources/app.jar
jlink --no-header-files --no-man-pages --strip-debug --compress=zip-6 \
  --add-modules java.se,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata,jdk.crypto.ec \
  --output build/resources/runtime                  # 产出精简 JRE
cd frontend
pnpm install
pnpm dist                                           # 打包当前平台；或 dist:win / dist:mac / dist:linux
```

产物在 `frontend/release/`。架构固定：Windows / Linux 仅 x64，macOS 仅 arm64（与 jlink runtime 一致）。

桌面模式下后端由主进程以
`java -jar app.jar --server.port=0 --announce-port --app.db-path=<用户数据目录>` 拉起，
后端在 stdout 打印 `PORT=<port>` 完成握手；数据库与密钥落在 OS 用户数据目录（而非仓库 `data/`），
随应用退出自动终止，无需手动管理。

开发态 `pnpm dev` 一键起桌面壳（编译主进程 + vite dev server + Electron 窗口），
不准备 sidecar，`/api` 走 vite 代理——需另开终端 `mvn spring-boot:run` 起后端。

### 生产部署

```bash
cd frontend && pnpm build      # 产物在 frontend/dist/（Web）与 frontend/dist-electron/（桌面主进程）
cd .. && mvn clean package     # 产物 target/kafka-visualizer-1.0.0.jar
```

前端静态文件用 Nginx/CDN 部署，反向代理 `/api` 到后端 JAR；或手动将 `dist/*` 复制到 `src/main/resources/static/` 后打包为单 JAR。

## 常用命令

| 位置 | 命令 | 说明 |
|------|------|------|
| 根目录 | `mvn spring-boot:run` | 启动后端（Web 模式，:8080） |
| `frontend/` | `pnpm dev:web` | 启动前端 Web 开发服务器（:5173，/api 代理到 8080） |
| `frontend/` | `pnpm dev` | 桌面壳开发模式（vite + Electron 同帧调试） |
| `frontend/` | `pnpm test` | 前端单元测试（Vitest） |
| `frontend/` | `pnpm build` | 类型检查 + 构建（dist/ 与 dist-electron/） |
| `frontend/` | `pnpm dist:win` / `dist:mac` / `dist:linux` | 打包桌面安装程序 |

> 仓库当前未包含后端测试代码（`src/test/` 为空），`mvn test` 裸跑为 0 个测试属正常；前端测试为 Vitest（`__tests__/` 与 `*.test.ts`）。

## 注意事项

- 删除 Topic / 删除消费组操作不可逆
- app-data 目录（裸跑默认 `data/`，桌面版为 OS 用户数据目录）包含本地数据库与加密密钥（`secret.key`），迁移或备份时需一并保存

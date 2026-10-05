# Kafka Visualizer

**本程序仅供学习了解, 请于下载后 24 小时内删除**

<p align="right">
  <a href="README_EN.md">English</a> | 简体中文
</p>

多集群 Kafka 可视化管理工具。支持集群注册与连接管理、Dashboard 概览、Topic 管理、消息查询与生产、消费组监控、消息归档、ZooKeeper 浏览，兼容 SASL / mTLS / OAuth 等多种认证方式。即可作为 Web 应用部署，也可打包为桌面应用（Tauri）本地运行。

## 功能特性

- **多集群管理** — UI 中增删改集群、连接/断开、测试连接；凭据加密存储
- **多种认证** — 无认证、SASL（PLAIN / SCRAM）、mTLS（PEM 证书）、OAuth2、自定义属性
- **Topic 管理** — 列表、创建、删除、分区/副本/ISR/Offset 详情、扩分区、配置修改
- **消息查询与生产** — 按分区/Offset/时间点浏览，JSON 美化，支持生产消息
- **消费组监控** — 列表、详情、Lag 监控、重置 Offset
- **消息归档** — 消息落库（SQLite / PostgreSQL / MySQL），支持时间范围回查
- **ZooKeeper 浏览** — ZK 模式集群的节点树浏览与管理
- **Dashboard** — 单集群总览 + 全部集群多总览

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
| `archive/` | 消息归档管道（每集群一张归档表） |
| `storage/` | 三种存储方言（SQLite/PG/MySQL）、集群配置存储、Schema 迁移 |
| `security/` | 凭据加解密 |
| `config/` · `exception/` · `model/` · `web/` | 配置、错误码、DTO/VO、Web 支撑 |

**关键设计**：
- 集群连接参数存本地数据库（UI 管理），`application.yml` 仅提供首次启动种子与全局默认值
- 消息查询消费者使用 `assign()` 手动分配分区 + 随机 group.id，**不影响**真实消费组
- 存储后端可在 UI 中切换（存于 `storage.json`），重启生效
- 空闲集群连接自动回收，按需重建

## 技术栈

**后端**
- Spring Boot 3.4.6 / Java 21 / Maven
- `kafka-clients` 3.9.0 — Kafka 直连客户端
- `curator-framework` — ZooKeeper 客户端
- `commons-pool2` — 消费者连接池
- `caffeine` — 本地缓存
- `sqlite-jdbc` / `postgresql` / `mysql-connector-j` — 存储后端

**前端**
- Vue 3 + TypeScript / Vite 5 / Element Plus / Vue Router 4 / Axios / Vitest
- 包管理器：pnpm 12

**桌面版**
- Tauri 2 — 后端 JAR + 精简 JRE 作为 sidecar 随应用分发，前端打包进 WebView

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
pnpm dev        # http://localhost:5173，/api 自动代理到 8080
```

浏览器访问 `http://localhost:5173`，首次进入页面会引导添加 Kafka 集群（填写地址与认证方式，可先测试连接）。集群配置存入本地数据库（默认 SQLite，位于 `data/`），凭据自动加密。

> 无人值守部署：设置环境变量 `KAFKA_BOOTSTRAP_SERVERS`，集群表为空时首次启动自动导入为首条集群记录。

### 桌面版（Tauri）

需要额外安装 Rust 工具链。`tauri build` 前须先准备 sidecar 产物到 `src-tauri/resources/`：
`app.jar`（`mvn clean package` 产物）与 `runtime/`（jlink 精简 JRE），两者缺一会导致构建失败：

```bash
mvn clean package                                   # 产出 app.jar
# 将 app.jar 与精简 JRE 放入 src-tauri/resources/
cd frontend
pnpm tauri build                                    # 打包桌面安装程序
```

桌面模式下后端由 Tauri 拉起并绑定随机端口，随应用退出自动终止，无需手动管理。

### 生产部署

```bash
cd frontend && pnpm build      # 产物在 frontend/dist/
cd .. && mvn clean package     # 产物 target/kafka-visualizer-1.0.0.jar
```

前端静态文件用 Nginx/CDN 部署，反向代理 `/api` 到后端 JAR；或手动将 `dist/*` 复制到 `src/main/resources/static/` 后打包为单 JAR。

## 常用命令

| 位置 | 命令 | 说明 |
|------|------|------|
| 根目录 | `mvn spring-boot:run` | 启动后端 |
| 根目录 | `mvn test -Dtest=TopicApiIT` | 运行指定集成测试 |
| `frontend/` | `pnpm dev` / `pnpm build` | 开发服务器 / 类型检查+构建 |
| `frontend/` | `pnpm test` | 前端单元测试 |
| `frontend/` | `pnpm tauri build` | 打包桌面版 |

> 后端测试全部为 `*IT.java` 后缀，必须 `-Dtest=` 显式指定（`mvn test` 裸跑为 0 个测试属正常）。测试基于内嵌真实 Kafka + ZK，单个测试类耗时以分钟计。

## 注意事项

- 切勿将真实 broker 地址/凭据写入 `application.yml`（会进入 git 历史），统一在 UI 中管理
- 删除 Topic / 删除消费组操作不可逆
- `data/` 目录包含本地数据库与加密密钥（`.key`），迁移或备份时需一并保存

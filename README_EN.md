# Kafka Visualizer

<p align="right">
  English | <a href="README.md">简体中文</a>
</p>

A multi-cluster Kafka visualization and management tool. Supports cluster registration & connection management, dashboard overview, topic management, message query & production, consumer group monitoring, message archiving, and ZooKeeper browsing, with SASL / mTLS / OAuth and more authentication methods. It can be deployed as a web application or packaged as a desktop application (Electron) to run locally.

## Features

- **Multi-cluster management** — Add, edit, delete clusters in the UI, connect/disconnect, test connections; credentials stored encrypted
- **Multiple authentication methods** — No auth, SASL (PLAIN / SCRAM), mTLS (PEM certificates), OAuth2, custom properties
- **Topic management** — List, create, delete, partition/replica/ISR/offset details, add partitions, config changes
- **Message query & production** — Browse by partition/offset/timestamp, JSON pretty-print, message production
- **Consumer group monitoring** — List, details, lag monitoring, offset reset
- **Message archiving** — Persist messages to database (SQLite / PostgreSQL / MySQL), query back by time range
- **ZooKeeper browser** — Node tree browsing and management for ZK-mode clusters
- **Dashboard** — Single-cluster overview + multi-overview across all clusters
- **Internationalization** — One-click switch between 中文 / English (vue-i18n, choice persisted locally)
- **Favorites** — Star topics / consumer groups, scoped per cluster, works offline
- **Scheduled inspection & multi-channel alerting** — Define per-cluster inspection rules
  (metric + threshold + optional time window + per-rule Cron); notify via in-app / email / webhook /
  IM bots (DingTalk / Feishu / WeCom). State de-duplication + recovery notifications,
  a cross-cluster alert center with acknowledgement, and an unread badge in the header
- **In-site notification center** — Top-bar bell with an unread badge and a recent notifications panel;
  mark as read individually or in bulk; click through to alert details in the alert center

## Architecture

Frontend-backend separated architecture; the backend talks to Kafka directly (no spring-kafka dependency):

```
┌─────────────────────┐        REST /api          ┌──────────────────────────────┐
│      Frontend        │ ────────────────────────▶ │        Backend (Spring Boot)  │
│  Vue 3 + Element Plus│ ◀──────────────────────── │                              │
│      (Vite, pnpm)    │   Unified JSON envelope   │  controller ──▶ service      │
└─────────────────────┘                           │       │            │         │
                                                  │       ▼            ▼         │
                                                  │  kafka/          zk/         │
                                                  │  AdminClient     Curator     │
                                                  │  Consumer pool   (ZK mode)   │
                                                  │       │                      │
                                                  │       ▼                      │
                                                  │  storage/  ◀── archive/      │
                                                  │  SQLite / PG / MySQL         │
                                                  └──────────────────────────────┘
```

**Backend modules** (`src/main/java/com/example/kafkaviz/`):

| Module | Responsibility |
|--------|----------------|
| `controller/` | REST controllers; business endpoints are mounted under the `/api/c/{clusterId}` segment |
| `service/` | Business logic |
| `kafka/` | AdminClient management, consumer connection pool (commons-pool2, one pool per cluster) |
| `zk/` | ZooKeeper browsing (Curator) |
| `inspection/` | Scheduled inspection engine: rule scheduling, metric collection, threshold evaluation, multi-channel alert dispatch |
| `archive/` | Message archive pipeline (one archive table per cluster) |
| `storage/` | Three storage dialects (SQLite/PG/MySQL), cluster config store, schema migration |
| `security/` | Credential encryption/decryption |
| `config/` · `exception/` · `model/` · `web/` | Configuration, error codes, DTO/VO, web support |

**Key design decisions**:
- Cluster connection parameters are stored in a local database (managed via the UI); `application.yml` only provides first-start seed values and global defaults
- Message-query consumers use `assign()` for manual partition assignment with a random `group.id` — **no impact** on real consumer groups
- The storage backend can be switched in the UI (stored in `storage.json` under the app-data directory, `data/` by default), effective after restart
- Idle cluster connections are reclaimed automatically and rebuilt on demand
- In desktop mode, `--app.db-path` points the database and key at the OS user-data directory, isolated from the repo tree

## Tech Stack

**Backend**
- Spring Boot 3.4.6 / Java 21 / Maven
- `kafka-clients` 3.9.0 — direct Kafka client
- `curator-framework` — ZooKeeper client
- `commons-pool2` — consumer connection pool
- `spring-boot-starter-mail` — SMTP channel for inspection alerts
- `caffeine` — local caching
- `sqlite-jdbc` / `postgresql` / `mysql-connector-j` — storage backends

**Frontend**
- Vue 3.5 + TypeScript / Vite 6 / Element Plus / Vue Router 4 / vue-i18n 11 / Axios / Vitest 4
- Package manager: pnpm 12

**Desktop**
- Electron 45 + electron-builder 26 — the main-process shell serves the frontend over a local static server and spawns the backend sidecar (`app.jar` + jlink-trimmed JRE), reaping it on exit

## Getting Started

### Prerequisites

- JDK 21+, Maven 3.8+
- Node.js 18+, pnpm 12+ (`npm i -g pnpm`)

### Web Mode

**1. Start the backend** (repo root):

```bash
mvn spring-boot:run        # http://localhost:8080
```

**2. Start the frontend** (`frontend/` directory):

```bash
pnpm install    # first time
pnpm dev:web    # http://localhost:5173, /api auto-proxied to 8080
```

Open `http://localhost:5173` in your browser. On first visit, the page will guide you through adding a Kafka cluster (fill in the address and auth method; you can test the connection first). Cluster configurations are stored in a local database (SQLite by default, under `data/`), with credentials encrypted automatically.

> Unattended deployment: set the environment variable `KAFKA_BOOTSTRAP_SERVERS`. When the cluster table is empty, the first startup automatically imports it as the first cluster record.

### Desktop (Electron)

The desktop shell lives in `frontend/electron/` (Electron main process + preload). **No Rust toolchain required.**
Before packaging, prepare the sidecar artifacts at the repo-root `build/resources/`: `app.jar` (the artifact of
`mvn clean package`) and `runtime/` (a jlink-trimmed JRE). Missing either one will cause the build to fail:

```bash
mvn clean package                                   # produces app.jar
mkdir -p build/resources
cp target/kafka-visualizer-1.0.0.jar build/resources/app.jar
jlink --no-header-files --no-man-pages --strip-debug --compress=zip-6 \
  --add-modules java.se,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata,jdk.crypto.ec \
  --output build/resources/runtime                  # produces the trimmed JRE
cd frontend
pnpm install
pnpm dist                                           # package for the current platform; or dist:win / dist:mac / dist:linux
```

Artifacts land in `frontend/release/`. Architectures are fixed: x64 only on Windows / Linux, arm64 only on macOS (matching the jlink runtime).

In desktop mode, the backend is launched by the main process as
`java -jar app.jar --server.port=0 --announce-port --app.db-path=<userData>`, and the backend completes a
handshake by printing `PORT=<port>` on stdout. The database and key live in the OS user-data directory
(not the repo `data/`) and the backend terminates automatically when the app exits — no manual management needed.

For development, `pnpm dev` boots the desktop shell in one shot (compiles the main process + vite dev server +
Electron window) without sidecar resources; `/api` goes through the vite proxy — run `mvn spring-boot:run`
in a separate terminal for the backend.

### Production Deployment

```bash
cd frontend && pnpm build      # output in frontend/dist/ (web) and frontend/dist-electron/ (desktop main process)
cd .. && mvn clean package     # output target/kafka-visualizer-1.0.0.jar
```

Deploy the frontend static files with Nginx/CDN and reverse-proxy `/api` to the backend JAR; or manually copy `dist/*` into `src/main/resources/static/` and package everything as a single JAR.

## Common Commands

| Location | Command | Description |
|----------|---------|-------------|
| Repo root | `mvn spring-boot:run` | Start the backend (web mode, :8080) |
| `frontend/` | `pnpm dev:web` | Start the frontend web dev server (:5173, /api proxied to 8080) |
| `frontend/` | `pnpm dev` | Desktop shell dev mode (vite + Electron in one window) |
| `frontend/` | `pnpm test` | Frontend unit tests (Vitest) |
| `frontend/` | `pnpm build` | Typecheck + build (dist/ and dist-electron/) |
| `frontend/` | `pnpm dist:win` / `dist:mac` / `dist:linux` | Package the desktop installer |

> The repo currently ships no backend test code (`src/test/` is empty), so a bare `mvn test` yields 0 tests — this is normal. Frontend tests use Vitest (`__tests__/` and `*.test.ts`).

## Caveats

- Deleting a topic / deleting a consumer group is irreversible
- The app-data directory (`data/` for bare runs, the OS user-data directory for the desktop app) contains the local database and the encryption key (`secret.key`); keep both together when migrating or backing up

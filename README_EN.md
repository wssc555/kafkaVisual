# Kafka Visualizer

<p align="right">
  English | <a href="README.md">简体中文</a>
</p>

A multi-cluster Kafka visualization and management tool. Supports cluster registration & connection management, dashboard overview, topic management, message query & production, consumer group monitoring, message archiving, and ZooKeeper browsing, with SASL / mTLS / OAuth and more authentication methods. It can be deployed as a web application or packaged as a desktop application (Tauri) to run locally.

## Features

- **Multi-cluster management** — Add, edit, delete clusters in the UI, connect/disconnect, test connections; credentials stored encrypted
- **Multiple authentication methods** — No auth, SASL (PLAIN / SCRAM), mTLS (PEM certificates), OAuth2, custom properties
- **Topic management** — List, create, delete, partition/replica/ISR/offset details, add partitions, config changes
- **Message query & production** — Browse by partition/offset/timestamp, JSON pretty-print, message production
- **Consumer group monitoring** — List, details, lag monitoring, offset reset
- **Message archiving** — Persist messages to database (SQLite / PostgreSQL / MySQL), query back by time range
- **ZooKeeper browser** — Node tree browsing and management for ZK-mode clusters
- **Dashboard** — Single-cluster overview + multi-overview across all clusters

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
| `archive/` | Message archive pipeline (one archive table per cluster) |
| `storage/` | Three storage dialects (SQLite/PG/MySQL), cluster config store, schema migration |
| `security/` | Credential encryption/decryption |
| `config/` · `exception/` · `model/` · `web/` | Configuration, error codes, DTO/VO, web support |

**Key design decisions**:
- Cluster connection parameters are stored in a local database (managed via the UI); `application.yml` only provides first-start seed values and global defaults
- Message-query consumers use `assign()` for manual partition assignment with a random `group.id` — **no impact** on real consumer groups
- The storage backend can be switched in the UI (stored in `storage.json`), effective after restart
- Idle cluster connections are reclaimed automatically and rebuilt on demand

## Tech Stack

**Backend**
- Spring Boot 3.4.6 / Java 21 / Maven
- `kafka-clients` 3.9.0 — direct Kafka client
- `curator-framework` — ZooKeeper client
- `commons-pool2` — consumer connection pool
- `caffeine` — local caching
- `sqlite-jdbc` / `postgresql` / `mysql-connector-j` — storage backends

**Frontend**
- Vue 3 + TypeScript / Vite 5 / Element Plus / Vue Router 4 / Axios / Vitest
- Package manager: pnpm 12

**Desktop**
- Tauri 2 — the backend JAR + a trimmed JRE are shipped as a sidecar with the app; the frontend is bundled into the WebView

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
pnpm dev        # http://localhost:5173, /api auto-proxied to 8080
```

Open `http://localhost:5173` in your browser. On first visit, the page will guide you through adding a Kafka cluster (fill in the address and auth method; you can test the connection first). Cluster configurations are stored in a local database (SQLite by default, under `data/`), with credentials encrypted automatically.

> Unattended deployment: set the environment variable `KAFKA_BOOTSTRAP_SERVERS`. When the cluster table is empty, the first startup automatically imports it as the first cluster record.

### Desktop (Tauri)

Requires an additional Rust toolchain. Before `tauri build`, prepare the sidecar artifacts in `src-tauri/resources/`:
`app.jar` (the artifact of `mvn clean package`) and `runtime/` (a jlink-trimmed JRE). Missing either one will cause the build to fail:

```bash
mvn clean package                                   # produces app.jar
# Place app.jar and the trimmed JRE into src-tauri/resources/
cd frontend
pnpm tauri build                                    # package the desktop installer
```

In desktop mode, the backend is launched by Tauri and bound to a random port; it terminates automatically when the app exits — no manual management needed.

### Production Deployment

```bash
cd frontend && pnpm build      # output in frontend/dist/
cd .. && mvn clean package     # output target/kafka-visualizer-1.0.0.jar
```

Deploy the frontend static files with Nginx/CDN and reverse-proxy `/api` to the backend JAR; or manually copy `dist/*` into `src/main/resources/static/` and package everything as a single JAR.

## Common Commands

| Location | Command | Description |
|----------|---------|-------------|
| Repo root | `mvn spring-boot:run` | Start the backend |
| Repo root | `mvn test -Dtest=TopicApiIT` | Run a specific integration test |
| `frontend/` | `pnpm dev` / `pnpm build` | Dev server / typecheck + build |
| `frontend/` | `pnpm test` | Frontend unit tests |
| `frontend/` | `pnpm tauri build` | Package the desktop app |

> All backend tests use the `*IT.java` suffix and must be specified explicitly via `-Dtest=` (running bare `mvn test` yields 0 tests — this is normal). Tests run against a real embedded Kafka + ZK; a single test class takes minutes.

## Caveats

- Never write real broker addresses/credentials into `application.yml` (it would end up in git history); manage everything through the UI
- Deleting a topic / deleting a consumer group is irreversible
- The `data/` directory contains the local database and the encryption key (`.key`); keep both together when migrating or backing up

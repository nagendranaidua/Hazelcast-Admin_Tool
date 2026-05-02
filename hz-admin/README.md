# Hazelcast Admin

Multi-cluster monitoring & administration for Hazelcast 4.2.x / 5.1.x / 5.2.x.
Open-source replacement for Hazelcast Management Center, scoped to the operations
real teams actually need: register clusters, see members, browse Maps/Queues/Topics,
query with Hazelcast SQL, perform lifecycle ops via SSH, role-based access.

## Status

- **Phase 1 (done)** — End-to-end runnable with database-backed authentication, four
  predefined roles & users, cluster registry, member listing, and OpenAPI/Swagger UI.
- **Phase 2 (done)** — Hazelcast 4.x bridge added (isolated classloaders so 4.x and 5.x
  coexist in one backend); cluster dashboard with partition-safety; real-time member
  events via Server-Sent Events; in-memory time-series metrics + chart UI.
- **Phase 3 (done)** — IMap browse with paging stats, key lookup, entry edit/delete with
  mandatory reason and full before/after audit, SQL console with two pagination modes
  (server-streamed cursor and auto-LIMIT/OFFSET), and the audit-log viewer.
- **Phase 4-5** — see roadmap below.

## Architecture in one paragraph

Spring Boot 2.7.18 backend on JDK 8 packaged as a single fat JAR. PostgreSQL holds
users, roles, the cluster registry, encrypted cluster credentials, audit log, saved
queries, and alert rules. The backend talks to monitored Hazelcast clusters through
the **Bridge SPI** — a tiny interface in `hz-bridge-spi` whose two implementations
(`hz-bridge-4x` against `hazelcast-client:4.2.8`, `hz-bridge-5x` against
`hazelcast-client:5.2.5`) ship as self-contained shaded JARs. At runtime each is
loaded into its own `URLClassLoader` so their incompatible `com.hazelcast.*`
classes don't collide. Apache HTTPD reverse-proxies a static React 18 + TypeScript
build (Vite + MUI + TanStack Query + recharts) to `/` and forwards `/api/*` to the JAR.

## Version matrix

| Component         | v1 (now)              | v2 (JDK 11)        | v3 (5.3+)         |
|-------------------|-----------------------|--------------------|-------------------|
| JDK               | 8 (Temurin 8u402+)    | 11                 | 17                |
| Spring Boot       | 2.7.18                | 2.7.18             | 3.3.x             |
| Hazelcast clients | 4.2.8 + 5.2.5         | same               | + 5.5.x           |
| Supported clusters| HZ 4.2.x, 5.1.x, 5.2.x| same               | + HZ 5.3.x – 5.5.x|
| Postgres driver   | 42.7.x                | same               | same              |
| Flyway            | 9.22.x                | same               | 10.x              |

Adding a future Hazelcast 6.x line means dropping a new `hz-bridge-6x-*.jar` into
the runtime `bridges/` directory and registering clusters with `majorVersion: 6.x`.
No backend code change required.

## Repository layout

```
hz-admin/
├── pom.xml                 (parent — pins all versions; declares shade-plugin pluginMgmt)
├── shared-api/             pure-Java DTOs (no Spring, no Hazelcast)
├── hz-bridge-spi/          interface seam (no Hazelcast deps)
├── hz-bridge-4x/           HZ 4.x impl, hazelcast-client 4.2.8, shaded
├── hz-bridge-5x/           HZ 5.x impl, hazelcast-client 5.2.5, shaded
├── backend/                Spring Boot fat JAR (deliberately has NO hazelcast dep)
└── frontend/               React 18 + TS + Vite + MUI + recharts
```

## How the isolated-classloader bridge loading works

1. `mvn package` builds three JARs that matter at runtime:
   - `backend/target/hz-admin-backend.jar` — Spring Boot fat-jar
   - `hz-bridge-4x/target/hz-bridge-4x-1.0.0-SNAPSHOT.jar` — shaded with hazelcast 4.2.8
   - `hz-bridge-5x/target/hz-bridge-5x-1.0.0-SNAPSHOT.jar` — shaded with hazelcast 5.2.5
2. The two bridge JARs are dropped into `${hz-admin.bridge.dir}` (default `./bridges/`).
3. On startup, `BridgeRegistry` scans that directory, builds one `URLClassLoader` per JAR
   with the application classloader as parent, and runs
   `ServiceLoader.load(HazelcastBridgeFactory.class, child)` on each.
4. SPI types (`HazelcastBridge`, `HazelcastBridgeFactory`, `BridgeConnectConfig`,
   `MemberInfo`, …) are *excluded* from the shaded jars and load from the parent CL,
   guaranteeing type identity across the seam.
5. When a cluster is registered with `majorVersion: 4.2`, `BridgeRouter` looks up the
   `"4"` factory; for `5.1`/`5.2`, the `"5"` factory. Each cluster's bridge instance is
   cached for `idle-eviction-min` minutes, then closed.

## Default seed users

V2 migration creates four users, one per role. All have `must_change_password=true`
and the app blocks every endpoint except `/api/auth/*` until rotated.

| Username     | Temporary password    | Role               |
|--------------|-----------------------|--------------------|
| `superadmin` | `ChangeMe!Admin#1`    | `SUPER_ADMIN`      |
| `operator`   | `ChangeMe!Op#1`       | `CLUSTER_OPERATOR` |
| `developer`  | `ChangeMe!Dev#1`      | `DEVELOPER`        |
| `viewer`     | `ChangeMe!View#1`     | `READ_ONLY`        |

`SeedUserBootstrap` re-hashes these with the configured bcrypt strength on first
boot if the SQL placeholder hashes are wrong, so the credentials above always work
in dev.

## Prerequisites

- JDK 8 (Temurin 8u402+ recommended)
- Maven 3.8+
- Node 20+ (frontend build only — runtime is just static files)
- PostgreSQL 12+ (or `docker compose up -d postgres`)
- Optional: Docker, to spin up Hazelcast 4.2 and/or 5.2 members for end-to-end testing

## Build everything

```bash
cd hz-admin
mvn -DskipTests clean package
# Outputs:
#   backend/target/hz-admin-backend.jar
#   hz-bridge-4x/target/hz-bridge-4x-1.0.0-SNAPSHOT.jar
#   hz-bridge-5x/target/hz-bridge-5x-1.0.0-SNAPSHOT.jar
```

## Run it (dev)

```bash
# 1. Postgres (and optionally a sample HZ 5.2 cluster)
cd hz-admin
docker compose up -d postgres hazelcast

# 2. Stage the bridge jars where the backend will look for them
mkdir -p bridges
cp hz-bridge-4x/target/hz-bridge-4x-*.jar bridges/
cp hz-bridge-5x/target/hz-bridge-5x-*.jar bridges/

# 3. Backend
export ADMIN_KEK="$(head -c 32 /dev/urandom | base64)"   # any string >= 1 char OK in dev
export JWT_SECRET="$(head -c 48 /dev/urandom | base64)"  # MUST be >= 32 bytes
mvn -pl backend -am spring-boot:run
# Look for log line: "BridgeRegistry initialised with majors=[4, 5]"

# 4. Frontend (separate terminal)
cd frontend
npm install
npm run dev
# visit http://localhost:5173
```

Vite proxies `/api`, `/sse`, `/api-docs`, and `/swagger-ui` to `localhost:8080`,
so dev is single-origin from the browser's perspective.

## Manual test plan

Run through this against a fresh DB to validate end-to-end across both Phase 1 and 2.

### Phase 1 acceptance

1. Open http://localhost:5173 and log in as `superadmin / ChangeMe!Admin#1`.
2. You should be redirected to the change-password screen (length policy ≥ 12 chars).
   Set a new password and verify you land on the dashboard.
3. Sign out, log in as each of the other three users in turn, rotate each password,
   verify the **left-nav menu items match the role**:
    - `superadmin` sees Dashboard / Clusters / Audit / Users.
    - `operator`, `developer`, `viewer` do **not** see Users.
4. As `superadmin`, register a 5.x cluster on the Clusters page using the optional
   docker compose member: name `local-5x`, environment `DEV`, hzClusterName `dev`,
   majorVersion `5.2`, addresses `127.0.0.1:5701`, security `PLAIN`. Click "Test"
   — you should see `connected: true` and `memberCount: 1`.
5. As `viewer`, attempt to register a cluster — the button should not be visible
   and POST `/api/clusters` returns 403.
6. Visit http://localhost:8080/swagger-ui.html — Swagger should load and show
   tags Auth / Token / Users / Clusters / Members / ClusterOverview / MemberEvents / Metrics.

### Phase 2 acceptance

7. **Two majors at once.** Spin up an HZ 4.2 server (`docker run --rm -p 5703:5701
   hazelcast/hazelcast:4.2.8`). Register a second cluster: name `local-4x`,
   majorVersion `4.2`, addresses `127.0.0.1:5703`. Both `local-5x` and `local-4x`
   should show `connected: true` from the same backend instance — proves the
   isolated-classloader split works.
8. **Cluster dashboard.** Click "Overview" on either cluster. You should see
   cluster state, member count, partition count (271 by default), and a green
   "Migration safe: YES" chip. The page auto-refreshes every 10s.
9. **SSE member events.** Click "Members" — the conn indicator should flip to a
   green "live" chip within 1-2s (no 5s polling). With the page open, stop the
   docker container; within ~10s a `member-removed` event appears in the event log
   and the chip flips to "reconnecting". Restart the container — `member-added`
   logged within ~5s.
10. **Metrics charts.** Click "Metrics". Wait 30-45s for the collector to gather
    a few samples. Charts should populate: Member count (step), Bridge round-trip
    latency (line), Connectivity (1/0 step). Switch the time-window toggle
    (5m/15m/1h) and watch the X-axis re-window. Stop the docker container — the
    Connectivity chart should drop to 0 within 15-30s and uptime % chip drops.

### Phase 3 acceptance

11. **Maps list.** From any client (e.g. `hz-cli`, a quick `IMap<String,String>` Java
    snippet, or `docker exec` into the HZ container and use the embedded console)
    write a few entries to a map called `users`. In the UI click **Maps** on the
    cluster card. The `users` map should appear within 30s.
12. **Browse.** Click `users` — the entries table should load with key/value/type/bytes
    columns and the stats chips (entries / heap / hits / puts / gets / removes) populated
    from `LocalMapStats`. Page Next/Prev — the order is stable across page loads.
13. **Key lookup.** Type a known key into the **Look up by key** box and click Find.
    The value should appear in the JSON preview. If you logged in as `op` or `admin`,
    Edit and Delete buttons appear; if you logged in as `viewer`, they don't.
14. **Edit with audit.** As `admin`, click Edit, change the value, leave the reason
    blank → Save is disabled. Type a reason → Save. The page refreshes; the new value
    is visible. Visit **Audit Log** in the left nav (only `admin` sees it). The new
    `MAP_PUT` row is at the top with `outcome: SUCCESS`, the reason you typed, and
    before/after JSON in the Detail column.
15. **PROD guard.** Edit the cluster registration to set `prod: true` (via
    `PUT /api/clusters/{id}` or directly in the DB for now). As `op`
    (CLUSTER_OPERATOR), Edit/Delete should now return 403 with a clear error.
    As `admin` (SUPER_ADMIN), it still works.
16. **SQL console — Stream mode.** Click **SQL** on the cluster card. Run
    `SELECT __key, this FROM "users"`. Mode `Stream`, page size 10. The first 10 rows
    appear, then **Fetch next 10** keeps appending. When the result set is exhausted,
    a green `done` chip shows. Hit **Close cursor** before exhaustion to test cursor
    cleanup; the cursor disappears from the SQL_CURSOR table.
17. **SQL console — LIMIT mode.** Switch the toggle to LIMIT/OFFSET, page size 10,
    same query. Each Fetch next re-runs the query with `LIMIT 10 OFFSET ((page+1)*10)`;
    pages still scroll forward but each fetch is independent.
18. **SQL audit.** Every executed query is recorded in the audit log with action
    `SQL_QUERY`, target = mode + page size, error_message = row count + elapsed ms.
    A failing query (e.g. typo) is recorded with `outcome: FAILED` and the error.
19. **4.x SQL on a pre-4.2 cluster.** If you have an HZ 4.0 or 4.1 lying around,
    register it and try the SQL console. The query should fail with a friendly
    "SQL is not supported on Hazelcast clusters older than 4.2" — the error
    surfaces verbatim through the audit row.

## Production deployment (VM, behind Apache HTTPD)

Build artifacts:

```bash
mvn -DskipTests clean package
cd frontend && npm ci && npm run build     # frontend/dist/
```

Install on the VM:

```
/opt/hz-admin/
├── hz-admin-backend.jar
├── application.yml          (overrides; references env vars below)
├── bridges/                 ← drop hz-bridge-Nx-*.jar files here
│   ├── hz-bridge-4x-1.0.0-SNAPSHOT.jar
│   └── hz-bridge-5x-1.0.0-SNAPSHOT.jar
└── ui/                      (contents of frontend/dist)
```

Required environment for the JAR (set in `/etc/systemd/system/hz-admin.service`):

```
HZ_ADMIN_PROFILE=prod
HZ_ADMIN_BRIDGE_DIR=/opt/hz-admin/bridges
ADMIN_KEK=...                                 # >= 32-byte secret. Rotation rotates all encrypted creds.
JWT_SECRET=...                                # >= 32-byte secret
DB_URL=jdbc:postgresql://db.internal:5432/hzadmin
DB_USER=hzadmin
DB_PASSWORD=...
COOKIE_SECURE=true
CORS_ORIGINS=https://hzadmin.your-corp
```

Apache HTTPD vhost (sketch — note the SSE-specific tweaks):

```apache
<VirtualHost *:443>
    ServerName hzadmin.your-corp
    SSLEngine on
    SSLCertificateFile      /etc/ssl/hzadmin.crt
    SSLCertificateKeyFile   /etc/ssl/hzadmin.key

    DocumentRoot /opt/hz-admin/ui

    # SPA fallback
    <Directory "/opt/hz-admin/ui">
        Options -Indexes +FollowSymLinks
        AllowOverride None
        Require all granted
        FallbackResource /index.html
    </Directory>

    # API + SSE go to Spring Boot
    ProxyPreserveHost On
    ProxyRequests     Off

    # SSE endpoint must NOT be buffered: flushpackets=on disables mod_proxy buffering
    # so server-sent events stream live to the browser. The members/events route is
    # under /api/, so we declare it BEFORE the catch-all /api/ rule.
    <Location "/api/clusters/">
        ProxyPass        http://127.0.0.1:8080/api/clusters/  flushpackets=on
        ProxyPassReverse http://127.0.0.1:8080/api/clusters/
        # Long-running streams; relax timeouts
        ProxyTimeout 3600
    </Location>

    ProxyPass        /api/      http://127.0.0.1:8080/api/
    ProxyPassReverse /api/      http://127.0.0.1:8080/api/
    ProxyPass        /actuator/ http://127.0.0.1:8080/actuator/
    ProxyPassReverse /actuator/ http://127.0.0.1:8080/actuator/
</VirtualHost>
```

`server.forward-headers-strategy=framework` (already in `application.yml`) makes
Spring honor `X-Forwarded-*` from Apache so cookies are marked `Secure` correctly.

To take the app down without stopping the JVM, point Apache to a maintenance
DocumentRoot (or use `mod_proxy_balancer` with the JAR drained out of rotation).

## Adding a future Hazelcast major version

1. Create a new module `hz-bridge-6x` mirroring `hz-bridge-4x` / `hz-bridge-5x`:
   POM with the new `hazelcast` version inline + the same `maven-shade-plugin` block;
   a `HazelcastBridge6xFactory` returning `"6"` from `supportedMajorVersion()`;
   a `HazelcastBridge6x` implementing the SPI methods against the new client API.
2. Add `<module>hz-bridge-6x</module>` to the parent POM.
3. `mvn package` and copy the resulting shaded JAR to `/opt/hz-admin/bridges/`.
4. Restart the backend. `BridgeRegistry` log line should now show `majors=[4, 5, 6]`.
5. Register clusters with `majorVersion: 6.0` (or whatever).

No change to backend, frontend, or SPI required, provided the SPI surface is sufficient
for the new client API.

## Phase 3 endpoint reference

The map browse / key-lookup / edit / SQL console endpoints round out the API surface that
the UI uses; all are protected by the same session-cookie + CSRF chain as the rest of `/api`.

```
GET    /api/clusters/{id}/maps                                  # list IMap names
GET    /api/clusters/{id}/maps/{name}/stats                     # MapStats from LocalMapStats
GET    /api/clusters/{id}/maps/{name}/entry/browse              # paginated entries
                                                                 #   ?pageIndex=0&pageSize=50&includeValues=true
GET    /api/clusters/{id}/maps/{name}/entry?key=…               # single key lookup
PUT    /api/clusters/{id}/maps/{name}/entry                     # body: {key,value,ttlMs,reason}
                                                                 #   roles: SUPER_ADMIN | CLUSTER_OPERATOR
                                                                 #   prod=true cluster ⇒ SUPER_ADMIN only
DELETE /api/clusters/{id}/maps/{name}/entry                     # body: {key,reason}
                                                                 #   same role rules as PUT

POST   /api/clusters/{id}/sql                                   # body: {query,pageSize,mode,offset?,reason?}
                                                                 #   mode: "STREAM" | "LIMIT"
                                                                 #   STREAM returns cursorId
POST   /api/clusters/{id}/sql/{cursorId}/next                   # body: {pageSize}; only for STREAM mode
DELETE /api/clusters/{id}/sql/{cursorId}                        # release a streaming cursor early

GET    /api/audit                                               # roles: SUPER_ADMIN
                                                                 #   ?page=0&size=50&actor=…&cluster=…
```

Audit semantics: every write op (`MAP_PUT`, `MAP_REMOVE`) and every executed `SQL_QUERY` writes
a row through `AuditService.record(...)` *before* the action runs (PENDING outcome) and is
updated to SUCCESS / FAILED after. Rows survive transactional rollbacks because the audit
service uses `Propagation.REQUIRES_NEW`. The before/after JSON of a map edit is stored in the
audit row's `error_message` text column to avoid a schema migration.

SQL cursor lifecycle: STREAM cursors are reaped both backend-side (`SqlCursorRegistry`,
5-minute idle TTL, owner-checked) and bridge-side (each bridge sweeps idle cursors on every
op). The frontend politely calls `DELETE /api/clusters/{id}/sql/{cursorId}` on unmount, but
forgetting to do so is fine — the reapers will catch it.

## Roadmap

- **Phase 4** — SSH executor (key + username/password), member shutdown, cluster state, rolling-upgrade wizard, CSV export of audit, saved-views UI for the audit feed.
- **Phase 5** — Live topic tail (WebSocket), saved queries UI, alerting (Email/Slack/Teams/webhook/in-app), LDAP & OIDC adapters, Prometheus endpoint, optional persistent metrics store (replaces the in-memory ring buffer).

## Security notes

- All cluster credentials, SSH keys, and HZ keystore passwords are encrypted at rest
  with AES-256-GCM using the `ADMIN_KEK` env var. Rotating `ADMIN_KEK` requires
  re-encrypting via a (forthcoming) `kek-rotate` admin endpoint.
- The `*_cipher` columns are never echoed back to the UI.
- Audit rows are written **before** every privileged action and updated with the
  outcome after — they survive rollbacks because they use `REQUIRES_NEW`.
- Write actions (map put/remove, member shutdown, cluster state change) require
  a non-empty `reason` field in Phase 3+ — Phase 1 records it on cluster register.
- The bridge JARs in `${HZ_ADMIN_BRIDGE_DIR}` should be owned by the same user as
  the JAR and `chmod 0640` to prevent tampering — they execute in the backend's JVM.

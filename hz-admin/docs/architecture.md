# Architecture

This document captures the design decisions made during requirements gathering so
the next contributor can understand *why* the code looks the way it does.

## Cross-version Hazelcast support

Hazelcast 4.x and 5.x clients are wire-incompatible. v1 ships only the 5.x bridge
to keep scope small, but the architecture preserves the option to add 4.x later
**without** rewriting controllers or services.

The seam is `com.genius.hz.bridge.HazelcastBridge` (in `hz-bridge-spi`). The
interface uses only DTOs from `shared-api` — **no Hazelcast types appear in the SPI**.
Implementations are discovered via `java.util.ServiceLoader` from
`META-INF/services/com.genius.hz.bridge.HazelcastBridgeFactory`. `BridgeRouter`
picks the factory whose `supportedMajorVersion()` matches the registered cluster's
`major_version` column.

When 4.x support comes back:

1. Create `hz-bridge-4x` with `hazelcast-client 4.2.x`. Same SPI, same SPI version.
2. The factory must be loaded in a **child URLClassLoader** to avoid clashing with
   `hz-bridge-5x` classes. Add a small `BridgeFactoryLoader` that scans
   `${HZ_BRIDGE_DIR}/hz-bridge-4x.jar` and creates an isolated loader. The
   `ServiceLoader` call in `BridgeRouter.init()` then becomes
   `ServiceLoader.load(HazelcastBridgeFactory.class, isolatedLoader)`.
3. Add `"4.x"` as a permitted value in the cluster registration form.

No controller or service code changes.

## Authentication

Two independent `SecurityFilterChain` beans, ordered:

1. **`/api/v1/**`** — stateless JWT bearer, for OpenAPI consumers and CI scripts.
   No CSRF, no session. JWT signed with HS256 using `JWT_SECRET` (must be ≥ 32 bytes).
2. **everything else** — session cookie + CSRF (cookie strategy so React can
   read the token from `XSRF-TOKEN` and echo `X-XSRF-TOKEN`). Browser users
   never see a JWT.

`AuthenticationProvider` chain is `@Order`-driven. v1 contains only
`DaoAuthenticationProvider`. Future LDAP / OIDC providers (Phase 5) are added in
their own `@Configuration` classes guarded by `@ConditionalOnProperty
(prefix="hz-admin.security.auth-providers", name={"ldap","oidc"})` — no edits to
`SecurityConfig` required.

## RBAC

Authorities prefix is `ROLE_`. Roles enforced via method-level `@PreAuthorize`:

- `SUPER_ADMIN` — every operation.
- `CLUSTER_OPERATOR` — register/test/delete clusters, change cluster state, member
  ops, no user mgmt.
- `DEVELOPER` — read all data, run queries, edit map entries by key.
- `READ_ONLY` — view metrics, structure listings, no entry values.

The frontend checks roles in two places: route guards in `App.tsx` (don't even
mount the page) and conditional rendering of action buttons in `AppShell.tsx` /
each page (don't tempt the user). The backend is the source of truth — UI
hiding is convenience, not security.

## Encryption-at-rest

`CryptoService` uses AES-256-GCM with a 12-byte IV and 128-bit auth tag. The KEK
is supplied via `ADMIN_KEK`, stretched to 256 bits via SHA-256 so any input
length is acceptable (a default constant is shipped for dev — `isUsingDefaultKek()`
returns `true` and the README warns).

Encrypted columns: `clusters.password_cipher`, `clusters.truststore_pwd_cipher`,
`clusters.keystore_pwd_cipher`, `ssh_hosts.private_key_cipher`,
`ssh_hosts.passphrase_cipher`, `ssh_hosts.password_cipher`. Plaintext exists
only in memory for the duration of a `BridgeConnectConfig`, which is built
inside `BridgeRouter.bridgeFor()` and discarded after the client connects.

## Audit

`AuditService.record()` writes a row with `outcome=PENDING` **before** the
privileged op runs, and `complete()` updates with `SUCCESS`/`FAILED`. Both
methods use `REQUIRES_NEW` so the row survives even if the caller's transaction
rolls back. Every row carries actor, role, cluster, action, target,
optional reason text (mandatory for write ops in Phase 3+), SHA-256 of the
request payload, and source IP.

Retention is forever in Postgres. CSV export (Phase 4) streams from the table.

## Connection pool & resilience

`BridgeRouter` keeps a Caffeine cache of `HazelcastBridge` instances keyed by
cluster id with `expireAfterAccess=10m` (configurable). On idle eviction, the
removal listener calls `bridge.close()` which shuts down the underlying client.

Each call into a bridge from a service is wrapped (Phase 2) in a Resilience4j
circuit breaker named `hz-bridge-{clusterId}` with a 50% failure ratio over the
last 20 calls; open state lasts 30s. The UI then shows a "cluster unreachable"
banner instead of timing out request after request.

## Real-time updates

Two transports, by feature:

- **SSE** for cluster overview / member events / metric ticks. One-way, plays
  nicely with Apache `mod_proxy` when `flushpackets=on`. TanStack Query falls
  back to interval polling automatically when the SSE connection cannot be
  established (e.g., behind a strict proxy).
- **WebSocket (STOMP)** for live topic tail in Phase 5. Backpressure matters there.

Phase 1 uses pure polling (5s interval) — the React Query setup is in place.

## Querying

Hazelcast SQL is invoked through `SqlService.execute(SqlStatement)` with
`setTimeoutMillis()` on every statement (default 30s, role-tunable). Pagination
uses `LIMIT n OFFSET cursor` (cursor returned in `QueryResponse.nextCursor`).

When the future 4.x bridge ships, `mapQuery()` translates the same `query` string
through `Predicates.sql()` + `PagingPredicate`. The DTO contract does not change.

## Serialization safety

`HazelcastBridge5x.renderEntry()` never assumes the backend has the value's
domain class. Order of preference: `HazelcastJsonValue` → primitives → Jackson
reflection of any POJO present on the classpath → fall through to a safe
"class+size" view. Writes from the UI go via `HazelcastJsonValue` only — the UI
cannot push raw Java-serialized blobs into a cluster, by design.

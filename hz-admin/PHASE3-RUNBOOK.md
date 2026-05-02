# Phase 3 Acceptance Runbook (Windows / PowerShell)

A practical step-through of the nine Phase 3 tests defined in
[`README.md`](./README.md#phase-3-acceptance), translated to PowerShell and
augmented with seed snippets and the PROD-flag toggle SQL.

> Order is the same as the README. If you already have the dev stack running
> from Phase 2, skip to **Section B**.

---

## A. Build and bring everything up

### A1. Build the JARs

```powershell
cd C:\GeniusNEO\Hazelcast_Admin\hz-admin
mvn -DskipTests clean package
```

Expected outputs:
```
backend\target\hz-admin-backend.jar
hz-bridge-4x\target\hz-bridge-4x-1.0.0-SNAPSHOT.jar
hz-bridge-5x\target\hz-bridge-5x-1.0.0-SNAPSHOT.jar
```

If the build fails, capture the exact Maven output and paste it back — the
likely suspects are Lombok annotation processing, the shade plugin, or a stale
`~/.m2` cache, all of which are quick to fix.

### A2. Stage bridge JARs

```powershell
New-Item -ItemType Directory -Force -Path .\bridges | Out-Null
Copy-Item .\hz-bridge-4x\target\hz-bridge-4x-*.jar .\bridges\
Copy-Item .\hz-bridge-5x\target\hz-bridge-5x-*.jar .\bridges\
```

### A3. Start Postgres + a Hazelcast 5.2 member

```powershell
docker compose up -d postgres hazelcast
```

`docker ps` should show `hzadmin-postgres` and `hzadmin-hz-5x`.

### A4. Backend (PowerShell-flavoured secrets)

```powershell
$env:ADMIN_KEK   = [Convert]::ToBase64String([System.Security.Cryptography.NGCryptoServiceProvider]::GetBytes(32))
$env:JWT_SECRET  = [Convert]::ToBase64String([System.Security.Cryptography.NGCryptoServiceProvider]::GetBytes(48))
mvn -pl backend -am spring-boot:run
```

Watch the logs for:
```
BridgeRegistry initialised with majors=[4, 5]
Started HzAdminApplication in N.NN seconds
```

### A5. Frontend (separate terminal)

```powershell
cd C:\GeniusNEO\Hazelcast_Admin\hz-admin\frontend
npm install
npm run dev
```

Open <http://localhost:5173>, log in as `superadmin / ChangeMe!Admin#1` (rotate
on first login), register the local 5.x cluster if not already registered:

| Field             | Value             |
|-------------------|-------------------|
| name              | `local-5x`        |
| environment       | `DEV`             |
| hzClusterName     | `dev`             |
| majorVersion      | `5.2`             |
| memberAddresses   | `127.0.0.1:5701`  |
| securityMode      | `PLAIN`           |

Click **Test** → expect `connected: true, memberCount: 1`.

---

## B. Seed the `users` map

Phase 3 step 11 expects a populated `users` IMap to exist. Easiest path: use
the new SQL console you're about to test anyway. Open
**Clusters → local-5x → SQL**, mode = `LIMIT/OFFSET`, paste this and click Run:

```sql
CREATE MAPPING "users"
  TYPE IMap
  OPTIONS ('keyFormat'='varchar','valueFormat'='varchar');
```

Then run, in turn:

```sql
INSERT INTO "users" VALUES ('u1','{"name":"alice","email":"alice@x.io"}');
INSERT INTO "users" VALUES ('u2','{"name":"bob","email":"bob@x.io"}');
INSERT INTO "users" VALUES ('u3','{"name":"carol","email":"carol@x.io"}');
INSERT INTO "users" VALUES ('u4','{"name":"dan","email":"dan@x.io"}');
INSERT INTO "users" VALUES ('u5','{"name":"eve","email":"eve@x.io"}');
```

(Reason field can be left blank for SQL — it's optional unless you want it on
the audit row. Each `INSERT` will appear in the audit log as `SQL_QUERY`.)

> Alt path if SQL bootstrap is awkward: `docker exec -it hzadmin-hz-5x bash`
> then `hz-cli sql` and paste the same statements; or a tiny JShell snippet
> with `HazelcastClient.newHazelcastClient(...).getMap("users").put(...)`.

---

## C. Walk through the nine acceptance tests

### Test 11 — Maps list

1. Cluster card → **Maps**.
2. The `users` map should appear within ~30s (`MapsPage` polls every 30s).

**Pass criteria**: `users` row visible, click navigates to the browse page.

### Test 12 — Browse + stats

1. Click `users`.
2. Verify the chips populate: `5 entries`, heap, hits, puts, gets, removes.
3. Click **Next** / **Prev** with `pageSize=50` (5 entries fit on page 0, so
   pagination is best tested with a larger map — see seed loop below for 200
   keys if you want to exercise paging).

**Pass criteria**: stable order across page loads (driven by `PagingPredicate`).

To get more rows for paging, run this in the SQL console (`LIMIT` mode):

```sql
INSERT INTO "users"
  SELECT CONCAT('u', CAST(v AS VARCHAR)),
         CONCAT('{"i":', CAST(v AS VARCHAR), '}')
  FROM TABLE(GENERATE_SERIES(6, 200));
```

### Test 13 — Key lookup + role gating

1. Look up by key → `u3` → **Find** → JSON preview shows
   `{"name":"carol","email":"carol@x.io"}`.
2. Edit / Delete buttons should be visible (you're `superadmin`).
3. Sign out, sign in as `viewer` (rotate password first), revisit — Edit /
   Delete should be hidden.

### Test 14 — Edit with audit

1. Back as `superadmin`, look up `u3` → **Edit**.
2. Change value to `{"name":"carol","email":"carol@updated.io"}`.
3. Leave reason blank → Save is disabled. Type a reason
   (`fix typo in carol's email`) → Save.
4. Browse refreshes → new value visible.
5. **Audit Log** in left nav → top row:
   - action `MAP_PUT`
   - actor `superadmin`
   - cluster `local-5x`
   - reason `fix typo in carol's email`
   - outcome `SUCCESS` (green chip)
   - Detail column shows `before={"name":"carol","email":"carol@x.io"}\nafter={"name":"carol","email":"carol@updated.io"}` (truncated to ~80 chars; hover for full).

### Test 15 — PROD guard

There's no PUT endpoint on `ClusterController` (yet — Phase 4), so flip the
flag in Postgres directly:

```powershell
docker exec -it hzadmin-postgres psql -U hzadmin -d hzadmin -c `
  "UPDATE clusters SET prod = true WHERE name = 'local-5x';"
```

Now:

1. Sign in as `operator` (CLUSTER_OPERATOR).
2. Maps → users → look up `u3` → Edit → Save.
3. Expect a 403 with the message
   `"Writes are blocked on PROD clusters except for SUPER_ADMIN"`.
4. Audit row should show `outcome: DENIED` for `MAP_PUT`.
5. Sign in as `superadmin` → same edit succeeds.

Reset when done:

```powershell
docker exec -it hzadmin-postgres psql -U hzadmin -d hzadmin -c `
  "UPDATE clusters SET prod = false WHERE name = 'local-5x';"
```

### Test 16 — SQL console (Stream)

1. **SQL** on the cluster card → mode `Stream`, page size `10`.
2. Run `SELECT __key, this FROM "users"`.
3. First 10 rows render; **Fetch next 10** appends; repeat until `done` chip
   appears.
4. Run again and click **Close cursor** before exhaustion. The bridge-side
   cursor map should drop the entry; the next fetch returns "cursor expired"
   if you somehow retain the id.

Sanity check the registry from the backend logs / DB — there's no UI for it,
but `SqlCursorRegistry` will log the put/evict on idle.

### Test 17 — SQL console (LIMIT/OFFSET)

1. Toggle to `LIMIT/OFFSET`, page size `10`.
2. Same query → first 10 rows.
3. **Fetch next 10** → re-issues `... LIMIT 10 OFFSET 10`, etc.

**Pass criteria**: each fetch is independent (no cursor lifecycle), pagination
moves forward through the results.

### Test 18 — SQL audit

After running a few queries, open Audit Log:

- Each query → `SQL_QUERY` row.
- Target column = `mode=STREAM,pageSize=10` (or `LIMIT`).
- error_message column = `rows=N, elapsedMs=M` for SUCCESS rows.
- Force a failure: run `SELECT broken syntax`. Audit row outcome = `FAILED`,
  error_message = the verbatim Hazelcast error text.

### Test 19 — 4.x SQL on pre-4.2 cluster (optional)

Only meaningful if you have an HZ 4.0 / 4.1 cluster lying around. Skip
otherwise — the `sqlOrThrow()` guard is already covered by unit-level review.

If you do test it: register the cluster with `majorVersion: 4.2`, open the SQL
console, run any query, expect a friendly error:

> SQL is not supported on Hazelcast clusters older than 4.2.

Audit row captures the same message.

---

## D. Common gotchas

- **`mvn package` fails on Lombok**: ensure `annotationProcessorPaths` resolves
  Lombok 1.18.30 in the parent POM; with JDK 8 + maven-compiler-plugin 3.11+
  you may need `<release>8</release>` removed and `<source>1.8</source>` /
  `<target>1.8</target>` instead.
- **`xxx.put is not a function` in browser**: a stale Vite cache. Stop `npm
  run dev`, delete `frontend\node_modules\.vite`, restart.
- **`SQL is not enabled` on the 5.x cluster**: the 5.2.5 image has SQL on by
  default; if you've overridden config, ensure `hazelcast.sql.enabled: true`.
- **Cursors stay alive after frontend reload**: `SqlConsolePage`'s unmount
  effect closes the cursor, but a hard browser refresh skips React unmount.
  The 5-min idle reaper on both sides cleans these up; safe to ignore.
- **Audit log shows nothing for SQL queries**: confirm the user has
  `SUPER_ADMIN`; `AuditController` is `@PreAuthorize("hasRole('SUPER_ADMIN')")`
  by design.

---

## E. When something fails

Capture the failure as either:

1. Backend stack trace (from the `mvn spring-boot:run` console), or
2. Browser DevTools → Network tab → the failing `/api/...` request →
   "Copy as cURL" (or just paste status + response body), or
3. Audit row screenshot for `outcome: FAILED` rows.

Paste any of those back here and I'll cut a fix.

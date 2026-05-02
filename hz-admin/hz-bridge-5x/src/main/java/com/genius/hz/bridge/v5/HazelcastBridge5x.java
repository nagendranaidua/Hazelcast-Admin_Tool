package com.genius.hz.bridge.v5;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.genius.hz.api.MapBrowsePage;
import com.genius.hz.api.MapEntryView;
import com.genius.hz.api.MapStats;
import com.genius.hz.api.MemberInfo;
import com.genius.hz.api.QueryRequest;
import com.genius.hz.api.QueryResponse;
import com.genius.hz.api.SqlColumnMeta;
import com.genius.hz.api.SqlPage;
import com.genius.hz.bridge.DistributedObjectRef;
import com.genius.hz.bridge.HazelcastBridge;
import com.genius.hz.bridge.MembershipEvent;
import com.hazelcast.cluster.Cluster;
import com.hazelcast.cluster.ClusterState;
import com.hazelcast.cluster.Member;
import com.hazelcast.cluster.MembershipListener;
import com.hazelcast.collection.IList;
import com.hazelcast.collection.IQueue;
import com.hazelcast.collection.ISet;
import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastJsonValue;
import com.hazelcast.map.IMap;
import com.hazelcast.map.LocalMapStats;
import com.hazelcast.multimap.MultiMap;
import com.hazelcast.partition.Partition;
import com.hazelcast.partition.PartitionService;
import com.hazelcast.query.PagingPredicate;
import com.hazelcast.query.Predicates;
import com.hazelcast.replicatedmap.ReplicatedMap;
import com.hazelcast.ringbuffer.Ringbuffer;
import com.hazelcast.sql.SqlColumnMetadata;
import com.hazelcast.sql.SqlResult;
import com.hazelcast.sql.SqlRow;
import com.hazelcast.sql.SqlRowMetadata;
import com.hazelcast.sql.SqlService;
import com.hazelcast.sql.SqlStatement;
import com.hazelcast.topic.ITopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Hazelcast 5.x SPI implementation. Phase 1 implements the operations needed by the
 * cluster-overview / members / map-browse screens. Advanced ops (CP, JCache, hot-restart, WAN)
 * are stubbed and surfaced via UnsupportedOperationException so the UI can disable controls.
 */
public class HazelcastBridge5x implements HazelcastBridge {

    private static final Logger LOG = LoggerFactory.getLogger(HazelcastBridge5x.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Idle TTL for orphaned SQL cursors. Refreshed on every fetch; reaped on access. */
    private static final long SQL_CURSOR_IDLE_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    /** Pre-compiled detector for an existing top-level LIMIT clause; case-insensitive. */
    private static final Pattern HAS_LIMIT = Pattern.compile("(?is).*\\blimit\\b\\s+\\d+.*");

    private final HazelcastInstance hz;
    private final Map<String, UUID> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, OpenCursor> cursors  = new ConcurrentHashMap<>();

    HazelcastBridge5x(HazelcastInstance hz) { this.hz = hz; }

    @Override public String  supportedMajorVersion() { return "5"; }
    @Override public boolean isConnected()           { return hz.getLifecycleService().isRunning(); }

    // ---- Cluster ------------------------------------------------------------
    @Override public String getClusterName()  { return hz.getConfig().getClusterName(); }

    /**
     * Returns the cluster state ({@code ACTIVE}, {@code NO_MIGRATION}, {@code FROZEN},
     * {@code PASSIVE}, or {@code IN_TRANSITION}), or the sentinel {@code "UNKNOWN"} if
     * the running cluster doesn't expose state to clients.
     * <p>
     * {@code Cluster.getClusterState()} is a member-side privileged operation. From a
     * client it works against most open-source 5.x clusters, but can throw
     * {@link UnsupportedOperationException} when:
     * <ul>
     *   <li>the cluster security config doesn't grant the client the
     *       {@code ClusterPermission} needed to read state, or</li>
     *   <li>the cluster runs an OSS variant where cluster-state RPC is disabled, or</li>
     *   <li>a client/server version mix-up rejects the protocol op.</li>
     * </ul>
     * Without this catch, the UOE would surface inside the metrics-collector loop and the
     * cluster-overview controller's broad {@code try { ... } catch (Exception)} block,
     * making a fully-reachable cluster look offline. We log at DEBUG so operators can opt
     * into seeing the underlying cause without log spam.
     */
    @Override
    public String getClusterState() {
        try {
            return hz.getCluster().getClusterState().name();
        } catch (UnsupportedOperationException | IllegalStateException e) {
            LOG.debug("getClusterState() unavailable on this client view: {}", e.toString());
            return "UNKNOWN";
        }
    }

    @Override
    public void changeClusterState(String newState) {
        hz.getCluster().changeClusterState(ClusterState.valueOf(newState));
    }

    @Override
    public List<MemberInfo> listMembers() {
        return hz.getCluster().getMembers().stream().map(this::toInfo).collect(Collectors.toList());
    }

    @Override
    public int getPartitionCount() {
        return hz.getPartitionService().getPartitions().size();
    }

    /**
     * Client-side approximation of {@code PartitionService.isClusterSafe()}.
     * <p>
     * The real {@code isClusterSafe()} is a member-side API — calling it on the client's
     * {@code PartitionService} throws {@code UnsupportedOperationException} because the
     * client doesn't have direct visibility into per-member migration / backup queues.
     * <p>
     * The standard remote heuristic, which is what Hazelcast Management Center itself uses
     * on its Cluster Health card: if every partition has a known owner, partition assignment
     * is complete and there are no in-flight migrations leaving partitions orphaned. That's a
     * sound proxy for "writes are safe right now". It does not detect every transient
     * unsafe state the member-side API would (a backup operation queued but not yet flushed,
     * for example), but it's the strongest signal a remote client can produce without
     * deploying a server-side task to all members.
     * <p>
     * Wrapped in try/catch returning {@code false} so any future API surprise (e.g. some HZ
     * version restricting even partition iteration on a client) degrades gracefully instead
     * of breaking the metrics-collector loop and the cluster-overview page.
     */
    @Override
    public boolean isClusterSafe() {
        try {
            PartitionService ps = hz.getPartitionService();
            Set<Partition> partitions = ps.getPartitions();
            if (partitions.isEmpty()) return false;
            for (Partition p : partitions) {
                if (p.getOwner() == null) return false;
            }
            return true;
        } catch (UnsupportedOperationException | IllegalStateException e) {
            LOG.debug("isClusterSafe() unavailable on this client view: {}", e.toString());
            return false;
        }
    }

    @Override
    public String getClientVersion() {
        // hard-coded to match the artifact this bridge is built against; the registry can
        // also surface the manifest's Bridge-Hz-Version attribute if more dynamism is needed.
        return "5.2.5";
    }

    @Override
    public void shutdownMember(String uuid, boolean force) {
        // From a *client*, we can't directly stop a member without a server-side service.
        // Phase 4 wires this to the SSH executor; for now signal not-yet-supported clearly.
        throw new UnsupportedOperationException(
                "Member shutdown via client API requires server-side cooperation; "
                + "Phase 4 routes this through SSH executor.");
    }

    @Override
    public String subscribeMembershipEvents(Consumer<MembershipEvent> sink) {
        Cluster cluster = hz.getCluster();
        UUID id = cluster.addMembershipListener(new MembershipListener() {
            @Override public void memberAdded(com.hazelcast.cluster.MembershipEvent ev) {
                sink.accept(new MembershipEvent(MembershipEvent.Type.ADDED, Instant.now(), toInfo(ev.getMember())));
            }
            @Override public void memberRemoved(com.hazelcast.cluster.MembershipEvent ev) {
                sink.accept(new MembershipEvent(MembershipEvent.Type.REMOVED, Instant.now(), toInfo(ev.getMember())));
            }
        });
        String key = id.toString();
        subscriptions.put(key, id);
        return key;
    }

    @Override
    public void unsubscribe(String subscriptionId) {
        UUID id = subscriptions.remove(subscriptionId);
        if (id != null) hz.getCluster().removeMembershipListener(id);
    }

    // ---- Distributed objects ------------------------------------------------
    @Override
    public Set<DistributedObjectRef> listDistributedObjects() {
        Set<DistributedObjectRef> out = new TreeSet<>(Comparator.comparing(DistributedObjectRef::getName));
        for (DistributedObject o : hz.getDistributedObjects()) {
            out.add(new DistributedObjectRef(o.getName(), classify(o)));
        }
        return out;
    }

    private DistributedObjectRef.Type classify(DistributedObject o) {
        if (o instanceof IMap)          return DistributedObjectRef.Type.IMAP;
        if (o instanceof IQueue)        return DistributedObjectRef.Type.QUEUE;
        if (o instanceof ITopic)        return DistributedObjectRef.Type.TOPIC;
        if (o instanceof MultiMap)      return DistributedObjectRef.Type.MULTIMAP;
        if (o instanceof ReplicatedMap) return DistributedObjectRef.Type.REPLICATED_MAP;
        if (o instanceof ISet)          return DistributedObjectRef.Type.SET;
        if (o instanceof IList)         return DistributedObjectRef.Type.LIST;
        if (o instanceof Ringbuffer)    return DistributedObjectRef.Type.RINGBUFFER;
        return DistributedObjectRef.Type.OTHER;
    }

    // ---- IMap ---------------------------------------------------------------
    @Override
    public long mapSize(String name) { return hz.getMap(name).size(); }

    @Override
    public MapEntryView mapGet(String name, String keyJson) {
        IMap<Object, Object> map = hz.getMap(name);
        // Best-effort key parse: try JSON, fall back to string.
        Object key = parseLoose(keyJson);
        Object val = map.get(key);
        return renderEntry(keyJson, val);
    }

    @Override
    public void mapPutJson(String name, String keyJson, String valueJson, Long ttlMs) {
        IMap<Object, Object> map = hz.getMap(name);
        Object key = parseLoose(keyJson);
        HazelcastJsonValue v = new HazelcastJsonValue(valueJson);
        if (ttlMs != null && ttlMs > 0) map.put(key, v, ttlMs, TimeUnit.MILLISECONDS);
        else                            map.put(key, v);
    }

    @Override
    public boolean mapRemove(String name, String keyJson) {
        Object key = parseLoose(keyJson);
        return hz.getMap(name).remove(key) != null;
    }

    @Override
    public QueryResponse mapQuery(QueryRequest req) {
        long started = System.nanoTime();
        SqlService sql = hz.getSql();
        SqlStatement stmt = new SqlStatement(req.getQuery());
        if (req.getTimeoutMs() != null) stmt.setTimeoutMillis(req.getTimeoutMs());

        List<MapEntryView> rows = new ArrayList<>();
        boolean truncated = false;
        long scanned = 0;
        try (SqlResult result = sql.execute(stmt)) {
            for (SqlRow row : result) {
                if (rows.size() >= req.getLimit()) { truncated = true; break; }
                rows.add(rowToEntry(row));
                scanned++;
            }
        }
        return QueryResponse.builder()
                .rows(rows)
                .nextCursor(truncated ? (req.getCursor() == null ? 0L : req.getCursor()) + rows.size() : null)
                .elapsedMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .totalScanned(scanned)
                .truncated(truncated)
                .build();
    }

    // ---- Queue ----------------------------------------------------------------
    @Override
    public List<String> listQueueNames() {
        List<String> out = new ArrayList<>();
        for (DistributedObject o : hz.getDistributedObjects()) {
            if (o instanceof IQueue) out.add(o.getName());
        }
        Collections.sort(out);
        return out;
    }

    @Override
    public long queueSize(String name) { return hz.getQueue(name).size(); }

    @Override
    public List<MapEntryView> queuePeek(String name, int limit) {
        IQueue<Object> q = hz.getQueue(name);
        Object[] snap = q.toArray();
        List<MapEntryView> out = new ArrayList<>();
        for (int i = 0; i < snap.length && i < limit; i++) out.add(renderEntry(null, snap[i]));
        return out;
    }

    @Override
    public void queueOfferJson(String name, String valueJson) {
        IQueue<Object> q = hz.getQueue(name);
        q.offer(new HazelcastJsonValue(valueJson));
    }

    @Override
    public List<MapEntryView> queuePoll(String name, int count) {
        IQueue<Object> q = hz.getQueue(name);
        List<MapEntryView> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object item = q.poll();
            if (item == null) break;
            out.add(renderEntry(null, item));
        }
        return out;
    }

    @Override
    public List<MapEntryView> queueDrain(String name) {
        IQueue<Object> q = hz.getQueue(name);
        List<Object> drained = new ArrayList<>();
        q.drainTo(drained);
        List<MapEntryView> out = new ArrayList<>(drained.size());
        for (Object item : drained) out.add(renderEntry(null, item));
        return out;
    }

    // ---- Topic ----------------------------------------------------------------
    @Override
    public List<String> listTopicNames() {
        List<String> out = new ArrayList<>();
        for (DistributedObject o : hz.getDistributedObjects()) {
            if (o instanceof ITopic) out.add(o.getName());
        }
        Collections.sort(out);
        return out;
    }

    @Override
    public void topicPublishJson(String name, String valueJson) {
        ITopic<Object> t = hz.getTopic(name);
        t.publish(new HazelcastJsonValue(valueJson));
    }

    @Override
    public String topicSubscribe(String name, Consumer<MapEntryView> sink) {
        ITopic<Object> t = hz.getTopic(name);
        UUID regId = t.addMessageListener(message -> {
            Object val = message.getMessageObject();
            MapEntryView view = renderEntry(null, val);
            sink.accept(view);
        });
        return regId.toString();
    }

    @Override
    public void topicUnsubscribe(String name, String subscriptionId) {
        ITopic<Object> t = hz.getTopic(name);
        t.removeMessageListener(UUID.fromString(subscriptionId));
    }

    // ---- Phase 3 -------------------------------------------------------------
    @Override
    public List<String> listMapNames() {
        List<String> out = new ArrayList<>();
        for (DistributedObject o : hz.getDistributedObjects()) {
            if (o instanceof IMap) out.add(o.getName());
        }
        Collections.sort(out);
        return out;
    }

    @Override
    public MapStats getMapStats(String mapName) {
        IMap<Object, Object> map = hz.getMap(mapName);
        LocalMapStats s = map.getLocalMapStats();
        // From a *client* connection, getLocalMapStats() returns stats local to the client (mostly
        // near-cache). Owned counts come from map.size() / partition data. We surface what we can
        // safely; a more accurate aggregate needs member-side JMX (Phase 4).
        long size = map.size();
        return MapStats.builder()
                .name(mapName)
                .ownedEntryCount(size)
                .backupEntryCount(0)                          // not visible from client
                .ownedEntryMemoryCost(s.getOwnedEntryMemoryCost())
                .backupEntryMemoryCost(s.getBackupEntryMemoryCost())
                .lockedEntryCount(s.getLockedEntryCount())
                .dirtyEntryCount(s.getDirtyEntryCount())
                .hits(s.getHits())
                .getOperationCount(s.getGetOperationCount())
                .putOperationCount(s.getPutOperationCount())
                .removeOperationCount(s.getRemoveOperationCount())
                .lastUpdateTimeMs(s.getLastUpdateTime())
                .build();
    }

    @Override
    public MapBrowsePage browseMap(String mapName, int pageSize, int pageIndex, boolean includeValues) {
        IMap<Object, Object> map = hz.getMap(mapName);
        long total = map.size();
        // PagingPredicate streams keys in a stable order across calls. We use TruePredicate
        // and a no-op natural-order comparator. For very large maps this is still O(n) over
        // the entry set — Phase 5 will add saved/named indexed predicates.
        PagingPredicate<Object, Object> pp = (PagingPredicate<Object, Object>)
                Predicates.pagingPredicate(Predicates.alwaysTrue(), pageSize);
        pp.setPage(pageIndex);

        Set<Map.Entry<Object, Object>> entries = map.entrySet(pp);
        List<MapEntryView> rows = new ArrayList<>(entries.size());
        for (Map.Entry<Object, Object> e : entries) {
            String keyStr = stringifyKey(e.getKey());
            if (includeValues) rows.add(renderEntry(keyStr, e.getValue()));
            else               rows.add(MapEntryView.builder().keyJson(keyStr).build());
        }

        boolean hasMore = (long) (pageIndex + 1) * pageSize < total;
        return MapBrowsePage.builder()
                .mapName(mapName)
                .pageIndex(pageIndex)
                .pageSize(pageSize)
                .totalSize(total)
                .hasMore(hasMore)
                .entries(rows)
                .build();
    }

    @Override
    public SqlPage runSqlStreaming(String query, int pageSize) {
        long t0 = System.nanoTime();
        SqlService sql = hz.getSql();
        SqlResult result = sql.execute(query);
        Iterator<SqlRow> it = result.iterator();
        List<SqlColumnMeta> cols = readColumns(result.getRowMetadata());

        String cursorId = UUID.randomUUID().toString();
        OpenCursor cursor = new OpenCursor(result, it, cols);
        cursors.put(cursorId, cursor);
        sweepIdleCursors();

        SqlPage page = drainPage(cursor, cursorId, pageSize, 0,
                                 TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        if (page.isDone()) closeSqlCursor(cursorId);
        return page;
    }

    @Override
    public SqlPage fetchSqlPage(String cursorId, int pageSize) {
        OpenCursor c = cursors.get(cursorId);
        if (c == null) {
            throw new IllegalStateException(
                    "SQL cursor " + cursorId + " not found (expired or never opened). "
                  + "Re-run the query to start a fresh stream.");
        }
        long t0 = System.nanoTime();
        c.lastAccessMs = System.currentTimeMillis();
        sweepIdleCursors();
        SqlPage page = drainPage(c, cursorId, pageSize, c.pagesEmitted,
                                 TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        if (page.isDone()) closeSqlCursor(cursorId);
        return page;
    }

    @Override
    public void closeSqlCursor(String cursorId) {
        OpenCursor c = cursors.remove(cursorId);
        if (c != null) {
            try { c.result.close(); }
            catch (Exception e) { LOG.debug("SqlResult close failed for {}: {}", cursorId, e.toString()); }
        }
    }

    @Override
    public SqlPage runSqlLimitOffset(String query, int limit, int offset) {
        long t0 = System.nanoTime();
        String rewritten = rewriteWithLimitOffset(query, limit, offset);
        SqlService sql = hz.getSql();
        List<SqlColumnMeta> cols;
        List<List<Object>> rows = new ArrayList<>(limit);
        try (SqlResult result = sql.execute(rewritten)) {
            cols = readColumns(result.getRowMetadata());
            for (SqlRow row : result) {
                rows.add(rowToList(row, cols.size()));
                if (rows.size() >= limit) break;
            }
        }
        return SqlPage.builder()
                .cursorId(null)
                .mode("LIMIT")
                .columns(cols)
                .rows(rows)
                .pageIndex(offset / Math.max(1, limit))
                .done(rows.size() < limit)
                .elapsedMs(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0))
                .build();
    }

    private static String rewriteWithLimitOffset(String query, int limit, int offset) {
        // Trim trailing semicolon so we can append cleanly.
        String q = query.trim();
        while (q.endsWith(";")) q = q.substring(0, q.length() - 1).trim();
        if (HAS_LIMIT.matcher(q).matches()) {
            // User already wrote LIMIT — don't double up; trust their query for the first page
            // and treat subsequent OFFSET requests as already-done. The controller surfaces this.
            return q;
        }
        return q + " LIMIT " + limit + " OFFSET " + offset;
    }

    private SqlPage drainPage(OpenCursor c, String cursorId, int pageSize, int pageIndex, long elapsedMs) {
        List<List<Object>> rows = new ArrayList<>(Math.min(pageSize, 256));
        boolean done = false;
        for (int i = 0; i < pageSize; i++) {
            if (!c.iterator.hasNext()) { done = true; break; }
            rows.add(rowToList(c.iterator.next(), c.columns.size()));
        }
        if (!done && !c.iterator.hasNext()) done = true;
        c.pagesEmitted = pageIndex + 1;
        return SqlPage.builder()
                .cursorId(done ? null : cursorId)
                .mode("STREAM")
                .columns(c.columns)
                .rows(rows)
                .pageIndex(pageIndex)
                .done(done)
                .elapsedMs(elapsedMs)
                .build();
    }

    private static List<SqlColumnMeta> readColumns(SqlRowMetadata md) {
        List<SqlColumnMeta> cols = new ArrayList<>(md.getColumnCount());
        for (int i = 0; i < md.getColumnCount(); i++) {
            SqlColumnMetadata cm = md.getColumn(i);
            cols.add(SqlColumnMeta.builder()
                    .name(cm.getName())
                    .type(cm.getType().name())
                    .nullable(cm.isNullable())
                    .build());
        }
        return cols;
    }

    private static List<Object> rowToList(SqlRow row, int n) {
        List<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Object v = row.getObject(i);
            // Hazelcast types like LocalDateTime / OffsetDateTime serialize fine through Jackson;
            // for opaque types we stringify so the JSON layer can never throw.
            if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) out.add(v);
            else if (v instanceof HazelcastJsonValue) out.add(v.toString());
            else                                       out.add(v.toString());
        }
        return out;
    }

    /** Reaps cursors not touched in {@link #SQL_CURSOR_IDLE_TTL_MS}. Called on every cursor op. */
    private void sweepIdleCursors() {
        long cutoff = System.currentTimeMillis() - SQL_CURSOR_IDLE_TTL_MS;
        for (Map.Entry<String, OpenCursor> e : new ArrayList<>(cursors.entrySet())) {
            if (e.getValue().lastAccessMs < cutoff) {
                LOG.debug("Reaping idle SQL cursor {}", e.getKey());
                closeSqlCursor(e.getKey());
            }
        }
    }

    /** Best-effort key stringification for the browse UI; mirrors {@code parseLoose} on read-back. */
    private static String stringifyKey(Object k) {
        if (k == null) return null;
        if (k instanceof String || k instanceof Number || k instanceof Boolean) return k.toString();
        try { return JSON.writeValueAsString(k); }
        catch (Exception e) { return k.toString(); }
    }

    /** Per-cursor state held on the bridge side. NOT thread-safe — controller must serialize calls per cursor. */
    private static final class OpenCursor {
        final SqlResult            result;
        final Iterator<SqlRow>     iterator;
        final List<SqlColumnMeta>  columns;
        volatile int               pagesEmitted;
        volatile long              lastAccessMs;
        OpenCursor(SqlResult r, Iterator<SqlRow> it, List<SqlColumnMeta> cols) {
            this.result       = r;
            this.iterator     = it;
            this.columns      = cols;
            this.lastAccessMs = System.currentTimeMillis();
        }
    }

    @Override
    public void close() {
        // Close any orphaned SQL cursors before tearing down the client.
        for (String id : new ArrayList<>(cursors.keySet())) closeSqlCursor(id);
        try { hz.getLifecycleService().shutdown(); }
        catch (Exception e) { LOG.warn("Bridge close failed: {}", e.toString()); }
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Per-bridge reverse-DNS cache. Lookups are slow on cold cache (10s+ on misconfigured DNS)
     * so we memoise the result for the lifetime of this bridge instance. The bridge is
     * itself idle-evicted by BridgeRouter, so the cache also gets pruned naturally when
     * the bridge is closed.
     */
    private final java.util.Map<String, String> hostnameCache = new java.util.concurrent.ConcurrentHashMap<>();

    private MemberInfo toInfo(Member m) {
        String host = m.getAddress().getHost();
        int    port = m.getAddress().getPort();
        return MemberInfo.builder()
                .uuid(m.getUuid().toString())
                .address(host + ":" + port)
                .host(host)
                .port(port)
                .hostName(resolveHostname(host))
                .lite(m.isLiteMember())
                .version(m.getVersion() == null ? null : m.getVersion().toString())
                .local(false)              // local-from-the-client perspective is meaningless
                .attributes(new LinkedHashMap<>(m.getAttributes()))
                .build();
    }

    /**
     * Reverse-DNS lookup with cache. Returns "" when:
     *   - the lookup throws (DNS unreachable, host disallowed, etc.), or
     *   - the canonical name is just the IP back (no PTR record), or
     *   - the host string is already a name (Hazelcast was configured with a hostname).
     * Callers should fall back to {@link MemberInfo#getHost()} on empty.
     */
    private String resolveHostname(String hostOrIp) {
        if (hostOrIp == null || hostOrIp.isEmpty()) return "";
        return hostnameCache.computeIfAbsent(hostOrIp, ip -> {
            try {
                java.net.InetAddress addr = java.net.InetAddress.getByName(ip);
                String canonical = addr.getCanonicalHostName();
                if (canonical == null || canonical.equals(ip)) return "";
                return canonical;
            } catch (Exception e) {
                LOG.debug("reverse-DNS lookup failed for {}: {}", ip, e.toString());
                return "";
            }
        });
    }

    private static Object parseLoose(String json) {
        if (json == null) return null;
        String s = json.trim();
        if (s.isEmpty()) return s;
        try { return JSON.readValue(s, Object.class); }
        catch (Exception ignore) { return s; }
    }

    private MapEntryView renderEntry(String keyJson, Object val) {
        if (val == null) {
            return MapEntryView.builder().keyJson(keyJson).valueClassName("null").valueSizeBytes(0L).build();
        }
        String cls = val.getClass().getName();
        String json = null;
        if (val instanceof HazelcastJsonValue) {
            json = val.toString();
        } else if (val instanceof Number || val instanceof Boolean || val instanceof String) {
            try { json = JSON.writeValueAsString(val); } catch (Exception ignore) {}
        } else {
            try { json = JSON.writeValueAsString(val); } catch (Exception ignore) {
                // fall through to byte preview only
            }
        }
        return MapEntryView.builder()
                .keyJson(keyJson)
                .valueJson(json)
                .valueClassName(cls)
                .valueSizeBytes((long) (json == null ? 0 : json.length()))
                .build();
    }

    private MapEntryView rowToEntry(SqlRow row) {
        Map<String, Object> obj = new LinkedHashMap<>();
        int n = row.getMetadata().getColumnCount();
        for (int i = 0; i < n; i++) obj.put(row.getMetadata().getColumn(i).getName(), row.getObject(i));
        try {
            String json = JSON.writeValueAsString(obj);
            return MapEntryView.builder()
                    .keyJson(null)
                    .valueJson(json)
                    .valueClassName("sql.row")
                    .valueSizeBytes((long) json.length())
                    .build();
        } catch (Exception e) {
            return MapEntryView.builder().keyJson(null).valueClassName("sql.row").valueSizeBytes(0L).build();
        }
    }
}

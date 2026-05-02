package com.genius.hz.bridge.v4;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hazelcast 4.x SPI implementation, targeting client 4.2.8.
 *
 * <p>Wire-protocol compatible with cluster members from 4.0+. Note:
 * <ul>
 *   <li>SQL is {@code @Beta} in 4.2; some queries that work on 5.x may behave differently
 *       or be unsupported. We surface SQL exceptions verbatim.</li>
 *   <li>Member runtime stats accessible from a *client* are limited (heap / CPU live on the
 *       member side — Hazelcast only exposes those via JMX or member-side agents). This bridge
 *       reports what the client SDK can see; the metrics collector composes the rest.</li>
 * </ul>
 */
public class HazelcastBridge4x implements HazelcastBridge {

    private static final Logger LOG = LoggerFactory.getLogger(HazelcastBridge4x.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final long SQL_CURSOR_IDLE_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final Pattern HAS_LIMIT = Pattern.compile("(?is).*\\blimit\\b\\s+\\d+.*");

    private final HazelcastInstance hz;
    private final Map<String, UUID> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, OpenCursor> cursors = new ConcurrentHashMap<>();

    HazelcastBridge4x(HazelcastInstance hz) { this.hz = hz; }

    @Override public String  supportedMajorVersion() { return "4"; }
    @Override public boolean isConnected()           { return hz.getLifecycleService().isRunning(); }

    // ---- Cluster ------------------------------------------------------------
    @Override public String getClusterName()  { return hz.getConfig().getClusterName(); }

    /**
     * Mirror of {@link com.genius.hz.bridge.v5.HazelcastBridge5x#getClusterState()} —
     * see that javadoc for the rationale. {@code Cluster.getClusterState()} can throw
     * {@code UnsupportedOperationException} from a 4.x client against certain server
     * configs, and any throw inside the metrics or overview paths makes a healthy
     * cluster look offline. Returning the sentinel {@code "UNKNOWN"} keeps the
     * downstream code paths working.
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
     * Mirror of {@link com.genius.hz.bridge.v5.HazelcastBridge5x#isClusterSafe()} —
     * see that class's javadoc for the rationale. {@code PartitionService.isClusterSafe()}
     * is member-only in 4.2.x as well, so we use the partition-owner heuristic.
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
        return "4.2.8";
    }

    @Override
    public void shutdownMember(String uuid, boolean force) {
        // Same constraint as the 5.x bridge: from a client we cannot directly stop a member.
        // Phase 4 routes this through SSH executor.
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
        SqlService sql;
        try {
            sql = hz.getSql();
        } catch (NoSuchMethodError | UnsupportedOperationException notSupported) {
            // 4.0 / 4.1 cluster — SQL was added in 4.2. Surface a clear error so the UI can
            // tell the user to upgrade or fall back to predicate-based queries.
            throw new UnsupportedOperationException(
                "SQL is not supported on Hazelcast clusters older than 4.2. " +
                "Either upgrade the cluster or use predicate-based map queries.", notSupported);
        }
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
        String regId = t.addMessageListener(message -> {
            Object val = message.getMessageObject();
            MapEntryView view = renderEntry(null, val);
            sink.accept(view);
        });
        return regId;
    }

    @Override
    public void topicUnsubscribe(String name, String subscriptionId) {
        ITopic<Object> t = hz.getTopic(name);
        t.removeMessageListener(subscriptionId);
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
        long size = map.size();
        return MapStats.builder()
                .name(mapName)
                .ownedEntryCount(size)
                .backupEntryCount(0)
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
        // Hazelcast 4.x: same Predicates.pagingPredicate API as 5.x. The cast suppresses the
        // raw-type warning the older API emits.
        @SuppressWarnings("unchecked")
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
        SqlService sql = sqlOrThrow();
        SqlResult result = sql.execute(query);
        Iterator<SqlRow> it = result.iterator();
        List<SqlColumnMeta> cols = readColumns(result.getRowMetadata());

        String cursorId = UUID.randomUUID().toString();
        OpenCursor c = new OpenCursor(result, it, cols);
        cursors.put(cursorId, c);
        sweepIdleCursors();

        SqlPage page = drainPage(c, cursorId, pageSize, 0,
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
        SqlService sql = sqlOrThrow();
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

    /** Throws a clear, user-actionable error on pre-4.2 clusters. Same wording as {@link #mapQuery}. */
    private SqlService sqlOrThrow() {
        try {
            return hz.getSql();
        } catch (NoSuchMethodError | UnsupportedOperationException notSupported) {
            throw new UnsupportedOperationException(
                "SQL is not supported on Hazelcast clusters older than 4.2. " +
                "Either upgrade the cluster or use predicate-based map queries.", notSupported);
        }
    }

    private static String rewriteWithLimitOffset(String query, int limit, int offset) {
        String q = query.trim();
        while (q.endsWith(";")) q = q.substring(0, q.length() - 1).trim();
        if (HAS_LIMIT.matcher(q).matches()) return q;
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
            if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) out.add(v);
            else if (v instanceof HazelcastJsonValue) out.add(v.toString());
            else                                       out.add(v.toString());
        }
        return out;
    }

    private void sweepIdleCursors() {
        long cutoff = System.currentTimeMillis() - SQL_CURSOR_IDLE_TTL_MS;
        for (Map.Entry<String, OpenCursor> e : new ArrayList<>(cursors.entrySet())) {
            if (e.getValue().lastAccessMs < cutoff) {
                LOG.debug("Reaping idle SQL cursor {}", e.getKey());
                closeSqlCursor(e.getKey());
            }
        }
    }

    private static String stringifyKey(Object k) {
        if (k == null) return null;
        if (k instanceof String || k instanceof Number || k instanceof Boolean) return k.toString();
        try { return JSON.writeValueAsString(k); }
        catch (Exception e) { return k.toString(); }
    }

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
        for (String id : new ArrayList<>(cursors.keySet())) closeSqlCursor(id);
        try { hz.getLifecycleService().shutdown(); }
        catch (Exception e) { LOG.warn("Bridge close failed: {}", e.toString()); }
    }

    // ---- helpers ------------------------------------------------------------
    /**
     * Per-bridge reverse-DNS cache. See HazelcastBridge5x.resolveHostname for rationale.
     */
    private final java.util.Map<String, String> hostnameCache = new java.util.concurrent.ConcurrentHashMap<>();

    private MemberInfo toInfo(Member m) {
        Map<String, String> attrs = m.getAttributes();
        Map<String, String> safe = attrs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attrs);
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
                .local(false)              // local-from-client is meaningless
                .attributes(safe)
                .build();
    }

    /** See HazelcastBridge5x.resolveHostname — same impl, mirrored to keep bridges symmetrical. */
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

package com.genius.hz.bridge;

import com.genius.hz.api.MapBrowsePage;
import com.genius.hz.api.MapEntryView;
import com.genius.hz.api.MapStats;
import com.genius.hz.api.MemberInfo;
import com.genius.hz.api.QueryRequest;
import com.genius.hz.api.QueryResponse;
import com.genius.hz.api.SqlPage;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Single seam between the Spring Boot backend and any specific Hazelcast client version.
 *
 * <p>Implementations live in their own module ({@code hz-bridge-5x}, future {@code hz-bridge-4x})
 * and may be loaded into isolated classloaders. NO Hazelcast types must appear in this interface.
 *
 * <p>Lifecycle: a bridge is created via {@link HazelcastBridgeFactory#open(BridgeConnectConfig)},
 * cached per-cluster by {@code BridgeRouter}, and {@link #close()}d on idle eviction.
 */
public interface HazelcastBridge extends AutoCloseable {

    String supportedMajorVersion();   // "5"

    boolean isConnected();

    // ---- Cluster & members ----------------------------------------------------------
    String   getClusterName();
    String   getClusterState();       // ACTIVE / NO_MIGRATION / FROZEN / PASSIVE
    void     changeClusterState(String newState);
    List<MemberInfo> listMembers();
    void     shutdownMember(String uuid, boolean force);

    // ---- Cluster overview (Phase 2 dashboard) ---------------------------------------
    /** Total partition count (default 271 in HZ; configurable per cluster). */
    int      getPartitionCount();
    /** True iff there are no in-flight partition migrations and quorum is satisfied. */
    boolean  isClusterSafe();
    /** Hazelcast client library version this bridge was built against, e.g. "5.2.5" / "4.2.8". */
    String   getClientVersion();

    /** Subscribe to MEMBER_ADDED / MEMBER_REMOVED events. Returns an id for unsubscribe. */
    String   subscribeMembershipEvents(Consumer<MembershipEvent> sink);
    void     unsubscribe(String subscriptionId);

    // ---- Distributed objects --------------------------------------------------------
    Set<DistributedObjectRef> listDistributedObjects();

    // IMap
    long          mapSize(String name);
    MapEntryView  mapGet(String name, String keyJson);
    void          mapPutJson(String name, String keyJson, String valueJson, Long ttlMs);
    boolean       mapRemove(String name, String keyJson);
    QueryResponse mapQuery(QueryRequest req);

    // ---- Phase 3: map browse, stats & SQL cursor -----------------------------------
    /** Just IMap names (cheaper than {@link #listDistributedObjects()} when the UI only wants maps). */
    List<String>   listMapNames();
    /** Aggregated stats sampled from {@code IMap.getLocalMapStats()} across cluster members. */
    MapStats       getMapStats(String mapName);
    /**
     * Page through entries of an IMap in a stable order (Hazelcast {@code PagingPredicate}
     * underneath, comparator on natural key order).
     *
     * @param includeValues when {@code false} the page returns keys only — much cheaper for
     *                      large maps and used by the "look up by key" affordance to populate
     *                      an autocomplete.
     */
    MapBrowsePage  browseMap(String mapName, int pageSize, int pageIndex, boolean includeValues);

    /**
     * Run a SQL query in *streaming* mode and return the first page. The bridge keeps the
     * underlying {@code SqlResult} open and indexes it by the returned {@code cursorId};
     * subsequent pages are fetched via {@link #fetchSqlPage(String, int)} and the cursor
     * MUST eventually be released via {@link #closeSqlCursor(String)} (or it'll be reaped
     * after a 5-minute idle timeout enforced inside the bridge).
     */
    SqlPage        runSqlStreaming(String query, int pageSize);
    /** Pull the next page from a previously-opened streaming cursor. */
    SqlPage        fetchSqlPage(String cursorId, int pageSize);
    /** Best-effort cursor cleanup. Idempotent — closing a missing/expired cursor is a no-op. */
    void           closeSqlCursor(String cursorId);

    /**
     * Run a SQL query in *one-shot* mode by appending {@code LIMIT} / {@code OFFSET} when the
     * query doesn't already contain a LIMIT clause. Simpler protocol than streaming but breaks
     * ORDER BY semantics on re-pagination if the underlying data changes between calls.
     */
    SqlPage        runSqlLimitOffset(String query, int limit, int offset);

    // ---- Queue -----------------------------------------------------------------
    /** Just IQueue names (mirrors {@link #listMapNames()} for queues). */
    List<String>       listQueueNames();
    long               queueSize(String name);
    List<MapEntryView> queuePeek(String name, int limit);
    /** Offer a JSON-encoded value to the tail of the queue. */
    void               queueOfferJson(String name, String valueJson);
    /** Poll (destructive read) up to {@code count} items from the head. */
    List<MapEntryView> queuePoll(String name, int count);
    /** Drain all items (destructive). Returns the removed items. */
    List<MapEntryView> queueDrain(String name);

    // ---- Topic -----------------------------------------------------------------
    /** Just ITopic names (mirrors {@link #listMapNames()} for topics). */
    List<String>       listTopicNames();
    void               topicPublishJson(String name, String valueJson);
    /**
     * Subscribe to messages published on this topic. Returns a subscription id.
     * Each received message is forwarded to the sink as a MapEntryView (keyJson=null,
     * valueJson=the message body). Call {@link #topicUnsubscribe(String, String)}
     * to cancel.
     */
    String             topicSubscribe(String name, Consumer<MapEntryView> sink);
    void               topicUnsubscribe(String name, String subscriptionId);

    @Override void close();
}

package com.genius.hz.api;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Aggregated stats for a single distributed IMap, derived from {@code IMap.getLocalMapStats()}
 * collected across all members the bridge sees. Heap-cost numbers are best-effort: Hazelcast
 * accounts for entries owned by *this* member only, so a remote client sums per-member partial
 * stats. Counts at the cluster level are reliable; heap costs are accurate within a known
 * margin (see Phase 4 plan for adding member-side JMX collection).
 */
@Value
@Builder
@Jacksonized
public class MapStats {
    String  name;

    /** Sum of locally-owned entries across all members the client can see. */
    long    ownedEntryCount;
    /** Sum of backup entries across members. */
    long    backupEntryCount;

    /** Approximate heap cost in bytes for owned entries (sum of LocalMapStats getOwnedEntryMemoryCost). */
    long    ownedEntryMemoryCost;
    /** Same for backups. */
    long    backupEntryMemoryCost;

    long    lockedEntryCount;
    long    dirtyEntryCount;

    long    hits;
    /** {@code -1} when the metric isn't available on the cluster's HZ version. */
    long    getOperationCount;
    long    putOperationCount;
    long    removeOperationCount;

    /** Last-update timestamp on this client's view; {@code 0} if no updates seen. */
    long    lastUpdateTimeMs;
}

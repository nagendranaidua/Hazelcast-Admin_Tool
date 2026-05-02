package com.genius.hz.admin.metrics;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable point-in-time snapshot of one IMap's stats. Stored in the
 * {@link MapMetricsBuffer} ring per (clusterId, mapName); reads return a snapshot list.
 *
 * <p>Cumulative counter fields ({@code getOps}, {@code putOps}, {@code removeOps},
 * {@code hits}) are stored as raw cluster-aggregated values exactly as Hazelcast reports
 * them. Per-second derivatives are computed at the read endpoint by diffing consecutive
 * samples — keeping the storage layer ignorant of derivation logic means we can change
 * the chart's window/units later without rewriting historical samples.
 */
@Value
@Builder
public class MapMetricSample {
    long ts;                       // epoch millis
    boolean reachable;             // false → sample taken but bridge call failed; series shows a gap

    long ownedEntryCount;          // current entries owned across the cluster
    long backupEntryCount;
    long ownedEntryMemoryCost;     // bytes
    long backupEntryMemoryCost;    // bytes

    long getOps;                   // cumulative
    long putOps;
    long removeOps;
    long hits;

    long lockedEntryCount;
    long dirtyEntryCount;
}

package com.genius.hz.admin.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-map ring buffer of {@link MapMetricSample}s, keyed by (clusterId, mapName).
 *
 * <p>Same general shape as {@link MetricsBuffer}, with an extra dimension for the map name.
 * Sized to retain {@code maxSamples} entries per map; oldest dropped on overflow. The buffer
 * is bounded by configuration so a cluster with 1000 maps × 240 samples doesn't blow heap —
 * see {@code hz-admin.metrics.map-retain-samples} (default 120 = ~30 min at 15s intervals).
 *
 * <p>Maps that are removed from a cluster are eventually evicted by the collector (which
 * stops sampling them); their buffers stay until {@link #evictCluster} or {@link #evictMap}
 * is called explicitly. We tolerate the slight drift because explicit cleanup adds locking
 * and the storage cost is small.
 */
public class MapMetricsBuffer {

    private final int maxSamples;

    /**
     * Outer key: clusterId. Inner key: map name. Inner value: ring deque of samples.
     * Both maps are concurrent so add/snapshot threads don't fight over the outer key.
     */
    private final Map<Long, Map<String, Deque<MapMetricSample>>> byCluster = new ConcurrentHashMap<>();

    public MapMetricsBuffer(int maxSamples) { this.maxSamples = maxSamples; }

    public void add(long clusterId, String mapName, MapMetricSample s) {
        Map<String, Deque<MapMetricSample>> mapsForCluster =
                byCluster.computeIfAbsent(clusterId, k -> new ConcurrentHashMap<>());
        Deque<MapMetricSample> q =
                mapsForCluster.computeIfAbsent(mapName, k -> new ArrayDeque<>(maxSamples + 1));
        synchronized (q) {
            q.addLast(s);
            while (q.size() > maxSamples) q.removeFirst();
        }
    }

    /** Snapshot all samples for a (cluster, map), oldest first, optionally bounded by [from,to]. */
    public List<MapMetricSample> snapshot(long clusterId, String mapName, Long fromInclusive, Long toInclusive) {
        Map<String, Deque<MapMetricSample>> mapsForCluster = byCluster.get(clusterId);
        if (mapsForCluster == null) return Collections.emptyList();
        Deque<MapMetricSample> q = mapsForCluster.get(mapName);
        if (q == null) return Collections.emptyList();
        List<MapMetricSample> out;
        synchronized (q) { out = new ArrayList<>(q); }
        if (fromInclusive == null && toInclusive == null) return out;
        List<MapMetricSample> filtered = new ArrayList<>(out.size());
        for (MapMetricSample s : out) {
            if (fromInclusive != null && s.getTs() < fromInclusive) continue;
            if (toInclusive   != null && s.getTs() > toInclusive)   continue;
            filtered.add(s);
        }
        return filtered;
    }

    /** Names of maps with at least one stored sample for this cluster. */
    public List<String> mapsWithSamples(long clusterId) {
        Map<String, Deque<MapMetricSample>> mapsForCluster = byCluster.get(clusterId);
        if (mapsForCluster == null) return Collections.emptyList();
        return new ArrayList<>(mapsForCluster.keySet());
    }

    public void evictCluster(long clusterId) { byCluster.remove(clusterId); }

    public void evictMap(long clusterId, String mapName) {
        Map<String, Deque<MapMetricSample>> mapsForCluster = byCluster.get(clusterId);
        if (mapsForCluster != null) mapsForCluster.remove(mapName);
    }

    /** Diagnostic: { clusterId -> { mapName -> sampleCount } }. Used by /actuator and tests. */
    public Map<Long, Map<String, Integer>> sampleCounts() {
        Map<Long, Map<String, Integer>> out = new LinkedHashMap<>();
        byCluster.forEach((cid, maps) -> {
            Map<String, Integer> inner = new LinkedHashMap<>();
            maps.forEach((m, q) -> { synchronized (q) { inner.put(m, q.size()); } });
            out.put(cid, inner);
        });
        return out;
    }
}

package com.genius.hz.admin.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-cluster ring buffer of {@link MetricSample}s.
 *
 * <p>Sized to retain the last {@code maxSamples} entries; oldest dropped on overflow.
 * Reads return a defensive snapshot list so concurrent modifications don't tear the response.
 *
 * <p>Phase 5 will optionally back this with a persistent store (Postgres tsvector or Prometheus
 * remote-write). For Phase 2 the in-memory buffer is sized for ~60 minutes at 15s poll interval
 * (240 samples) which matches the dashboard time-window we surface.
 */
public class MetricsBuffer {

    private final int maxSamples;
    private final Map<Long, Deque<MetricSample>> byCluster = new ConcurrentHashMap<>();

    public MetricsBuffer(int maxSamples) { this.maxSamples = maxSamples; }

    public void add(long clusterId, MetricSample s) {
        Deque<MetricSample> q = byCluster.computeIfAbsent(clusterId, k -> new ArrayDeque<>(maxSamples + 1));
        synchronized (q) {
            q.addLast(s);
            while (q.size() > maxSamples) q.removeFirst();
        }
    }

    /** Snapshot all samples for a cluster (oldest first), optionally bounded by [from,to]. */
    public List<MetricSample> snapshot(long clusterId, Long fromInclusive, Long toInclusive) {
        Deque<MetricSample> q = byCluster.get(clusterId);
        if (q == null) return java.util.Collections.emptyList();
        List<MetricSample> out;
        synchronized (q) { out = new ArrayList<>(q); }
        if (fromInclusive == null && toInclusive == null) return out;
        List<MetricSample> filtered = new ArrayList<>(out.size());
        for (MetricSample s : out) {
            if (fromInclusive != null && s.getTs() < fromInclusive) continue;
            if (toInclusive   != null && s.getTs() > toInclusive)   continue;
            filtered.add(s);
        }
        return filtered;
    }

    public void evict(long clusterId) { byCluster.remove(clusterId); }

    public int sampleCount(long clusterId) {
        Deque<MetricSample> q = byCluster.get(clusterId);
        if (q == null) return 0;
        synchronized (q) { return q.size(); }
    }
}

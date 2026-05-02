package com.genius.hz.admin.metrics;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.admin.domain.Cluster;
import com.genius.hz.admin.repo.ClusterRepository;
import com.genius.hz.api.MapStats;
import com.genius.hz.bridge.HazelcastBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodic poller that samples each enabled cluster and pushes the result into
 * {@link MetricsBuffer}. Failures don't abort the loop — they get recorded as
 * {@code connected=false} samples so the chart can show the downtime gap.
 *
 * <p>What we measure is constrained by the Hazelcast client API surface: heap & CPU live on
 * the member side and aren't exposed to a remote client. Phase 4/5 will add JMX-over-SSH
 * collection for those. Today we surface what the client SDK can see plus our own
 * round-trip latency, which is the most useful "is this cluster healthy?" signal anyway.
 */
@Component
public class MetricsCollector {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsCollector.class);

    private final ClusterRepository repo;
    private final BridgeRouter      router;
    private final MetricsBuffer     buffer;
    private final MapMetricsBuffer  mapBuffer;
    /**
     * Cap on the number of maps sampled per cluster per tick. Without this, a cluster with
     * thousands of IMaps would saturate the single-threaded scheduler. 50 covers the
     * vast majority of real deployments; raise via config if you regularly want more.
     */
    private final int               mapSampleCap;
    private final boolean           mapSamplingEnabled;

    public MetricsCollector(ClusterRepository repo,
                            BridgeRouter router,
                            MetricsBuffer buffer,
                            MapMetricsBuffer mapBuffer,
                            @Value("${hz-admin.metrics.map-sampling-enabled:true}") boolean mapSamplingEnabled,
                            @Value("${hz-admin.metrics.map-sample-cap:50}") int mapSampleCap) {
        this.repo               = repo;
        this.router             = router;
        this.buffer             = buffer;
        this.mapBuffer          = mapBuffer;
        this.mapSamplingEnabled = mapSamplingEnabled;
        this.mapSampleCap       = Math.max(1, mapSampleCap);
    }

    /**
     * Fixed-rate poller. Period is application-config'd via {@code hz-admin.metrics.sample-interval-sec}
     * (read by the @Bean factory below into a millis literal that Spring resolves at parse time).
     * Default 15s — see application.yml.
     */
    @Scheduled(fixedRateString = "#{${hz-admin.metrics.sample-interval-sec:15} * 1000}")
    public void sampleAll() {
        List<Cluster> all = repo.findAll();
        long now = System.currentTimeMillis();
        for (Cluster c : all) {
            if (!c.isEnabled()) continue;
            sampleOne(c, now);
        }
    }

    private void sampleOne(Cluster c, long now) {
        long t0 = System.nanoTime();
        try {
            HazelcastBridge b = router.bridgeFor(c.getId());
            int members    = b.listMembers().size();
            int partitions = b.getPartitionCount();
            boolean safe   = b.isClusterSafe();
            String state   = b.getClusterState();
            long latency   = (System.nanoTime() - t0) / 1_000_000L;
            buffer.add(c.getId(), MetricSample.builder()
                    .ts(now)
                    .connected(true)
                    .memberCount(members)
                    .partitionCount(partitions)
                    .clusterSafe(safe)
                    .clusterState(state)
                    .bridgeCallLatencyMs(latency)
                    .build());
        } catch (Exception e) {
            long latency = (System.nanoTime() - t0) / 1_000_000L;
            buffer.add(c.getId(), MetricSample.builder()
                    .ts(now)
                    .connected(false)
                    .memberCount(0)
                    .partitionCount(0)
                    .clusterSafe(false)
                    .clusterState(null)
                    .bridgeCallLatencyMs(latency)
                    .build());
            LOG.debug("Metrics sample failed for cluster id={} ({}): {}", c.getId(), c.getName(), e.toString());
        }
    }

    /**
     * Per-map sampling. Separate scheduler from {@link #sampleAll()} so operators can tune
     * the two independently. Default 30s — slower than the 15s cluster sampler because
     * per-map data has many more rows to gather and changes less frequently. Disabled by
     * setting {@code hz-admin.metrics.map-sampling-enabled=false}.
     *
     * <p>For each cluster we ask the bridge for {@code listMapNames()} (cheap), then call
     * {@code getMapStats(name)} on up to {@link #mapSampleCap} maps. If a map throws, the
     * sample for that map is recorded as {@code reachable=false} so the chart shows the gap;
     * other maps in the same cluster keep going.
     */
    @Scheduled(fixedRateString = "#{${hz-admin.metrics.map-sample-interval-sec:30} * 1000}")
    public void sampleMaps() {
        if (!mapSamplingEnabled) return;
        long now = System.currentTimeMillis();
        for (Cluster c : repo.findAll()) {
            if (!c.isEnabled()) continue;
            sampleMapsForCluster(c, now);
        }
    }

    private void sampleMapsForCluster(Cluster c, long now) {
        List<String> mapNames;
        HazelcastBridge b;
        try {
            b = router.bridgeFor(c.getId());
            mapNames = b.listMapNames();
        } catch (Exception e) {
            LOG.debug("listMapNames failed for cluster id={} ({}): {}",
                      c.getId(), c.getName(), e.toString());
            return;
        }
        int sampled = 0;
        for (String name : mapNames) {
            if (sampled >= mapSampleCap) {
                LOG.debug("map sample cap {} reached for cluster id={}; skipping {} more",
                          mapSampleCap, c.getId(), mapNames.size() - sampled);
                break;
            }
            try {
                MapStats s = b.getMapStats(name);
                mapBuffer.add(c.getId(), name, MapMetricSample.builder()
                        .ts(now)
                        .reachable(true)
                        .ownedEntryCount(s.getOwnedEntryCount())
                        .backupEntryCount(s.getBackupEntryCount())
                        .ownedEntryMemoryCost(s.getOwnedEntryMemoryCost())
                        .backupEntryMemoryCost(s.getBackupEntryMemoryCost())
                        .getOps(s.getGetOperationCount())
                        .putOps(s.getPutOperationCount())
                        .removeOps(s.getRemoveOperationCount())
                        .hits(s.getHits())
                        .lockedEntryCount(s.getLockedEntryCount())
                        .dirtyEntryCount(s.getDirtyEntryCount())
                        .build());
            } catch (Exception e) {
                mapBuffer.add(c.getId(), name, MapMetricSample.builder()
                        .ts(now).reachable(false).build());
                LOG.debug("getMapStats failed for cluster id={} map={}: {}",
                          c.getId(), name, e.toString());
            }
            sampled++;
        }
    }

    @Configuration
    static class BufferConfig {
        @Bean
        public MetricsBuffer metricsBuffer(@Value("${hz-admin.metrics.retain-samples:240}") int retain) {
            return new MetricsBuffer(retain);
        }
        @Bean
        public MapMetricsBuffer mapMetricsBuffer(@Value("${hz-admin.metrics.map-retain-samples:120}") int retain) {
            return new MapMetricsBuffer(retain);
        }
    }
}

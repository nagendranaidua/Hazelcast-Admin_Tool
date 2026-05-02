package com.genius.hz.admin.web;

import com.genius.hz.admin.metrics.MetricSample;
import com.genius.hz.admin.metrics.MetricsBuffer;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Time-series read endpoint for the metrics chart.
 *
 * <p>Returns samples in a compact columnar form so the frontend can plot multiple series
 * without re-shaping the data. {@code from} and {@code to} are epoch-millis bounds; both
 * optional.
 */
@RestController
@RequestMapping("/api/clusters/{id}/metrics")
@Tag(name = "Metrics", description = "Cluster time-series for charting")
@PreAuthorize("isAuthenticated()")
public class MetricsController {

    private final MetricsBuffer buffer;

    public MetricsController(MetricsBuffer buffer) { this.buffer = buffer; }

    @GetMapping
    public ResponseEntity<Map<String, Object>> series(@PathVariable("id") Long id,
                                                      @RequestParam(value = "from", required = false) Long from,
                                                      @RequestParam(value = "to",   required = false) Long to) {
        List<MetricSample> samples = buffer.snapshot(id, from, to);
        int n = samples.size();
        long[]    ts        = new long[n];
        int[]     members   = new int[n];
        int[]     parts     = new int[n];
        boolean[] safe      = new boolean[n];
        boolean[] connected = new boolean[n];
        long[]    latency   = new long[n];
        String[]  state     = new String[n];
        for (int i = 0; i < n; i++) {
            MetricSample s = samples.get(i);
            ts[i]        = s.getTs();
            members[i]   = s.getMemberCount();
            parts[i]     = s.getPartitionCount();
            safe[i]      = s.isClusterSafe();
            connected[i] = s.isConnected();
            latency[i]   = s.getBridgeCallLatencyMs();
            state[i]     = s.getClusterState();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count",            n);
        body.put("ts",               ts);
        body.put("memberCount",      members);
        body.put("partitionCount",   parts);
        body.put("clusterSafe",      safe);
        body.put("connected",        connected);
        body.put("bridgeLatencyMs",  latency);
        body.put("clusterState",     state);
        return ResponseEntity.ok(body);
    }
}

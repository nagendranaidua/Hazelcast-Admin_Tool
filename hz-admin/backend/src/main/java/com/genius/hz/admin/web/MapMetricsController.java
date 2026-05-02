package com.genius.hz.admin.web;

import com.genius.hz.admin.metrics.MapMetricSample;
import com.genius.hz.admin.metrics.MapMetricsBuffer;
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
 * Per-map time-series read endpoint backing the Stage-3 mini-charts on the map browse page.
 *
 * <p>Returns columnar arrays so the chart code can plot N series without re-shaping. Cumulative
 * counters (gets/puts/removes/hits) are returned as raw values plus a derived per-second array
 * computed from consecutive deltas. The first per-second value is always {@code 0} because we
 * have no prior sample to diff against.
 */
@RestController
@RequestMapping("/api/clusters/{id}/maps/{name}/metrics")
@Tag(name = "MapMetrics", description = "Per-map time-series for the chart strip on map browse")
@PreAuthorize("isAuthenticated()")
public class MapMetricsController {

    private final MapMetricsBuffer buffer;

    public MapMetricsController(MapMetricsBuffer buffer) { this.buffer = buffer; }

    @GetMapping
    public ResponseEntity<Map<String, Object>> series(@PathVariable("id")   Long   id,
                                                      @PathVariable("name") String name,
                                                      @RequestParam(value = "from", required = false) Long from,
                                                      @RequestParam(value = "to",   required = false) Long to) {
        List<MapMetricSample> samples = buffer.snapshot(id, name, from, to);
        int n = samples.size();

        long[]    ts                   = new long[n];
        boolean[] reachable            = new boolean[n];
        long[]    entries              = new long[n];
        long[]    backupEntries        = new long[n];
        long[]    memoryBytes          = new long[n];
        long[]    backupMemoryBytes    = new long[n];
        long[]    hits                 = new long[n];
        long[]    locked               = new long[n];
        long[]    dirty                = new long[n];
        long[]    getOpsCum            = new long[n];
        long[]    putOpsCum            = new long[n];
        long[]    removeOpsCum         = new long[n];
        // Per-second derivatives. Index 0 = 0 (no prior sample); subsequent values diff
        // counter against previous sample and divide by elapsed seconds.
        double[]  getOpsPerSec         = new double[n];
        double[]  putOpsPerSec         = new double[n];
        double[]  removeOpsPerSec      = new double[n];
        double[]  totalOpsPerSec       = new double[n];

        for (int i = 0; i < n; i++) {
            MapMetricSample s = samples.get(i);
            ts[i]                = s.getTs();
            reachable[i]         = s.isReachable();
            entries[i]           = s.getOwnedEntryCount();
            backupEntries[i]     = s.getBackupEntryCount();
            memoryBytes[i]       = s.getOwnedEntryMemoryCost();
            backupMemoryBytes[i] = s.getBackupEntryMemoryCost();
            hits[i]              = s.getHits();
            locked[i]            = s.getLockedEntryCount();
            dirty[i]             = s.getDirtyEntryCount();
            getOpsCum[i]         = s.getGetOps();
            putOpsCum[i]         = s.getPutOps();
            removeOpsCum[i]      = s.getRemoveOps();

            if (i > 0) {
                MapMetricSample p = samples.get(i - 1);
                double secs = Math.max(0.001, (s.getTs() - p.getTs()) / 1000.0);
                // Counter resets (member restart, map rebuild) would produce a negative delta.
                // Clamp to 0 — better to undercount one tick than chart a downward spike.
                getOpsPerSec[i]    = Math.max(0, s.getGetOps()    - p.getGetOps())    / secs;
                putOpsPerSec[i]    = Math.max(0, s.getPutOps()    - p.getPutOps())    / secs;
                removeOpsPerSec[i] = Math.max(0, s.getRemoveOps() - p.getRemoveOps()) / secs;
                totalOpsPerSec[i]  = getOpsPerSec[i] + putOpsPerSec[i] + removeOpsPerSec[i];
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count",                n);
        body.put("ts",                   ts);
        body.put("reachable",            reachable);
        body.put("entries",              entries);
        body.put("backupEntries",        backupEntries);
        body.put("memoryBytes",          memoryBytes);
        body.put("backupMemoryBytes",    backupMemoryBytes);
        body.put("hits",                 hits);
        body.put("lockedEntries",        locked);
        body.put("dirtyEntries",         dirty);
        body.put("getOpsCumulative",     getOpsCum);
        body.put("putOpsCumulative",     putOpsCum);
        body.put("removeOpsCumulative",  removeOpsCum);
        body.put("getOpsPerSec",         getOpsPerSec);
        body.put("putOpsPerSec",         putOpsPerSec);
        body.put("removeOpsPerSec",      removeOpsPerSec);
        body.put("totalOpsPerSec",       totalOpsPerSec);
        return ResponseEntity.ok(body);
    }
}

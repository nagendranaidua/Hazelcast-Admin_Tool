package com.genius.hz.admin.web;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.admin.metrics.MapMetricSample;
import com.genius.hz.admin.metrics.MapMetricsBuffer;
import com.genius.hz.api.MapStats;
import com.genius.hz.bridge.HazelcastBridge;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight read endpoints that back the Maps list page.
 *
 * <p>Browse / get / put / delete for a single map live in {@link MapEntriesController}; this
 * one is just for "what maps exist on this cluster, and how big are they".
 */
@RestController
@RequestMapping("/api/clusters/{id}/maps")
@Tag(name = "Maps", description = "List & summary stats for IMaps on a cluster")
@PreAuthorize("isAuthenticated()")
public class MapsController {

    private final BridgeRouter     router;
    private final MapMetricsBuffer mapBuffer;

    public MapsController(BridgeRouter router, MapMetricsBuffer mapBuffer) {
        this.router    = router;
        this.mapBuffer = mapBuffer;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@PathVariable("id") Long id) {
        HazelcastBridge b = router.bridgeFor(id);
        List<String> names = b.listMapNames();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", names.size());
        body.put("names", names);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{name}/stats")
    public ResponseEntity<MapStats> stats(@PathVariable("id") Long id, @PathVariable("name") String name) {
        HazelcastBridge b = router.bridgeFor(id);
        return ResponseEntity.ok(b.getMapStats(name));
    }

    /**
     * One row per IMap on the cluster, with the most recent sample from MapMetricsBuffer.
     * Lets the maps list page render entries / memory / hits / etc. in a single round-trip
     * instead of N+1 calls. Maps that exist but haven't been sampled yet (within ~30s of
     * being created) get null stats fields — the UI shows "—" for those.
     */
    @GetMapping("/summary")
    public ResponseEntity<List<Map<String, Object>>> summary(@PathVariable("id") Long id) {
        HazelcastBridge b = router.bridgeFor(id);
        List<String> names = b.listMapNames();
        List<Map<String, Object>> out = new ArrayList<>(names.size());
        for (String name : names) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            List<MapMetricSample> samples = mapBuffer.snapshot(id, name, null, null);
            if (!samples.isEmpty()) {
                MapMetricSample s = samples.get(samples.size() - 1);
                row.put("ownedEntries",        s.getOwnedEntryCount());
                row.put("backupEntries",       s.getBackupEntryCount());
                row.put("ownedMemoryBytes",    s.getOwnedEntryMemoryCost());
                row.put("backupMemoryBytes",   s.getBackupEntryMemoryCost());
                row.put("hits",                s.getHits());
                row.put("lockedEntries",       s.getLockedEntryCount());
                row.put("dirtyEntries",        s.getDirtyEntryCount());
                row.put("getOpsCumulative",    s.getGetOps());
                row.put("putOpsCumulative",    s.getPutOps());
                row.put("removeOpsCumulative", s.getRemoveOps());
                row.put("sampleTs",            s.getTs());
                row.put("reachable",           s.isReachable());
            } else {
                // No sample yet — collector hasn't run or just started. Send nulls so the
                // UI can render "—" rather than misleading zeros.
                row.put("ownedEntries",        null);
                row.put("backupEntries",       null);
                row.put("ownedMemoryBytes",    null);
                row.put("backupMemoryBytes",   null);
                row.put("hits",                null);
                row.put("lockedEntries",       null);
                row.put("dirtyEntries",        null);
                row.put("getOpsCumulative",    null);
                row.put("putOpsCumulative",    null);
                row.put("removeOpsCumulative", null);
                row.put("sampleTs",            null);
                row.put("reachable",           null);
            }
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }
}

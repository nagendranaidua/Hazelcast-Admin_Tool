package com.genius.hz.admin.web;

import com.genius.hz.admin.bridge.BridgeRouter;
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
 * Read endpoints that back the Queues list page. Browse / offer / poll for a
 * single queue live in {@link QueueEntriesController}.
 */
@RestController
@RequestMapping("/api/clusters/{id}/queues")
@Tag(name = "Queues", description = "List & summary stats for IQueues on a cluster")
@PreAuthorize("isAuthenticated()")
public class QueuesController {

    private final BridgeRouter router;

    public QueuesController(BridgeRouter router) {
        this.router = router;
    }

    /** List all queue names and count. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@PathVariable("id") Long id) {
        HazelcastBridge b = router.bridgeFor(id);
        List<String> names = b.listQueueNames();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", names.size());
        body.put("names", names);
        return ResponseEntity.ok(body);
    }

    /**
     * One row per IQueue on the cluster with current depth.
     * Mirrors {@link MapsController#summary} for queues.
     */
    @GetMapping("/summary")
    public ResponseEntity<List<Map<String, Object>>> summary(@PathVariable("id") Long id) {
        HazelcastBridge b = router.bridgeFor(id);
        List<String> names = b.listQueueNames();
        List<Map<String, Object>> out = new ArrayList<>(names.size());
        for (String name : names) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            try {
                row.put("size", b.queueSize(name));
            } catch (Exception e) {
                row.put("size", null);
                row.put("error", e.getMessage());
            }
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /** Stats for a single queue. */
    @GetMapping("/{name}/stats")
    public ResponseEntity<Map<String, Object>> stats(@PathVariable("id") Long id,
                                                     @PathVariable("name") String name) {
        HazelcastBridge b = router.bridgeFor(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("size", b.queueSize(name));
        return ResponseEntity.ok(body);
    }
}

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
 * Read endpoints that back the Topics list page.
 * Publish / subscribe operations live in {@link TopicEntriesController}.
 */
@RestController
@RequestMapping("/api/clusters/{id}/topics")
@Tag(name = "Topics", description = "List & summary stats for ITopics on a cluster")
@PreAuthorize("isAuthenticated()")
public class TopicsController {

    private final BridgeRouter router;

    public TopicsController(BridgeRouter router) {
        this.router = router;
    }

    /** List all topic names and count. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@PathVariable("id") Long id) {
        HazelcastBridge b = router.bridgeFor(id);
        List<String> names = b.listTopicNames();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", names.size());
        body.put("names", names);
        return ResponseEntity.ok(body);
    }

    /**
     * One row per ITopic on the cluster.
     * Topics are fire-and-forget in Hazelcast (no retained messages or depth),
     * so we list names only. The UI shows the live subscriber stream on drill-in.
     */
    @GetMapping("/summary")
    public ResponseEntity<List<Map<String, Object>>> summary(@PathVariable("id") Long id) {
        HazelcastBridge b = router.bridgeFor(id);
        List<String> names = b.listTopicNames();
        List<Map<String, Object>> out = new ArrayList<>(names.size());
        for (String name : names) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }
}

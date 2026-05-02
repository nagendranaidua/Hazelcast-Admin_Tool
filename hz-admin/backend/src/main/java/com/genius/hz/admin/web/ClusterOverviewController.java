package com.genius.hz.admin.web;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.admin.domain.Cluster;
import com.genius.hz.admin.repo.ClusterRepository;
import com.genius.hz.api.MemberInfo;
import com.genius.hz.bridge.HazelcastBridge;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated read-only snapshot of one cluster, designed to back the dashboard page.
 * One round-trip per page load: members list + partition state + version metadata.
 */
@RestController
@RequestMapping("/api/clusters/{id}/overview")
@Tag(name = "ClusterOverview", description = "Aggregated cluster snapshot for dashboard")
@PreAuthorize("isAuthenticated()")
public class ClusterOverviewController {

    private final BridgeRouter      router;
    private final ClusterRepository repo;

    public ClusterOverviewController(BridgeRouter router, ClusterRepository repo) {
        this.router = router;
        this.repo   = repo;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> overview(@PathVariable("id") Long id) {
        Cluster c = repo.findById(id).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id",              c.getId());
        body.put("name",            c.getName());
        body.put("environment",     c.getEnvironment());
        body.put("majorVersion",    c.getMajorVersion());
        body.put("prod",            c.isProd());

        try {
            HazelcastBridge b = router.bridgeFor(id);
            List<MemberInfo> members = b.listMembers();
            body.put("connected",       true);
            body.put("clusterName",     b.getClusterName());
            body.put("clusterState",    b.getClusterState());
            body.put("clientVersion",   b.getClientVersion());
            body.put("memberCount",     members.size());
            body.put("partitionCount",  b.getPartitionCount());
            body.put("clusterSafe",     b.isClusterSafe());
            body.put("members",         members);
        } catch (Exception e) {
            body.put("connected",       false);
            body.put("error",           e.getMessage());
        }
        return ResponseEntity.ok(body);
    }
}

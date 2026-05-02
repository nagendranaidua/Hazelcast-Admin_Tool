package com.genius.hz.admin.web;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.admin.domain.Cluster;
import com.genius.hz.admin.repo.ClusterRepository;
import com.genius.hz.bridge.HazelcastBridge;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated multi-cluster health for the ClustersPage cards. One round-trip,
 * one row per registered cluster, with a traffic-light status derived from:
 *   - reachability  (can the bridge connect at all?)
 *   - cluster state (ACTIVE vs PASSIVE/FROZEN/IN_TRANSITION)
 *   - cluster-safe  (partition-owner heuristic — see HazelcastBridge.isClusterSafe)
 *   - member count  (>= 1)
 *
 * <p>Status mapping:
 *   HEALTHY   = connected, state=ACTIVE/UNKNOWN, safe, members >= 1
 *   WARN      = connected but in transition / not safe / single-member / unknown state
 *   CRITICAL  = unreachable (bridge open or any op failed) or members == 0
 *
 * <p>Each per-cluster probe is wrapped in its own try/catch so one bad cluster
 * doesn't poison the response. Latency is bounded by the BridgeRouter's connect
 * budget (3 attempts × 2s default = 6s per unreachable cluster).
 */
@RestController
@RequestMapping("/api/clusters")
@Tag(name = "ClusterHealth", description = "Multi-cluster health summary for the dashboard")
@PreAuthorize("isAuthenticated()")
public class ClusterHealthController {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterHealthController.class);

    private final ClusterRepository repo;
    private final BridgeRouter      router;

    public ClusterHealthController(ClusterRepository repo, BridgeRouter router) {
        this.repo   = repo;
        this.router = router;
    }

    @GetMapping("/health-summary")
    public List<Map<String, Object>> healthSummary() {
        List<Cluster> all = repo.findAll();
        List<Map<String, Object>> out = new ArrayList<>(all.size());
        for (Cluster c : all) {
            out.add(probe(c));
        }
        return out;
    }

    /**
     * Probe a single cluster. Never throws — a cluster that can't be reached produces a
     * CRITICAL row with the leaf cause as message; that's exactly what the UI wants to render.
     */
    private Map<String, Object> probe(Cluster c) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id",            c.getId());
        row.put("name",          c.getName());
        row.put("environment",   c.getEnvironment());
        row.put("majorVersion",  c.getMajorVersion());
        row.put("prod",          c.isProd());
        row.put("enabled",       c.isEnabled());

        if (!c.isEnabled()) {
            row.put("status",    "DISABLED");
            row.put("message",   "Cluster registration is disabled.");
            row.put("memberCount", 0);
            row.put("clusterSafe", false);
            row.put("clusterState", "DISABLED");
            return row;
        }

        try {
            HazelcastBridge b = router.bridgeFor(c.getId());
            int members        = b.listMembers().size();
            String state       = b.getClusterState();        // returns "UNKNOWN" if not exposed
            boolean safe       = b.isClusterSafe();          // partition-owner heuristic
            String  health     = classify(members, state, safe);

            row.put("status",      health);
            row.put("memberCount", members);
            row.put("clusterState", state);
            row.put("clusterSafe", safe);
            row.put("message",     buildMessage(health, members, state, safe));
        } catch (Exception e) {
            LOG.debug("health probe failed for cluster id={} ({}): {}",
                      c.getId(), c.getName(), e.toString());
            row.put("status",      "CRITICAL");
            row.put("memberCount", 0);
            row.put("clusterState", "UNREACHABLE");
            row.put("clusterSafe", false);
            row.put("message",     leafMessage(e));
        }
        return row;
    }

    /** Map (members, state, safe) -> traffic-light status. */
    private static String classify(int members, String state, boolean safe) {
        if (members == 0)             return "CRITICAL";
        boolean stateOk = state == null
                       || "ACTIVE".equals(state)
                       || "UNKNOWN".equals(state);   // can't be confirmed; don't penalise
        if (!stateOk || !safe)        return "WARN";
        if (members == 1)             return "WARN";   // single-member cluster: no HA
        return "HEALTHY";
    }

    private static String buildMessage(String health, int members, String state, boolean safe) {
        StringBuilder sb = new StringBuilder();
        sb.append(members).append(" member").append(members == 1 ? "" : "s");
        sb.append(", state=").append(state);
        if (!safe) sb.append(", partitions not yet safe");
        if ("WARN".equals(health) && members == 1) sb.append(", single-member (no HA)");
        return sb.toString();
    }

    /** Surface the deepest cause message — it's the one with operational signal. */
    private static String leafMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }
}

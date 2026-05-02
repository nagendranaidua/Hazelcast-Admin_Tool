package com.genius.hz.admin.web;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.admin.domain.Cluster;
import com.genius.hz.admin.repo.ClusterRepository;
import com.genius.hz.admin.service.AuditService;
import com.genius.hz.admin.domain.AuditEvent;
import com.genius.hz.api.MapEntryView;
import com.genius.hz.bridge.HazelcastBridge;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browse (peek), offer, poll and drain items from an IQueue.
 * Read operations are open to any authenticated user; writes require operator roles.
 */
@RestController
@RequestMapping("/api/clusters/{id}/queues/{name}/entries")
@Tag(name = "QueueEntries", description = "Browse, offer, poll and drain IQueue items")
@PreAuthorize("isAuthenticated()")
public class QueueEntriesController {

    private final BridgeRouter      router;
    private final ClusterRepository clusters;
    private final AuditService      audit;

    public QueueEntriesController(BridgeRouter router, ClusterRepository clusters, AuditService audit) {
        this.router   = router;
        this.clusters = clusters;
        this.audit    = audit;
    }

    /** Non-destructive peek at queue contents (up to limit items from head). */
    @GetMapping("/peek")
    public ResponseEntity<Map<String, Object>> peek(@PathVariable("id") Long id,
                                                    @PathVariable("name") String name,
                                                    @RequestParam(value = "limit", defaultValue = "100") int limit) {
        HazelcastBridge b = router.bridgeFor(id);
        int safe = Math.max(1, Math.min(limit, 500));
        List<MapEntryView> items = b.queuePeek(name, safe);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("queueName", name);
        body.put("size", b.queueSize(name));
        body.put("peekedCount", items.size());
        body.put("items", items);
        return ResponseEntity.ok(body);
    }

    /** Offer (enqueue) a JSON value. Audited. */
    @PostMapping("/offer")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR')")
    public ResponseEntity<Map<String, Object>> offer(@PathVariable("id") Long id,
                                                     @PathVariable("name") String name,
                                                     @RequestBody OfferBody body,
                                                     HttpServletRequest http) {
        Cluster c = mustFindCluster(id);
        guardProdWrite(c);
        requireReason(body.reason);

        AuditEvent ev = audit.record("QUEUE_OFFER", c.getName(), name,
                body.reason, body, http.getRemoteAddr());
        try {
            HazelcastBridge b = router.bridgeFor(id);
            b.queueOfferJson(name, body.value);
            audit.complete(ev.getId(), "SUCCESS", "offered 1 item");
        } catch (RuntimeException e) {
            audit.complete(ev.getId(), "FAILED", e.getMessage());
            throw e;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("auditId", ev.getId());
        return ResponseEntity.ok(out);
    }

    /** Poll (destructive read) up to count items from head. Audited. */
    @PostMapping("/poll")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR')")
    public ResponseEntity<Map<String, Object>> poll(@PathVariable("id") Long id,
                                                    @PathVariable("name") String name,
                                                    @RequestBody PollBody body,
                                                    HttpServletRequest http) {
        Cluster c = mustFindCluster(id);
        guardProdWrite(c);
        requireReason(body.reason);

        int count = Math.max(1, Math.min(body.count, 100));

        AuditEvent ev = audit.record("QUEUE_POLL", c.getName(), name,
                body.reason, body, http.getRemoteAddr());
        try {
            HazelcastBridge b = router.bridgeFor(id);
            List<MapEntryView> polled = b.queuePoll(name, count);
            audit.complete(ev.getId(), "SUCCESS", "polled " + polled.size() + " items");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("polledCount", polled.size());
            out.put("items", polled);
            out.put("auditId", ev.getId());
            return ResponseEntity.ok(out);
        } catch (RuntimeException e) {
            audit.complete(ev.getId(), "FAILED", e.getMessage());
            throw e;
        }
    }

    /** Drain all items from the queue. Destructive. Audited. */
    @PostMapping("/drain")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR')")
    public ResponseEntity<Map<String, Object>> drain(@PathVariable("id") Long id,
                                                     @PathVariable("name") String name,
                                                     @RequestBody ReasonBody body,
                                                     HttpServletRequest http) {
        Cluster c = mustFindCluster(id);
        guardProdWrite(c);
        requireReason(body.reason);

        AuditEvent ev = audit.record("QUEUE_DRAIN", c.getName(), name,
                body.reason, body, http.getRemoteAddr());
        try {
            HazelcastBridge b = router.bridgeFor(id);
            List<MapEntryView> drained = b.queueDrain(name);
            audit.complete(ev.getId(), "SUCCESS", "drained " + drained.size() + " items");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("drainedCount", drained.size());
            out.put("items", drained);
            out.put("auditId", ev.getId());
            return ResponseEntity.ok(out);
        } catch (RuntimeException e) {
            audit.complete(ev.getId(), "FAILED", e.getMessage());
            throw e;
        }
    }

    // ---- helpers ----------------------------------------------------------------

    private Cluster mustFindCluster(Long id) {
        return clusters.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown cluster id: " + id));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().isEmpty())
            throw new IllegalArgumentException("reason is required for write operations");
        if (reason.length() > 1000)
            throw new IllegalArgumentException("reason must be 1000 characters or fewer");
    }

    private static void guardProdWrite(Cluster c) {
        if (!c.isProd()) return;
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        boolean superAdmin = false;
        if (a != null) {
            for (GrantedAuthority ga : a.getAuthorities()) {
                if ("ROLE_SUPER_ADMIN".equals(ga.getAuthority())) { superAdmin = true; break; }
            }
        }
        if (!superAdmin) {
            throw new AccessDeniedException(
                "Cluster '" + c.getName() + "' is marked production; only SUPER_ADMIN can write to it.");
        }
    }

    // ---- request bodies ---------------------------------------------------------
    public static class OfferBody {
        public String value;
        public String reason;
    }
    public static class PollBody {
        public int    count = 1;
        public String reason;
    }
    public static class ReasonBody {
        public String reason;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}

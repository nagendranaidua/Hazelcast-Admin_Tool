package com.genius.hz.admin.web;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.admin.domain.AuditEvent;
import com.genius.hz.admin.domain.Cluster;
import com.genius.hz.admin.repo.ClusterRepository;
import com.genius.hz.admin.service.AuditService;
import com.genius.hz.api.MapEntryView;
import com.genius.hz.bridge.HazelcastBridge;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publish to and subscribe (live SSE stream) from an ITopic on a remote cluster.
 */
@RestController
@RequestMapping("/api/clusters/{id}/topics/{name}")
@Tag(name = "TopicEntries", description = "Publish and subscribe to ITopic messages")
@PreAuthorize("isAuthenticated()")
public class TopicEntriesController {

    private static final Logger LOG = LoggerFactory.getLogger(TopicEntriesController.class);

    private final BridgeRouter      router;
    private final ClusterRepository clusters;
    private final AuditService      audit;

    // Track active SSE subscriptions so we can clean up bridge listeners
    private final Map<SseEmitter, SubInfo> activeSubscriptions = new ConcurrentHashMap<>();

    public TopicEntriesController(BridgeRouter router, ClusterRepository clusters, AuditService audit) {
        this.router   = router;
        this.clusters = clusters;
        this.audit    = audit;
    }

    /** Publish a JSON message to the topic. Audited. */
    @PostMapping("/publish")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR')")
    public ResponseEntity<Map<String, Object>> publish(@PathVariable("id") Long id,
                                                       @PathVariable("name") String name,
                                                       @RequestBody PublishBody body,
                                                       HttpServletRequest http) {
        Cluster c = mustFindCluster(id);
        guardProdWrite(c);
        requireReason(body.reason);

        AuditEvent ev = audit.record("TOPIC_PUBLISH", c.getName(), name,
                body.reason, body, http.getRemoteAddr());
        try {
            HazelcastBridge b = router.bridgeFor(id);
            b.topicPublishJson(name, body.value);
            audit.complete(ev.getId(), "SUCCESS", "published to topic " + name);
        } catch (RuntimeException e) {
            audit.complete(ev.getId(), "FAILED", e.getMessage());
            throw e;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("auditId", ev.getId());
        return ResponseEntity.ok(out);
    }

    /**
     * SSE stream that forwards every message published to this topic in real time.
     * The browser opens an EventSource to this endpoint and receives:
     * <ul>
     *   <li>{@code event: message} — each published item as MapEntryView JSON</li>
     *   <li>{@code event: subscribed} — confirmation with subscription id</li>
     * </ul>
     * Closing the EventSource (or network drop) automatically unsubscribes.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("id") Long id,
                             @PathVariable("name") String name) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout

        try {
            HazelcastBridge b = router.bridgeFor(id);
            String subId = b.topicSubscribe(name, (MapEntryView view) -> {
                try {
                    emitter.send(SseEmitter.event().name("message").data(view));
                } catch (IOException e) {
                    // Client disconnected — cleanup will fire via onCompletion/onError
                }
            });

            SubInfo info = new SubInfo(id, name, subId);
            activeSubscriptions.put(emitter, info);

            // Send confirmation
            Map<String, String> confirmation = new LinkedHashMap<>();
            confirmation.put("subscriptionId", subId);
            confirmation.put("topicName", name);
            emitter.send(SseEmitter.event().name("subscribed").data(confirmation));

            // Cleanup on disconnect
            Runnable cleanup = () -> {
                activeSubscriptions.remove(emitter);
                try { router.bridgeFor(id).topicUnsubscribe(name, subId); }
                catch (Exception ignored) {}
            };
            emitter.onCompletion(cleanup);
            emitter.onTimeout(cleanup);
            emitter.onError(t -> cleanup.run());

        } catch (Exception e) {
            try { emitter.send(SseEmitter.event().name("error").data(e.getMessage())); }
            catch (IOException ignored) {}
            emitter.complete();
        }

        return emitter;
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

    // ---- inner types ------------------------------------------------------------
    public static class PublishBody {
        public String value;
        public String reason;
    }

    private static class SubInfo {
        final long clusterId;
        final String topicName;
        final String subscriptionId;
        SubInfo(long c, String t, String s) { clusterId = c; topicName = t; subscriptionId = s; }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}

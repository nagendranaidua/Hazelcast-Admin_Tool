package com.genius.hz.admin.web;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.admin.domain.AuditEvent;
import com.genius.hz.admin.domain.Cluster;
import com.genius.hz.admin.repo.ClusterRepository;
import com.genius.hz.admin.service.AuditService;
import com.genius.hz.api.MapBrowsePage;
import com.genius.hz.api.MapEntryView;
import com.genius.hz.bridge.HazelcastBridge;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Map;

/**
 * Browse, look-up, edit, and delete IMap entries on a remote cluster.
 *
 * <p>Read endpoints (browse, get) are open to any authenticated user. Write endpoints
 * (put, delete) require {@code SUPER_ADMIN} or {@code CLUSTER_OPERATOR}; further, writes
 * to a cluster marked {@code prod=true} are restricted to {@code SUPER_ADMIN} as a
 * blast-radius guard. Both writes require a non-empty {@code reason} which is recorded
 * in the audit log along with the (best-effort) before/after value JSON.
 */
@RestController
@RequestMapping("/api/clusters/{id}/maps/{name}/entry")
@Tag(name = "MapEntries", description = "Browse, lookup, edit, and delete IMap entries")
@PreAuthorize("isAuthenticated()")
public class MapEntriesController {

    private static final Logger LOG = LoggerFactory.getLogger(MapEntriesController.class);

    private final BridgeRouter      router;
    private final ClusterRepository clusters;
    private final AuditService      audit;

    public MapEntriesController(BridgeRouter router, ClusterRepository clusters, AuditService audit) {
        this.router   = router;
        this.clusters = clusters;
        this.audit    = audit;
    }

    /** Paginated browse. {@code includeValues=false} returns just keys (cheap autocomplete). */
    @GetMapping("/browse")
    public ResponseEntity<MapBrowsePage> browse(@PathVariable("id") Long id,
                                                @PathVariable("name") String name,
                                                @RequestParam(value = "pageSize",      defaultValue = "50")    int pageSize,
                                                @RequestParam(value = "pageIndex",     defaultValue = "0")     int pageIndex,
                                                @RequestParam(value = "includeValues", defaultValue = "true")  boolean includeValues) {
        HazelcastBridge b = router.bridgeFor(id);
        // Bound the page size both ways to limit blast radius from a malformed request.
        int safe = Math.max(1, Math.min(pageSize, 500));
        return ResponseEntity.ok(b.browseMap(name, safe, Math.max(0, pageIndex), includeValues));
    }

    /** Look up a single entry by JSON-encoded key. */
    @GetMapping
    public ResponseEntity<MapEntryView> get(@PathVariable("id") Long id,
                                            @PathVariable("name") String name,
                                            @RequestParam("key") String keyJson) {
        HazelcastBridge b = router.bridgeFor(id);
        return ResponseEntity.ok(b.mapGet(name, keyJson));
    }

    /** Upsert a single entry. Audited. */
    @PutMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR')")
    public ResponseEntity<Map<String, Object>> put(@PathVariable("id") Long id,
                                                   @PathVariable("name") String name,
                                                   @RequestBody PutBody body,
                                                   HttpServletRequest http) {
        Cluster c = mustFindCluster(id);
        guardProdWrite(c);
        requireReason(body.reason);

        // Capture "before" so the audit row carries both states. Best-effort — null if missing
        // or unreadable; not a failure mode for the put.
        String beforeJson = null;
        try { beforeJson = router.bridgeFor(id).mapGet(name, body.key).getValueJson(); }
        catch (Exception e) { LOG.debug("audit-before fetch failed: {}", e.toString()); }

        AuditEvent ev = audit.record("MAP_PUT", c.getName(), name + "/" + truncate(body.key, 200),
                body.reason, body, http.getRemoteAddr());
        try {
            HazelcastBridge b = router.bridgeFor(id);
            b.mapPutJson(name, body.key, body.value, body.ttlMs);
            ev.setReason(body.reason); // already set
            ev.setErrorMessage(null);
            audit.complete(ev.getId(), "SUCCESS", buildBeforeAfter(beforeJson, body.value));
        } catch (RuntimeException e) {
            audit.complete(ev.getId(), "FAILED", e.getMessage());
            throw e;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("auditId", ev.getId());
        return ResponseEntity.ok(out);
    }

    /** Remove a single entry. Audited. */
    @DeleteMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR')")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable("id") Long id,
                                                      @PathVariable("name") String name,
                                                      @RequestBody DeleteBody body,
                                                      HttpServletRequest http) {
        Cluster c = mustFindCluster(id);
        guardProdWrite(c);
        requireReason(body.reason);

        String beforeJson = null;
        try { beforeJson = router.bridgeFor(id).mapGet(name, body.key).getValueJson(); }
        catch (Exception e) { LOG.debug("audit-before fetch failed: {}", e.toString()); }

        AuditEvent ev = audit.record("MAP_REMOVE", c.getName(), name + "/" + truncate(body.key, 200),
                body.reason, body, http.getRemoteAddr());
        boolean removed;
        try {
            HazelcastBridge b = router.bridgeFor(id);
            removed = b.mapRemove(name, body.key);
            audit.complete(ev.getId(), "SUCCESS", buildBeforeAfter(beforeJson, null));
        } catch (RuntimeException e) {
            audit.complete(ev.getId(), "FAILED", e.getMessage());
            throw e;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("removed", removed);
        out.put("auditId", ev.getId());
        return ResponseEntity.ok(out);
    }

    // ---- helpers ----------------------------------------------------------------

    private Cluster mustFindCluster(Long id) {
        return clusters.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown cluster id: " + id));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("reason is required for write operations");
        }
        if (reason.length() > 1000) {
            throw new IllegalArgumentException("reason must be 1000 characters or fewer");
        }
    }

    /** Block writes against PROD clusters unless caller is SUPER_ADMIN. */
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

    /** Squashes very long keys for the audit-target column. */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Encodes before/after into the {@code error_message} TEXT column to avoid a schema change. */
    private static String buildBeforeAfter(String before, String after) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("before=").append(before == null ? "<null>" : before);
        sb.append("\nafter=") .append(after  == null ? "<null>" : after);
        return sb.toString();
    }

    public static class PutBody {
        public String key;
        public String value;
        public Long   ttlMs;
        public String reason;
    }
    public static class DeleteBody {
        public String key;
        public String reason;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}

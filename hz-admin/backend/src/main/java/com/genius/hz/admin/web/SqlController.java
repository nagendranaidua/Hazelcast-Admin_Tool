package com.genius.hz.admin.web;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.admin.domain.AuditEvent;
import com.genius.hz.admin.domain.Cluster;
import com.genius.hz.admin.repo.ClusterRepository;
import com.genius.hz.admin.service.AuditService;
import com.genius.hz.admin.sql.SqlCursorRegistry;
import com.genius.hz.api.SqlPage;
import com.genius.hz.bridge.HazelcastBridge;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQL console endpoints. Two pagination modes are exposed and chosen by the request:
 *
 * <ul>
 *   <li>{@code STREAM} — bridge keeps the {@code SqlResult} open. Returns a {@code cursorId}
 *       on the first page; subsequent pages come from {@code POST .../sql/{cursorId}/next}.
 *       Preserves ORDER BY semantics across pages, but the cursor is reaped after 5 minutes
 *       of inactivity.</li>
 *   <li>{@code LIMIT} — bridge appends {@code LIMIT N OFFSET M} (only if the user's query
 *       doesn't already include LIMIT) and re-executes per page. Simpler protocol; ORDER BY
 *       only stable if the underlying data didn't change between pages.</li>
 * </ul>
 *
 * <p>Every executed query (success or failure) gets an audit row tagged {@code SQL_QUERY}.
 */
@RestController
@RequestMapping("/api/clusters/{id}/sql")
@Tag(name = "SqlConsole", description = "Run SQL queries against a Hazelcast 4.2+ / 5.x cluster")
@PreAuthorize("isAuthenticated()")
public class SqlController {

    private static final Logger LOG = LoggerFactory.getLogger(SqlController.class);

    private final BridgeRouter      router;
    private final ClusterRepository clusters;
    private final AuditService      audit;
    private final SqlCursorRegistry registry;

    public SqlController(BridgeRouter router, ClusterRepository clusters,
                         AuditService audit, SqlCursorRegistry registry) {
        this.router   = router;
        this.clusters = clusters;
        this.audit    = audit;
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<SqlPage> execute(@PathVariable("id") Long id,
                                           @RequestBody RunBody body,
                                           HttpServletRequest http) {
        validate(body);
        Cluster c = mustFindCluster(id);
        String mode = body.mode == null ? "STREAM" : body.mode.toUpperCase();

        AuditEvent ev = audit.record("SQL_QUERY", c.getName(),
                "mode=" + mode + "; pageSize=" + body.pageSize,
                /* reason: not required for read */ body.reason,
                body, http.getRemoteAddr());
        try {
            HazelcastBridge b = router.bridgeFor(id);
            SqlPage page;
            if ("LIMIT".equals(mode)) {
                int limit  = Math.max(1, Math.min(body.pageSize, 1000));
                int offset = Math.max(0, body.offset == null ? 0 : body.offset);
                page = b.runSqlLimitOffset(body.query, limit, offset);
            } else {
                int pageSize = Math.max(1, Math.min(body.pageSize, 1000));
                page = b.runSqlStreaming(body.query, pageSize);
                if (page.getCursorId() != null) {
                    registry.register(page.getCursorId(), id, currentUser());
                }
            }
            audit.complete(ev.getId(), "SUCCESS",
                    "rows=" + page.getRows().size() + "; elapsedMs=" + page.getElapsedMs());
            return ResponseEntity.ok(page);
        } catch (RuntimeException e) {
            audit.complete(ev.getId(), "FAILED", e.getMessage());
            throw e;
        }
    }

    @PostMapping("/{cursorId}/next")
    public ResponseEntity<SqlPage> next(@PathVariable("id") Long id,
                                        @PathVariable("cursorId") String cursorId,
                                        @RequestBody NextBody body) {
        SqlCursorRegistry.Entry e = registry.get(cursorId);
        if (e == null) throw new IllegalArgumentException("cursor not found or expired");
        if (e.clusterId != id) throw new IllegalArgumentException("cursor belongs to a different cluster");
        if (!e.owner.equals(currentUser())) throw new AccessDeniedException("cursor not owned by current user");

        int pageSize = Math.max(1, Math.min(body.pageSize, 1000));
        e.lock.lock();
        try {
            HazelcastBridge b = router.bridgeFor(id);
            SqlPage page = b.fetchSqlPage(cursorId, pageSize);
            if (page.isDone()) registry.release(cursorId);
            return ResponseEntity.ok(page);
        } finally {
            e.lock.unlock();
        }
    }

    @DeleteMapping("/{cursorId}")
    public ResponseEntity<Void> close(@PathVariable("id") Long id,
                                      @PathVariable("cursorId") String cursorId) {
        SqlCursorRegistry.Entry e = registry.get(cursorId);
        if (e != null) {
            if (e.clusterId != id || !e.owner.equals(currentUser())) {
                throw new AccessDeniedException("cursor not owned by current user");
            }
            e.lock.lock();
            try {
                router.bridgeFor(id).closeSqlCursor(cursorId);
            } finally {
                e.lock.unlock();
                registry.release(cursorId);
            }
        }
        return ResponseEntity.noContent().build();
    }

    private static void validate(RunBody body) {
        if (body == null || body.query == null || body.query.trim().isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }
        if (body.query.length() > 50_000) {
            throw new IllegalArgumentException("query too long (50k char cap)");
        }
        if (body.pageSize <= 0) body.pageSize = 100;
    }

    private Cluster mustFindCluster(Long id) {
        return clusters.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown cluster id: " + id));
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "anonymous" : a.getName();
    }

    public static class RunBody {
        public String  query;
        public int     pageSize;
        public String  mode;     // STREAM | LIMIT
        public Integer offset;   // LIMIT mode only
        public String  reason;   // optional context for the audit row
    }
    public static class NextBody {
        public int pageSize;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}

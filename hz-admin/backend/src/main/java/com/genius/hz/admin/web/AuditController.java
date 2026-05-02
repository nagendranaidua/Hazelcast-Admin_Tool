package com.genius.hz.admin.web;

import com.genius.hz.admin.domain.AuditEvent;
import com.genius.hz.admin.repo.AuditEventRepository;
import com.genius.hz.api.AuditEventDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only feed of the {@code audit_events} table. Restricted to {@code SUPER_ADMIN}
 * because audit rows can include before/after value JSON for IMap edits, which can be
 * sensitive (PII, credentials, etc).
 *
 * <p>Filters are simple equality matches; if richer searching is needed (free-text reason,
 * date range, outcome), Phase 5 plans a JPA Specification + saved-views UI.
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "Audit", description = "Read audit events (SUPER_ADMIN)")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AuditController {

    private final AuditEventRepository repo;

    public AuditController(AuditEventRepository repo) { this.repo = repo; }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(value = "page",    defaultValue = "0")   int page,
            @RequestParam(value = "size",    defaultValue = "50")  int size,
            @RequestParam(value = "actor",   required = false)     String actor,
            @RequestParam(value = "cluster", required = false)     String cluster) {

        int safeSize = Math.max(1, Math.min(size, 500));
        PageRequest pr = PageRequest.of(Math.max(0, page), safeSize,
                Sort.by(Sort.Direction.DESC, "occurredAt"));

        Page<AuditEvent> p;
        if (actor != null && !actor.isEmpty())        p = repo.findByActor(actor, pr);
        else if (cluster != null && !cluster.isEmpty()) p = repo.findByClusterName(cluster, pr);
        else                                            p = repo.findAll(pr);

        List<AuditEventDto> items = p.getContent().stream()
                .map(AuditController::toDto)
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("page",       p.getNumber());
        body.put("size",       p.getSize());
        body.put("totalPages", p.getTotalPages());
        body.put("totalCount", p.getTotalElements());
        body.put("items",      items);
        return ResponseEntity.ok(body);
    }

    private static AuditEventDto toDto(AuditEvent e) {
        return AuditEventDto.builder()
                .id(e.getId())
                .timestamp(e.getOccurredAt())
                .actor(e.getActor())
                .actorRole(e.getActorRole())
                .cluster(e.getClusterName())
                .action(e.getAction())
                .target(e.getTarget())
                .reason(e.getReason())
                .requestHash(e.getRequestHash())
                .outcome(e.getOutcome())
                .errorMessage(e.getErrorMessage())   // NB: also carries before/after JSON for MAP_PUT/MAP_REMOVE
                .build();
    }
}

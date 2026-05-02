package com.genius.hz.admin.service;

import com.genius.hz.admin.domain.AuditEvent;
import com.genius.hz.admin.repo.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.stream.Collectors;

/**
 * Writes audit rows in REQUIRES_NEW so they survive a rollback of the calling transaction.
 * Always writes the row BEFORE the privileged action runs; the caller updates outcome
 * after the op succeeds/fails via {@link #complete(Long, String, String)}.
 */
@Service
public class AuditService {

    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repo;

    public AuditService(AuditEventRepository repo) { this.repo = repo; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent record(String action, String clusterName, String target,
                             String reason, Object requestPayload, String sourceIp) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        String actor = a == null ? "anonymous" : a.getName();
        String role = a == null ? null : a.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.joining(","));

        AuditEvent ev = AuditEvent.builder()
                .actor(actor)
                .actorRole(role)
                .clusterName(clusterName)
                .action(action)
                .target(target)
                .reason(reason)
                .requestHash(sha256(String.valueOf(requestPayload)))
                .outcome("PENDING")
                .sourceIp(sourceIp)
                .build();
        return repo.save(ev);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long id, String outcome, String errorMessage) {
        repo.findById(id).ifPresent(ev -> {
            ev.setOutcome(outcome);
            ev.setErrorMessage(errorMessage);
            repo.save(ev);
        });
    }

    private static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            LOG.warn("sha256 failed: {}", e.toString());
            return null;
        }
    }
}

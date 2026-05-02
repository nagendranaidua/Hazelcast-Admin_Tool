package com.genius.hz.admin.web;

import com.genius.hz.admin.bridge.BridgeRouter;
import com.genius.hz.admin.domain.AuditEvent;
import com.genius.hz.admin.domain.Cluster;
import com.genius.hz.admin.repo.ClusterRepository;
import com.genius.hz.admin.service.AuditService;
import com.genius.hz.admin.service.CryptoService;
import com.genius.hz.bridge.HazelcastBridge;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/clusters")
@Tag(name = "Clusters", description = "Multi-cluster registry: register, list, test connection")
public class ClusterController {

    private final ClusterRepository repo;
    private final CryptoService     crypto;
    private final BridgeRouter      router;
    private final AuditService      audit;

    public ClusterController(ClusterRepository r, CryptoService c, BridgeRouter b, AuditService a) {
        this.repo = r; this.crypto = c; this.router = b; this.audit = a;
    }

    @GetMapping
    public List<Map<String,Object>> list() {
        return repo.findAll().stream().map(ClusterController::dto).collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String,Object>> get(@PathVariable Long id) {
        return repo.findById(id).map(c -> ResponseEntity.ok(dto(c))).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR')")
    public ResponseEntity<Map<String,Object>> register(@Valid @RequestBody RegisterRequest req,
                                                       HttpServletRequest http) {
        AuditEvent ev = audit.record("CLUSTER_REGISTER", req.name, req.name,
                "register cluster", req, http.getRemoteAddr());
        try {
            Cluster c = Cluster.builder()
                .name(req.name)
                .environment(req.environment)
                .hzClusterName(req.hzClusterName)
                .majorVersion(req.majorVersion)
                .memberAddresses(String.join(",", req.memberAddresses))
                .securityMode(req.securityMode)
                .username(req.username)
                .passwordCipher(crypto.encrypt(req.password))
                .truststorePath(req.truststorePath)
                .truststorePwdCipher(crypto.encrypt(req.truststorePassword))
                .keystorePath(req.keystorePath)
                .keystorePwdCipher(crypto.encrypt(req.keystorePassword))
                .tlsProtocol(req.tlsProtocol)
                .prod(req.prod)
                .enabled(true)
                .build();
            Cluster saved = repo.save(c);
            audit.complete(ev.getId(), "SUCCESS", null);
            return ResponseEntity.status(201).body(dto(saved));
        } catch (Exception e) {
            audit.complete(ev.getId(), "FAILED", e.getMessage());
            throw e;
        }
    }

    @PostMapping("/{id}/test-connection")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','CLUSTER_OPERATOR','DEVELOPER')")
    public Map<String,Object> testConnection(@PathVariable Long id) {
        Map<String,Object> r = new LinkedHashMap<>();
        try {
            HazelcastBridge br = router.bridgeFor(id);
            r.put("connected",    br.isConnected());
            r.put("clusterState", br.getClusterState());
            r.put("memberCount",  br.listMembers().size());
        } catch (Exception e) {
            r.put("connected", false);
            r.put("error",     e.getMessage());
        }
        return r;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestParam(name = "reason", required = false) String reason,
                                       HttpServletRequest http) {
        // Defensive: reason is optional from a contract perspective, but the UI requires it,
        // and we want the audit row to carry it verbatim. Trim and cap to avoid a runaway log.
        String safeReason = reason == null ? "" : reason.trim();
        if (safeReason.length() > 1000) safeReason = safeReason.substring(0, 1000);
        final String auditReason = safeReason.isEmpty() ? "delete cluster" : safeReason;

        repo.findById(id).ifPresent(c -> {
            AuditEvent ev = audit.record("CLUSTER_DELETE", c.getName(), c.getName(),
                    auditReason, id, http.getRemoteAddr());
            try {
                router.evict(id);
                repo.delete(c);
                audit.complete(ev.getId(), "SUCCESS", null);
            } catch (Exception e) {
                audit.complete(ev.getId(), "FAILED", e.getMessage());
                throw e;
            }
        });
        return ResponseEntity.noContent().build();
    }

    static Map<String,Object> dto(Cluster c) {
        Map<String,Object> m = new LinkedHashMap<>();
        m.put("id",              c.getId());
        m.put("name",            c.getName());
        m.put("environment",     c.getEnvironment());
        m.put("hzClusterName",   c.getHzClusterName());
        m.put("majorVersion",    c.getMajorVersion());
        m.put("memberAddresses", Arrays.asList(c.getMemberAddresses().split(",")));
        m.put("securityMode",    c.getSecurityMode());
        m.put("username",        c.getUsername());
        m.put("tlsProtocol",     c.getTlsProtocol());
        m.put("prod",            c.isProd());
        m.put("enabled",         c.isEnabled());
        // never echo any *_cipher field back to the UI
        return m;
    }

    @Data public static class RegisterRequest {
        @NotBlank private String name;
        @NotBlank private String environment;
        @NotBlank private String hzClusterName;
        @NotBlank private String majorVersion;          // "5.1" | "5.2"
        private List<String> memberAddresses;
        @NotBlank private String securityMode;          // PLAIN / TLS / MTLS
        private String username;
        private String password;
        private String truststorePath;
        private String truststorePassword;
        private String keystorePath;
        private String keystorePassword;
        private String tlsProtocol;
        private boolean prod;
    }
}

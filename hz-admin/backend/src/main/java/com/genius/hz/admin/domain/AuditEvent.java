package com.genius.hz.admin.domain;

import lombok.*;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditEvent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private Instant occurredAt = Instant.now();

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(name = "actor_role", length = 64)
    private String actorRole;

    @Column(name = "cluster_name", length = 128)
    private String clusterName;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 512)
    private String target;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(nullable = false, length = 16)
    private String outcome;        // SUCCESS / DENIED / FAILED

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "source_ip", length = 64)
    private String sourceIp;
}

package com.genius.hz.admin.domain;

import lombok.*;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "clusters")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Cluster {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String environment;

    @Column(name = "hz_cluster_name", nullable = false, length = 128)
    private String hzClusterName;

    @Column(name = "major_version", nullable = false, length = 8)
    private String majorVersion;             // "5.1" | "5.2"

    @Column(name = "member_addresses", nullable = false, columnDefinition = "TEXT")
    private String memberAddresses;          // comma-separated host:port

    @Column(name = "security_mode", nullable = false, length = 16)
    private String securityMode;             // PLAIN / TLS / MTLS

    private String username;

    @Column(name = "password_cipher", columnDefinition = "TEXT")
    private String passwordCipher;

    @Column(name = "truststore_path", columnDefinition = "TEXT")
    private String truststorePath;

    @Column(name = "truststore_pwd_cipher", columnDefinition = "TEXT")
    private String truststorePwdCipher;

    @Column(name = "keystore_path", columnDefinition = "TEXT")
    private String keystorePath;

    @Column(name = "keystore_pwd_cipher", columnDefinition = "TEXT")
    private String keystorePwdCipher;

    @Column(name = "tls_protocol", length = 16)
    private String tlsProtocol;

    @Column(name = "is_prod", nullable = false)
    @Builder.Default
    private boolean prod = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}

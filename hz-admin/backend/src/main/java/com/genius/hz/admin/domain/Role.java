package com.genius.hz.admin.domain;

import lombok.*;

import javax.persistence.*;

@Entity
@Table(name = "roles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;          // SUPER_ADMIN, CLUSTER_OPERATOR, DEVELOPER, READ_ONLY

    @Column(length = 255)
    private String description;

    public static final String SUPER_ADMIN      = "SUPER_ADMIN";
    public static final String CLUSTER_OPERATOR = "CLUSTER_OPERATOR";
    public static final String DEVELOPER        = "DEVELOPER";
    public static final String READ_ONLY        = "READ_ONLY";
}

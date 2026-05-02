package com.genius.hz.admin.repo;

import com.genius.hz.admin.domain.Cluster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClusterRepository extends JpaRepository<Cluster, Long> {
    Optional<Cluster> findByName(String name);
    List<Cluster> findByEnabledTrue();
    List<Cluster> findByEnvironment(String environment);
}

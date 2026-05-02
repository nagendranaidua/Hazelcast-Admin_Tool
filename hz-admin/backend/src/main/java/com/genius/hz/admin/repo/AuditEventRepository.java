package com.genius.hz.admin.repo;

import com.genius.hz.admin.domain.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    Page<AuditEvent> findByActor(String actor, Pageable pageable);
    Page<AuditEvent> findByClusterName(String clusterName, Pageable pageable);
}

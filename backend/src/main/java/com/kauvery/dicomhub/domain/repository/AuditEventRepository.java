package com.kauvery.dicomhub.domain.repository;

import com.kauvery.dicomhub.domain.entity.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findTop100BySiteIdOrderByCreatedAtDesc(UUID siteId);
}

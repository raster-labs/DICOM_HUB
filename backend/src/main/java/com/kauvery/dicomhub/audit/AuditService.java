package com.kauvery.dicomhub.audit;

import com.kauvery.dicomhub.dicom.LiveStudyEventStreamService;
import com.kauvery.dicomhub.domain.entity.AuditEvent;
import com.kauvery.dicomhub.domain.repository.AuditEventRepository;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final AuditEventRepository auditEventRepository;
    private final LiveStudyEventStreamService liveStreamService;

    @Autowired
    public AuditService(
            AuditEventRepository auditEventRepository,
            LiveStudyEventStreamService liveStreamService) {
        this.auditEventRepository = auditEventRepository;
        this.liveStreamService = liveStreamService;
    }

    public AuditService(AuditEventRepository auditEventRepository) {
        this(auditEventRepository, null);
    }

    @Transactional
    public AuditEvent record(
            UUID siteId,
            String actorUsername,
            String actorRole,
            String eventCategory,
            String action,
            String entityType,
            String entityId,
            String outcome,
            String detailsJson) {
        return recordWithSetting(
                siteId, actorUsername, actorRole, eventCategory,
                action, entityType, entityId, outcome, null, detailsJson);
    }

    @Transactional
    public AuditEvent recordWithSetting(
            UUID siteId,
            String actorUsername,
            String actorRole,
            String eventCategory,
            String action,
            String entityType,
            String entityId,
            String outcome,
            String changedSetting,
            String detailsJson) {
        AuditEvent event = new AuditEvent();
        event.setId(UUID.randomUUID());
        event.setSiteId(siteId);
        event.setActorUsername(actorUsername != null && !actorUsername.isBlank() ? actorUsername : "super.admin");
        event.setActorRole(actorRole != null && !actorRole.isBlank() ? actorRole : "SUPER_USER");
        event.setEventCategory(eventCategory);
        event.setAction(action);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setOutcome(outcome);
        event.setChangedSetting(changedSetting);
        event.setDetailsJson(detailsJson);
        event.setCreatedAt(OffsetDateTime.now());
        AuditEvent saved = auditEventRepository.save(event);
        if (liveStreamService != null) {
            liveStreamService.publishAuditOrAlertEvent(saved);
        }
        return saved;
    }
}

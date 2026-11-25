package com.kauvery.dicomhub.report;

import com.kauvery.dicomhub.domain.entity.AiResultRecord;
import com.kauvery.dicomhub.domain.entity.PacsDestination;
import com.kauvery.dicomhub.domain.entity.StudyRecord;

/**
 * Pluggable Report Pre-fill Integration Strategy (BRD Interface I-6 / Spec Section 4.9 / ADR-06).
 * Always produces editable draft findings; never auto-signs.
 */
public interface ReportIntegrationStrategy {
    String strategyCode();

    String dispatchEditableDraft(
            PacsDestination pacsDestination,
            StudyRecord study,
            AiResultRecord aiResult,
            String restoredPatientId,
            String restoredAccessionNumber);
}

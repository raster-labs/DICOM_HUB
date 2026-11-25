package com.kauvery.dicomhub.report;

import com.kauvery.dicomhub.domain.entity.AiResultRecord;
import com.kauvery.dicomhub.domain.entity.PacsDestination;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Implements Option A (DICOM_SR_NATIVE), Option B (HL7_V2_ORU), and Option C (FHIR_DIAGNOSTIC_REPORT)
 * editable draft report pre-fill strategies.
 */
@Component
public class ReportIntegrationDispatcher {

    private final Map<String, ReportIntegrationStrategy> strategies;

    public ReportIntegrationDispatcher() {
        List<ReportIntegrationStrategy> builtIn = List.of(
                new DicomSrNativeStrategy(),
                new Hl7V2OruStrategy(),
                new FhirDiagnosticReportStrategy(),
                new PacsVendorApiStrategy()
        );
        this.strategies = builtIn.stream()
                .collect(Collectors.toMap(ReportIntegrationStrategy::strategyCode, Function.identity()));
    }

    public String dispatchDraft(
            PacsDestination destination,
            StudyRecord study,
            AiResultRecord result,
            String realPatientId,
            String realAccession) {
        ReportIntegrationStrategy strategy = strategies.getOrDefault(
                destination.getReportIntegrationStrategy(),
                strategies.get("DICOM_SR_NATIVE"));
        return strategy.dispatchEditableDraft(destination, study, result, realPatientId, realAccession);
    }

    static class DicomSrNativeStrategy implements ReportIntegrationStrategy {
        @Override
        public String strategyCode() { return "DICOM_SR_NATIVE"; }

        @Override
        public String dispatchEditableDraft(
                PacsDestination dest, StudyRecord study, AiResultRecord res, String patId, String acc) {
            return "DRAFT_SENT_DICOM_SR_TID1500_TO_" + dest.getAeTitle();
        }
    }

    static class Hl7V2OruStrategy implements ReportIntegrationStrategy {
        @Override
        public String strategyCode() { return "HL7_V2_ORU"; }

        @Override
        public String dispatchEditableDraft(
                PacsDestination dest, StudyRecord study, AiResultRecord res, String patId, String acc) {
            return "DRAFT_SENT_HL7_ORU_R01_PRELIMINARY_STATUS_P";
        }
    }

    static class FhirDiagnosticReportStrategy implements ReportIntegrationStrategy {
        @Override
        public String strategyCode() { return "FHIR_DIAGNOSTIC_REPORT"; }

        @Override
        public String dispatchEditableDraft(
                PacsDestination dest, StudyRecord study, AiResultRecord res, String patId, String acc) {
            return "DRAFT_SENT_FHIR_R4_DIAGNOSTIC_REPORT_PRELIMINARY";
        }
    }

    static class PacsVendorApiStrategy implements ReportIntegrationStrategy {
        @Override
        public String strategyCode() { return "PACS_VENDOR_API"; }

        @Override
        public String dispatchEditableDraft(
                PacsDestination dest, StudyRecord study, AiResultRecord res, String patId, String acc) {
            return "DRAFT_SENT_PACS_VENDOR_REST_API";
        }
    }
}

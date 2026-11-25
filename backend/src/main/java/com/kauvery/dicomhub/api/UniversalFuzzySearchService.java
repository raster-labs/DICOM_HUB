package com.kauvery.dicomhub.api;

import com.kauvery.dicomhub.domain.entity.AuditEvent;
import com.kauvery.dicomhub.domain.entity.DicomSource;
import com.kauvery.dicomhub.domain.entity.HubUser;
import com.kauvery.dicomhub.domain.entity.PacsDestination;
import com.kauvery.dicomhub.domain.entity.PseudonymMapping;
import com.kauvery.dicomhub.domain.entity.RoutingRule;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.StudyRegionMapping;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.repository.AuditEventRepository;
import com.kauvery.dicomhub.domain.repository.DicomSourceRepository;
import com.kauvery.dicomhub.domain.repository.HubUserRepository;
import com.kauvery.dicomhub.domain.repository.PacsDestinationRepository;
import com.kauvery.dicomhub.domain.repository.PseudonymMappingRepository;
import com.kauvery.dicomhub.domain.repository.RoutingRuleRepository;
import com.kauvery.dicomhub.domain.repository.StudyRecordRepository;
import com.kauvery.dicomhub.domain.repository.StudyRegionMappingRepository;
import com.kauvery.dicomhub.domain.repository.VendorConfigRepository;
import com.kauvery.dicomhub.domain.repository.VendorDeliveryJobRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Universal Search Service with Exact/Substring and Fuzzy Matching modes across
 * Studies, Patient Forwarding Jobs, DICOM Sources, Destinations/Vendors,
 * Routing Rules & Brain Synonym Mappings, Users, and Audit Logs.
 */
@Service
public class UniversalFuzzySearchService {

    public record UniversalSearchHitDto(
            String category, // STUDY, DELIVERY_JOB, DICOM_SOURCE, DESTINATION_VENDOR, ROUTING_RULE, USER, AUDIT_EVENT
            String entityId,
            String title,
            String subtitle,
            String badge,
            String status,
            String matchType, // EXACT, SUBSTRING, FUZZY
            double similarityScore,
            String matchedField,
            String targetSubpage
    ) {}

    public record UniversalSearchResponseDto(
            UUID siteId,
            String query,
            boolean fuzzyEnabled,
            double threshold,
            int totalHits,
            List<UniversalSearchHitDto> hits
    ) {}

    private record FieldMatch(boolean matched, String matchType, double score, String fieldName) {}

    private final StudyRecordRepository studyRecordRepository;
    private final VendorDeliveryJobRepository jobRepository;
    private final DicomSourceRepository dicomSourceRepository;
    private final PacsDestinationRepository pacsDestinationRepository;
    private final VendorConfigRepository vendorConfigRepository;
    private final RoutingRuleRepository routingRuleRepository;
    private final StudyRegionMappingRepository studyRegionMappingRepository;
    private final PseudonymMappingRepository pseudonymMappingRepository;
    private final HubUserRepository hubUserRepository;
    private final AuditEventRepository auditEventRepository;

    public UniversalFuzzySearchService(
            StudyRecordRepository studyRecordRepository,
            VendorDeliveryJobRepository jobRepository,
            DicomSourceRepository dicomSourceRepository,
            PacsDestinationRepository pacsDestinationRepository,
            VendorConfigRepository vendorConfigRepository,
            RoutingRuleRepository routingRuleRepository,
            StudyRegionMappingRepository studyRegionMappingRepository,
            PseudonymMappingRepository pseudonymMappingRepository,
            HubUserRepository hubUserRepository,
            AuditEventRepository auditEventRepository) {
        this.studyRecordRepository = studyRecordRepository;
        this.jobRepository = jobRepository;
        this.dicomSourceRepository = dicomSourceRepository;
        this.pacsDestinationRepository = pacsDestinationRepository;
        this.vendorConfigRepository = vendorConfigRepository;
        this.routingRuleRepository = routingRuleRepository;
        this.studyRegionMappingRepository = studyRegionMappingRepository;
        this.pseudonymMappingRepository = pseudonymMappingRepository;
        this.hubUserRepository = hubUserRepository;
        this.auditEventRepository = auditEventRepository;
    }

    public UniversalSearchResponseDto search(
            UUID siteId,
            String rawQuery,
            boolean fuzzy,
            Double customThreshold) {
        return search(siteId, rawQuery, fuzzy, customThreshold, true);
    }

    public UniversalSearchResponseDto search(
            UUID siteId,
            String rawQuery,
            boolean fuzzy,
            Double customThreshold,
            boolean includeDicomConfig) {
        String query = rawQuery != null ? rawQuery.trim() : "";
        double threshold = (customThreshold != null && customThreshold > 0.0 && customThreshold <= 1.0)
                ? customThreshold
                : 0.58;
        if (query.isEmpty()) {
            return new UniversalSearchResponseDto(siteId, "", fuzzy, threshold, 0, List.of());
        }

        List<UniversalSearchHitDto> results = new ArrayList<>();
        Map<UUID, VendorConfig> vendorMap = vendorConfigRepository.findBySiteIdOrderByVendorCodeAsc(siteId).stream()
                .collect(Collectors.toMap(VendorConfig::getId, v -> v, (a, b) -> a));

        // 1. Search Studies
        for (StudyRecord s : studyRecordRepository.findBySiteIdOrderByCreatedAtDesc(siteId)) {
            String cleanName = s.getPatientName() != null ? s.getPatientName().replace('^', ' ').trim() : "KAUVERY PATIENT";
            String recvStatus = s.getReceivingStatus() != null ? s.getReceivingStatus() : "COMPLETED";
            FieldMatch m = evaluateFields(
                    query,
                    fuzzy,
                    threshold,
                    Map.of(
                            "Patient Name", cleanName,
                            "Patient ID", s.getPatientId() != null ? s.getPatientId() : "",
                            "Accession Number", s.getAccessionNumber() != null ? s.getAccessionNumber() : "",
                            "Study Description", s.getStudyDescription() != null ? s.getStudyDescription() : "",
                            "Canonical Region", s.getCanonicalRegion() != null ? s.getCanonicalRegion() : "",
                            "Body Part", s.getBodyPartExamined() != null ? s.getBodyPartExamined() : "",
                            "Modality", s.getModality() != null ? s.getModality() : "",
                            "Source AE Title", (includeDicomConfig && s.getCallingAeTitle() != null) ? s.getCallingAeTitle() : "",
                            "Source Type", s.getSourceType() != null ? s.getSourceType() : "",
                            "Receiving Status", recvStatus));
            if (m.matched()) {
                results.add(new UniversalSearchHitDto(
                        "STUDY",
                        s.getId().toString(),
                        cleanName + " (" + (s.getPatientId() != null ? s.getPatientId() : "N/A") + ")",
                        s.getModality() + " · " + (s.getStudyDescription() != null ? s.getStudyDescription() : s.getCanonicalRegion())
                                + " · " + s.getInstanceCount() + " stored imgs · Source: " + s.getSourceType()
                                + " (" + s.getCallingAeTitle() + ")",
                        s.getSourceType(),
                        recvStatus,
                        m.matchType(),
                        m.score(),
                        m.fieldName(),
                        "studies"));
            }
        }

        // 2. Search Delivery Jobs
        Map<UUID, StudyRecord> studyMap = studyRecordRepository.findBySiteIdOrderByCreatedAtDesc(siteId).stream()
                .collect(Collectors.toMap(StudyRecord::getId, s -> s, (a, b) -> a));
        for (VendorDeliveryJob j : jobRepository.findBySiteIdOrderByCreatedAtDesc(siteId)) {
            VendorConfig v = vendorMap.get(j.getVendorId());
            StudyRecord s = studyMap.get(j.getStudyId());
            Optional<PseudonymMapping> pm = pseudonymMappingRepository
                    .findBySiteIdAndStudyIdAndVendorId(siteId, j.getStudyId(), j.getVendorId());
            String pseudoId = pm.map(PseudonymMapping::getPseudoPatientId).orElse("");
            String vName = v != null ? v.getVendorName() : "AI Vendor";
            String vCode = v != null ? v.getVendorCode() : "";
            String destAe = (includeDicomConfig && v != null && v.getDestinationAeTitle() != null)
                    ? v.getDestinationAeTitle()
                    : "";
            FieldMatch m = evaluateFields(
                    query,
                    fuzzy,
                    threshold,
                    Map.of(
                            "Vendor Name", vName,
                            "Vendor Code", vCode,
                            "Destination AE", destAe,
                            "Pseudo Patient ID", pseudoId,
                            "Job Status", j.getStatus(),
                            "Vendor Job ID", j.getVendorJobId() != null ? j.getVendorJobId() : "",
                            "Patient Name", s != null && s.getPatientName() != null ? s.getPatientName().replace('^', ' ') : ""));
            if (m.matched()) {
                String jobTitle = includeDicomConfig && !destAe.isEmpty()
                        ? vName + " → " + destAe + " (" + j.getTransferredInstances() + " imgs pushed)"
                        : vName + " (" + j.getTransferredInstances() + " imgs pushed)";
                results.add(new UniversalSearchHitDto(
                        "DELIVERY_JOB",
                        j.getId().toString(),
                        jobTitle,
                        "Pseudo ID: " + (pseudoId.isEmpty() ? "—" : pseudoId)
                                + " · Receipt: " + (j.getVendorJobId() != null ? j.getVendorJobId() : "Pending"),
                        v != null && v.getDestinationType() != null ? v.getDestinationType() : "DICOM_UPLOADER",
                        j.getStatus(),
                        m.matchType(),
                        m.score(),
                        m.fieldName(),
                        "queue-results"));
            }
        }

        if (includeDicomConfig) {
            // 3. Search DICOM Sources (Modalities & RIS-PACS)
            for (DicomSource src : dicomSourceRepository.findBySiteIdOrderBySourceNameAsc(siteId)) {
                FieldMatch m = evaluateFields(
                        query,
                        fuzzy,
                        threshold,
                        Map.of(
                                "Source Name", src.getSourceName(),
                                "Source Type", src.getSourceType(),
                                "Calling AE Title", src.getCallingAeTitle(),
                                "Called AE Title", src.getCalledAeTitle(),
                                "Host IP", src.getHostAddress()));
                if (m.matched()) {
                    results.add(new UniversalSearchHitDto(
                            "DICOM_SOURCE",
                            src.getId().toString(),
                            src.getSourceName() + " [" + src.getCallingAeTitle() + "]",
                            src.getSourceType() + " · " + src.getCallingAeTitle() + " → " + src.getCalledAeTitle()
                                    + " @ " + src.getHostAddress() + ":" + src.getDicomPort(),
                            src.getSourceType(),
                            src.isActive() ? "ACTIVE" : "PAUSED",
                            m.matchType(),
                            m.score(),
                            m.fieldName(),
                            "dicom-config"));
                }
            }

            // 4. Search Destinations & Vendor Connectors (DICOM Router, AI Uploader, PACS)
            for (VendorConfig v : vendorConfigRepository.findBySiteIdOrderByVendorCodeAsc(siteId)) {
                FieldMatch m = evaluateFields(
                        query,
                        fuzzy,
                        threshold,
                        Map.of(
                                "Vendor Name", v.getVendorName(),
                                "Vendor Code", v.getVendorCode(),
                                "Use Case", v.getUseCaseName(),
                                "Destination AE Title", v.getDestinationAeTitle() != null ? v.getDestinationAeTitle() : "",
                                "Destination IP", v.getDestinationHost() != null ? v.getDestinationHost() : "",
                                "Destination Type", v.getDestinationType() != null ? v.getDestinationType() : ""));
                if (m.matched()) {
                    results.add(new UniversalSearchHitDto(
                            "DESTINATION_VENDOR",
                            v.getId().toString(),
                            v.getVendorName() + " (" + v.getVendorCode() + ")",
                            v.getUseCaseName() + " · AE: " + v.getDestinationAeTitle()
                                    + " @ " + v.getDestinationHost() + ":" + v.getDestinationPort(),
                            v.getDestinationType() != null ? v.getDestinationType() : "DICOM_UPLOADER",
                            v.isActive() ? "ACTIVE" : "PAUSED",
                            m.matchType(),
                            m.score(),
                            m.fieldName(),
                            "dicom-config"));
                }
            }

            for (PacsDestination p : pacsDestinationRepository.findBySiteIdOrderByDestinationNameAsc(siteId)) {
                FieldMatch m = evaluateFields(
                        query,
                        fuzzy,
                        threshold,
                        Map.of(
                                "PACS Destination Name", p.getDestinationName(),
                                "PACS AE Title", p.getAeTitle(),
                                "PACS Host IP", p.getHostAddress(),
                                "Report Strategy", p.getReportIntegrationStrategy()));
                if (m.matched()) {
                    results.add(new UniversalSearchHitDto(
                            "DESTINATION_VENDOR",
                            p.getId().toString(),
                            p.getDestinationName() + " [" + p.getAeTitle() + "]",
                            "Designated Return PACS @ " + p.getHostAddress() + ":" + p.getDicomPort()
                                    + " · Strategy: " + p.getReportIntegrationStrategy(),
                            "PACS_DESTINATION",
                            p.isActive() ? "ACTIVE" : "PAUSED",
                            m.matchType(),
                            m.score(),
                            m.fieldName(),
                            "dicom-config"));
                }
            }

            // 5. Search Routing Rules & Multi-Description Region Mappings
            for (StudyRegionMapping rm : studyRegionMappingRepository.findBySiteIdOrderByCanonicalRegionAsc(siteId)) {
                FieldMatch m = evaluateFields(
                        query,
                        fuzzy,
                        threshold,
                        Map.of(
                                "Canonical Region", rm.getCanonicalRegion(),
                                "Study Descriptions", rm.getStudyDescriptions(),
                                "Modality", rm.getModality(),
                                "Body Part", rm.getBodyPart()));
                if (m.matched()) {
                    results.add(new UniversalSearchHitDto(
                            "ROUTING_RULE",
                            rm.getId().toString(),
                            "Region Dictionary: " + rm.getCanonicalRegion() + " (" + rm.getModality() + ")",
                            "Aliases: " + rm.getStudyDescriptions(),
                            "REGION_SYNONYMS",
                            rm.isActive() ? "ACTIVE" : "PAUSED",
                            m.matchType(),
                            m.score(),
                            m.fieldName(),
                            "dicom-config"));
                }
            }

            for (RoutingRule r : routingRuleRepository.findBySiteIdOrderByPriorityOrderAsc(siteId)) {
                FieldMatch m = evaluateFields(
                        query,
                        fuzzy,
                        threshold,
                        Map.of(
                                "Rule Name", r.getRuleName(),
                                "Study Description Pattern", r.getStudyDescriptionPattern(),
                                "Canonical Region", r.getCanonicalRegion() != null ? r.getCanonicalRegion() : "",
                                "Modality", r.getModality(),
                                "Body Part Pattern", r.getBodyPartPattern() != null ? r.getBodyPartPattern() : ""));
                if (m.matched()) {
                    results.add(new UniversalSearchHitDto(
                            "ROUTING_RULE",
                            r.getId().toString(),
                            "#" + r.getPriorityOrder() + " " + r.getRuleName(),
                            "Modality: " + r.getModality() + " · Pattern: " + r.getStudyDescriptionPattern(),
                            "ROUTING_RULE",
                            r.isActive() ? "ACTIVE" : "PAUSED",
                            m.matchType(),
                            m.score(),
                            m.fieldName(),
                            "dicom-config"));
                }
            }
        }

        // 6. Search Users
        for (HubUser u : hubUserRepository.findBySiteIdOrderByCreatedAtAsc(siteId)) {
            FieldMatch m = evaluateFields(
                    query,
                    fuzzy,
                    threshold,
                    Map.of(
                            "Username", u.getUsername(),
                            "Full Name", u.getFullName(),
                            "Email", u.getEmail(),
                            "User Role", u.getUserRole(),
                            "Department", u.getDepartment() != null ? u.getDepartment() : ""));
            if (m.matched()) {
                results.add(new UniversalSearchHitDto(
                        "USER",
                        u.getId().toString(),
                        u.getFullName() + " (@" + u.getUsername() + ")",
                        u.getUserRole() + " · " + (u.getDepartment() != null ? u.getDepartment() : u.getEmail()),
                        u.getUserRole(),
                        u.isActive() ? "ACTIVE" : "DISABLED",
                        m.matchType(),
                        m.score(),
                        m.fieldName(),
                        "users"));
            }
        }

        // 7. Search Recent Audit Events
        Set<String> dicomConfigEntityTypes = Set.of(
                "DicomSource", "PacsDestination", "VendorConfig", "DicomNodeSettings",
                "RoutingRule", "StudyRegionMapping", "DeidProfile", "DicomEcho");
        for (AuditEvent a : auditEventRepository.findTop100BySiteIdOrderByCreatedAtDesc(siteId).stream().limit(35).toList()) {
            if (!includeDicomConfig && ("CONFIG_CHANGE".equalsIgnoreCase(a.getEventCategory())
                    || (a.getEntityType() != null && dicomConfigEntityTypes.contains(a.getEntityType())))) {
                continue;
            }
            String searchableSetting = includeDicomConfig && a.getChangedSetting() != null ? a.getChangedSetting() : "";
            String searchableDetails = includeDicomConfig && a.getDetailsJson() != null ? a.getDetailsJson() : "";
            FieldMatch m = evaluateFields(
                    query,
                    fuzzy,
                    threshold,
                    Map.of(
                            "Actor", a.getActorUsername(),
                            "Action", a.getAction(),
                            "Changed Setting", searchableSetting,
                            "Entity Type", a.getEntityType(),
                            "Details", searchableDetails));
            if (m.matched()) {
                String auditSubtitle = includeDicomConfig
                        ? ((a.getChangedSetting() != null ? a.getChangedSetting() + " · " : "")
                                + (a.getDetailsJson() != null ? a.getDetailsJson() : ""))
                        : (a.getEventCategory() + " · " + a.getEntityType() + " · " + a.getOutcome());
                results.add(new UniversalSearchHitDto(
                        "AUDIT_EVENT",
                        a.getId().toString(),
                        a.getAction() + " by " + a.getActorUsername() + " (" + a.getActorRole() + ")",
                        auditSubtitle,
                        a.getEventCategory(),
                        a.getOutcome(),
                        m.matchType(),
                        m.score(),
                        m.fieldName(),
                        "audit"));
            }
        }

        results.sort(Comparator.comparingDouble(UniversalSearchHitDto::similarityScore).reversed());
        List<UniversalSearchHitDto> topHits = results.stream().limit(40).toList();
        return new UniversalSearchResponseDto(siteId, query, fuzzy, threshold, topHits.size(), topHits);
    }

    private FieldMatch evaluateFields(
            String query,
            boolean fuzzy,
            double threshold,
            Map<String, String> fields) {
        String qNorm = normalize(query);
        if (qNorm.isEmpty()) {
            return new FieldMatch(false, "NONE", 0.0, "");
        }

        FieldMatch best = new FieldMatch(false, "NONE", 0.0, "");

        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String fieldName = entry.getKey();
            String rawVal = entry.getValue();
            if (rawVal == null || rawVal.isBlank()) {
                continue;
            }
            String valNorm = normalize(rawVal);
            if (valNorm.isEmpty()) {
                continue;
            }

            // 1. Exact equality
            if (valNorm.equals(qNorm)) {
                return new FieldMatch(true, "EXACT", 1.0, fieldName);
            }

            // 2. Direct substring containment
            if (valNorm.contains(qNorm)) {
                double subScore = valNorm.startsWith(qNorm) ? 0.96 : 0.91;
                if (subScore > best.score()) {
                    best = new FieldMatch(true, "SUBSTRING", subScore, fieldName);
                }
                continue;
            }

            // 3. Multi-token exact substring check
            String[] qTokens = qNorm.split("\\s+");
            if (qTokens.length > 1) {
                boolean allTokensContained = true;
                for (String qt : qTokens) {
                    if (!valNorm.contains(qt)) {
                        allTokensContained = false;
                        break;
                    }
                }
                if (allTokensContained) {
                    if (0.89 > best.score()) {
                        best = new FieldMatch(true, "SUBSTRING", 0.89, fieldName);
                    }
                    continue;
                }
            }

            // 4. Fuzzy matching (Levenshtein + Bigram Dice + Subsequence) when enabled
            if (fuzzy) {
                double fuzzyScore = computeFuzzySimilarity(qNorm, valNorm);
                if (fuzzyScore >= threshold && fuzzyScore > best.score()) {
                    best = new FieldMatch(true, "FUZZY", Math.round(fuzzyScore * 100.0) / 100.0, fieldName);
                }
            }
        }
        return best;
    }

    public double computeFuzzySimilarity(String queryNorm, String candidateNorm) {
        String[] qTokens = queryNorm.split("[\\s,_/\\-:.]+");
        String[] cTokens = candidateNorm.split("[\\s,_/\\-:.]+");
        if (qTokens.length == 0 || cTokens.length == 0) {
            return 0.0;
        }

        double totalTokenScore = 0.0;
        int validTokens = 0;

        for (String qt : qTokens) {
            if (qt.isEmpty()) {
                continue;
            }
            validTokens++;
            double bestForQt = 0.0;
            for (String ct : cTokens) {
                if (ct.isEmpty()) {
                    continue;
                }
                if (ct.equals(qt)) {
                    bestForQt = 1.0;
                    break;
                }
                int minL = Math.min(qt.length(), ct.length());
                int maxL = Math.max(qt.length(), ct.length());
                if (minL >= 3 && (ct.contains(qt) || qt.contains(ct)) && ((double) minL / maxL) >= 0.45) {
                    double containSim = 0.65 + 0.30 * ((double) minL / maxL);
                    bestForQt = Math.max(bestForQt, containSim);
                }
                double levSim = levenshteinSimilarity(qt, ct);
                double diceSim = bigramDiceSimilarity(qt, ct);
                double combined = Math.max(levSim, (levSim * 0.65 + diceSim * 0.35));
                if (qt.length() >= 3 && ct.length() >= 3 && qt.substring(0, 3).equals(ct.substring(0, 3))) {
                    combined = Math.min(0.95, combined + 0.08);
                } else if (isSubsequence(qt, ct) && qt.length() >= 3) {
                    combined = Math.max(combined, 0.68);
                }
                bestForQt = Math.max(bestForQt, combined);
            }
            totalTokenScore += bestForQt;
        }

        if (validTokens == 0) {
            return 0.0;
        }
        double avgTokenSim = totalTokenScore / validTokens;
        double wholeSim = Math.max(
                levenshteinSimilarity(queryNorm, candidateNorm),
                bigramDiceSimilarity(queryNorm, candidateNorm));
        return Math.max(avgTokenSim, wholeSim);
    }

    private String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replace('^', ' ').replaceAll("\\s+", " ").trim();
    }

    private double levenshteinSimilarity(String a, String b) {
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) {
            return 1.0;
        }
        int dist = levenshteinDistance(a, b);
        return Math.max(0.0, 1.0 - ((double) dist / maxLen));
    }

    private int levenshteinDistance(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n];
    }

    private double bigramDiceSimilarity(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        Set<String> setA = new HashSet<>();
        for (int i = 0; i < a.length() - 1; i++) {
            setA.add(a.substring(i, i + 2));
        }
        Set<String> setB = new HashSet<>();
        for (int i = 0; i < b.length() - 1; i++) {
            setB.add(b.substring(i, i + 2));
        }
        int intersection = 0;
        for (String bg : setA) {
            if (setB.contains(bg)) {
                intersection++;
            }
        }
        return (2.0 * intersection) / (setA.size() + setB.size());
    }

    private boolean isSubsequence(String sub, String target) {
        int i = 0;
        int j = 0;
        while (i < sub.length() && j < target.length()) {
            if (sub.charAt(i) == target.charAt(j)) {
                i++;
            }
            j++;
        }
        return i == sub.length();
    }
}

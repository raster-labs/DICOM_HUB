package com.kauvery.dicomhub.routing;

import com.kauvery.dicomhub.audit.AuditService;
import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import com.kauvery.dicomhub.domain.entity.RoutingRule;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.StudyRegionMapping;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.repository.RoutingRuleRepository;
import com.kauvery.dicomhub.domain.repository.StudyRegionMappingRepository;
import com.kauvery.dicomhub.domain.repository.VendorConfigRepository;
import com.kauvery.dicomhub.domain.repository.VendorDeliveryJobRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Evaluates priority-ordered routing rules per site (FR-03, FR-06, FR-08, FR-32)
 * without requiring code changes, including multi-study-description region alias mappings
 * (e.g., HEAD Protocol, BRAI Injury, Strock Protocol -> CT Brain / MRI Brain),
 * series selection filtering, and dry-run rule testing.
 */
@Service
public class RoutingEngineService {

    private final RoutingRuleRepository routingRuleRepository;
    private final StudyRegionMappingRepository studyRegionMappingRepository;
    private final VendorConfigRepository vendorConfigRepository;
    private final VendorDeliveryJobRepository jobRepository;
    private final AuditService auditService;

    public RoutingEngineService(
            RoutingRuleRepository routingRuleRepository,
            StudyRegionMappingRepository studyRegionMappingRepository,
            VendorConfigRepository vendorConfigRepository,
            VendorDeliveryJobRepository jobRepository,
            AuditService auditService) {
        this.routingRuleRepository = routingRuleRepository;
        this.studyRegionMappingRepository = studyRegionMappingRepository;
        this.vendorConfigRepository = vendorConfigRepository;
        this.jobRepository = jobRepository;
        this.auditService = auditService;
    }

    public record RuleMatchEvaluationDto(
            UUID ruleId,
            String ruleName,
            int priorityOrder,
            String canonicalRegion,
            UUID vendorId,
            String vendorCode,
            String vendorName,
            String useCaseName,
            String destinationType,
            String destinationAeTitle,
            String destinationHost,
            Integer destinationPort,
            boolean shadowMode,
            boolean allowMultiVendor
    ) {}

    public record RuleTestSandboxResultDto(
            UUID siteId,
            String resolvedCanonicalRegion,
            int matchedRulesCount,
            boolean multiVendorConflictOrFanout,
            List<RuleMatchEvaluationDto> matchedRules
    ) {}

    public List<String> buildStudyMetadataCandidates(
            String studyDescription,
            String bodyPartExamined,
            String protocolName) {
        List<String> candidates = new ArrayList<>();
        String sd = studyDescription == null ? "" : studyDescription.trim();
        String bp = bodyPartExamined == null ? "" : bodyPartExamined.trim();
        String pn = protocolName == null ? "" : protocolName.trim();
        if (!sd.isEmpty()) {
            candidates.add(sd);
        }
        if (!bp.isEmpty() && !bp.equalsIgnoreCase(sd)) {
            candidates.add(bp);
        }
        if (!pn.isEmpty() && !pn.equalsIgnoreCase(sd) && !pn.equalsIgnoreCase(bp)) {
            candidates.add(pn);
        }
        if (candidates.size() > 1) {
            candidates.add(String.join(" ", candidates));
        }
        return candidates;
    }

    public Optional<StudyRegionMapping> resolveRegionMapping(UUID siteId, String modality, String studyDescription) {
        return resolveRegionMapping(siteId, modality, studyDescription, null, null);
    }

    public Optional<StudyRegionMapping> resolveRegionMapping(
            UUID siteId,
            String modality,
            String studyDescription,
            String bodyPartExamined,
            String protocolName) {
        if (siteId == null) {
            return Optional.empty();
        }
        List<String> candidates = buildStudyMetadataCandidates(studyDescription, bodyPartExamined, protocolName);
        String normModality = normalizeModality(modality);
        if (candidates.isEmpty()) {
            candidates = List.of(normModality);
        }
        List<StudyRegionMapping> mappings = studyRegionMappingRepository
                .findBySiteIdAndActiveTrueOrderByCanonicalRegionAsc(siteId);
        // First pass (a): prefer region mappings that match via explicit (non-wildcard '*') study descriptions
        for (StudyRegionMapping m : mappings) {
            if (!modalityMatches(m.getModality(), normModality)) {
                continue;
            }
            if (candidates.stream().anyMatch(c -> matchesMultiPatternSpecificOnly(c, m.getStudyDescriptions(), "CONTAINS"))) {
                return Optional.of(m);
            }
        }
        // First pass (b): check region mappings that match via explicit (non-wildcard '*') body part or canonical region
        for (StudyRegionMapping m : mappings) {
            if (!modalityMatches(m.getModality(), normModality)) {
                continue;
            }
            if (candidates.stream().anyMatch(c -> matchesMultiPatternSpecificOnly(c, m.getBodyPart(), "CONTAINS")
                    || matchesMultiPatternSpecificOnly(c, m.getCanonicalRegion(), "CONTAINS"))) {
                return Optional.of(m);
            }
        }
        // First pass (c): check region mappings that match via wildcard '*'
        for (StudyRegionMapping m : mappings) {
            if (!modalityMatches(m.getModality(), normModality)) {
                continue;
            }
            if (candidates.stream().anyMatch(c -> matchesMultiPattern(c, m.getStudyDescriptions(), "CONTAINS"))) {
                return Optional.of(m);
            }
        }
        // Second pass: if study metadata matches a configured Brain protocol alias
        // (e.g., HEAD Protocol, BRAI Injury, Strock Protocol, CT Brain) under either CT or MR,
        // resolve it as a Brain region for the study's modality (CT Brain or MRI Brain).
        for (StudyRegionMapping m : mappings) {
            if (isBrainRegion(m.getCanonicalRegion())
                    && candidates.stream().anyMatch(c -> matchesMultiPattern(c, m.getStudyDescriptions(), "CONTAINS"))) {
                Optional<StudyRegionMapping> targetModalityBrain = mappings.stream()
                        .filter(candidate -> isBrainRegion(candidate.getCanonicalRegion())
                                && modalityMatches(candidate.getModality(), normModality))
                        .findFirst();
                return Optional.of(targetModalityBrain.orElse(m));
            }
        }
        return Optional.empty();
    }

    public String adaptRegionToModality(String canonicalRegion, String modality) {
        if (canonicalRegion == null || canonicalRegion.isBlank()) {
            return "";
        }
        String normModality = normalizeModality(modality);
        if (isBrainRegion(canonicalRegion)) {
            return "MR".equals(normModality) ? "MRI Brain" : "CT Brain";
        }
        return canonicalRegion.trim();
    }

    private boolean isBrainRegion(String canonicalRegion) {
        if (canonicalRegion == null) {
            return false;
        }
        String upper = canonicalRegion.trim().toUpperCase(Locale.ROOT);
        return upper.equals("CT BRAIN")
                || upper.equals("MRI BRAIN")
                || upper.equals("MR BRAIN")
                || upper.equals("BRAIN")
                || upper.equals("CT/MRI BRAIN")
                || upper.equals("CT OR MRI BRAIN");
    }

    public String resolveCanonicalRegion(UUID siteId, String modality, String studyDescription) {
        return resolveCanonicalRegion(siteId, modality, studyDescription, null, null);
    }

    public String resolveCanonicalRegion(
            UUID siteId,
            String modality,
            String studyDescription,
            String bodyPartExamined,
            String protocolName) {
        String normModality = normalizeModality(modality);
        Optional<StudyRegionMapping> mapped = resolveRegionMapping(
                siteId, normModality, studyDescription, bodyPartExamined, protocolName);
        if (mapped.isPresent()) {
            return adaptRegionToModality(mapped.get().getCanonicalRegion(), normModality);
        }
        boolean hasConfiguredBrainMappings = siteId != null && studyRegionMappingRepository
                .findBySiteIdAndActiveTrueOrderByCanonicalRegionAsc(siteId).stream()
                .anyMatch(m -> isBrainRegion(m.getCanonicalRegion()));
        String combinedText = String.join(" ", buildStudyMetadataCandidates(studyDescription, bodyPartExamined, protocolName));
        String descUpper = combinedText.toUpperCase(Locale.ROOT);
        if (descUpper.contains("BRAIN")
                || (!hasConfiguredBrainMappings && (descUpper.contains("HEAD PROTOCOL")
                        || descUpper.contains("BRAI INJURY")
                        || descUpper.contains("BRAIN INJURY")
                        || descUpper.contains("STROCK PROTOCOL")
                        || descUpper.contains("STROKE PROTOCOL")
                        || descUpper.contains("NCCT HEAD")))) {
            return "MR".equals(normModality) ? "MRI Brain" : "CT Brain";
        }
        if (descUpper.contains("KUB") || descUpper.contains("RENAL") || descUpper.contains("CALCULUS")) {
            return "CT KUB";
        }
        return normModality + " Study";
    }

    @Transactional
    public List<VendorDeliveryJob> evaluateAndCreateJobs(
            StudyRecord study,
            List<InstanceRecord> instances,
            boolean forceDeliberateResend) {

        List<RoutingRule> rules = routingRuleRepository.findBySiteIdAndActiveTrueOrderByPriorityOrderAsc(study.getSiteId())
                .stream()
                .sorted(Comparator.<RoutingRule, Boolean>comparing(r -> !isSpecificStudyMatch(study, r))
                        .thenComparingInt(RoutingRule::getPriorityOrder)
                        .thenComparing(RoutingRule::isAllowMultiVendor))
                .toList();
        boolean anySpecificMatch = rules.stream()
                .anyMatch(r -> matchesRule(study, instances, r) && isSpecificStudyMatch(study, r));
        List<VendorDeliveryJob> createdJobs = new ArrayList<>();

        for (RoutingRule rule : rules) {
            if (!matchesRule(study, instances, rule)) {
                continue;
            }
            if (anySpecificMatch && !isSpecificStudyMatch(study, rule)) {
                continue;
            }

            Optional<VendorConfig> vendorOpt = vendorConfigRepository.findById(rule.getVendorId());
            if (vendorOpt.isEmpty() || !vendorOpt.get().isActive()) {
                continue;
            }
            VendorConfig vendor = vendorOpt.get();

            // FR-06: Prevent duplicate delivery unless deliberate resend requested
            // (If an existing job has no routingRuleId because it was created by an anonymization preview, allow routing it)
            Optional<VendorDeliveryJob> existingJob = jobRepository
                    .findBySiteIdAndStudyIdAndVendorId(study.getSiteId(), study.getId(), vendor.getId());
            if (existingJob.isPresent()
                    && !forceDeliberateResend
                    && existingJob.get().getRoutingRuleId() != null) {
                continue;
            }

            VendorDeliveryJob job = existingJob.orElseGet(VendorDeliveryJob::new);
            if (job.getId() == null) {
                job.setId(UUID.randomUUID());
                job.setCreatedAt(OffsetDateTime.now());
            } else if (forceDeliberateResend) {
                job.setAttemptCount(0);
                job.setNextRetryAt(null);
                job.setLastError(null);
                job.setVendorJobId(null);
                job.setUploadedBytes(0L);
                job.setSelectedInstances(0);
                job.setTransferredInstances(0);
                job.setVendorAcceptedAt(null);
                job.setResultReceivedAt(null);
                job.setPacsReturnedAt(null);
                job.setWorkingCopyCleanedAt(null);
            }
            job.setSiteId(study.getSiteId());
            job.setStudyId(study.getId());
            job.setVendorId(vendor.getId());
            job.setRoutingRuleId(rule.getId());
            job.setDeidProfileId(vendor.getDeidProfileId());
            job.setStatus("DEIDENTIFICATION_PENDING");
            job.setMaxAttempts(vendor.getMaxRetryAttempts());
            job.setUpdatedAt(OffsetDateTime.now());
            job = jobRepository.save(job);
            createdJobs.add(job);

            auditService.record(
                    study.getSiteId(),
                    "ROUTING_ENGINE",
                    "SYSTEM",
                    "STUDY_LIFECYCLE",
                    "ROUTING_RULE_MATCHED",
                    "VendorDeliveryJob",
                    job.getId().toString(),
                    "SUCCESS",
                    "{\"ruleName\":\"" + rule.getRuleName()
                            + "\",\"ruleVersion\":" + rule.getRuleVersion()
                            + ",\"vendorCode\":\"" + vendor.getVendorCode()
                            + "\",\"canonicalRegion\":\"" + (study.getCanonicalRegion() != null ? study.getCanonicalRegion() : "")
                            + "\",\"deliberateResend\":" + forceDeliberateResend + "}");

            if (!rule.isAllowMultiVendor()) {
                break;
            }
        }
        return createdJobs;
    }

    /**
     * FR-08: Selects only the series/instances required by the matched routing rule
     * (strictly enforcing both requiredSeriesPattern and maxSliceThicknessMm together,
     * and excluding non-diagnostic scout/localizer series when requiredSeriesPattern is unset).
     */
    public List<InstanceRecord> selectEligibleInstancesForRule(
            List<InstanceRecord> instances,
            RoutingRule rule) {
        if (instances == null || instances.isEmpty() || rule == null) {
            return instances == null ? List.of() : instances;
        }

        boolean studyHasSeriesDescriptions = instances.stream()
                .anyMatch(i -> i.getSeriesDescription() != null && !i.getSeriesDescription().isBlank());
        boolean hasRequiredSeriesFilter = rule.getRequiredSeriesPattern() != null
                && !rule.getRequiredSeriesPattern().isBlank()
                && studyHasSeriesDescriptions;

        return instances.stream()
                .filter(i -> rule.getMaxSliceThicknessMm() == null
                        || i.getSliceThicknessMm() == null
                        || i.getSliceThicknessMm() <= rule.getMaxSliceThicknessMm())
                .filter(i -> {
                    if (hasRequiredSeriesFilter) {
                        return i.getSeriesDescription() != null
                                && !i.getSeriesDescription().isBlank()
                                && matchesMultiPattern(i.getSeriesDescription(), rule.getRequiredSeriesPattern(), "CONTAINS");
                    }
                    return !isNonDiagnosticScoutOrLocalizerSeries(i.getSeriesDescription());
                })
                .toList();
    }

    private boolean isNonDiagnosticScoutOrLocalizerSeries(String seriesDescription) {
        if (seriesDescription == null || seriesDescription.isBlank()) {
            return false;
        }
        String upper = seriesDescription.toUpperCase(Locale.ROOT);
        return upper.contains("SCOUT")
                || upper.contains("LOCALIZER")
                || upper.contains("TOPOGRAM")
                || upper.contains("SURVIEW")
                || upper.contains("DOSE REPORT");
    }

    /**
     * Specification Section 4.4: Dry-run test sample study metadata against active site rules
     * before activating or modifying routing rules.
     */
    public RuleTestSandboxResultDto testSampleStudyMetadata(
            UUID siteId,
            String modality,
            String studyDescription,
            String bodyPartExamined,
            String seriesDescription,
            Double sliceThicknessMm) {

        String effectiveModality = normalizeModality(modality == null ? "CT" : modality);
        String effectiveDesc = studyDescription == null ? "" : studyDescription;
        String effectiveBodyPart = bodyPartExamined == null ? "" : bodyPartExamined;
        String resolvedRegion = resolveCanonicalRegion(
                siteId, effectiveModality, effectiveDesc, effectiveBodyPart, null);

        StudyRecord sampleStudy = new StudyRecord();
        sampleStudy.setSiteId(siteId);
        sampleStudy.setModality(effectiveModality);
        sampleStudy.setStudyDescription(effectiveDesc);
        sampleStudy.setCanonicalRegion(resolvedRegion);
        sampleStudy.setBodyPartExamined(effectiveBodyPart);

        InstanceRecord sampleInstance = new InstanceRecord();
        sampleInstance.setSeriesDescription(seriesDescription == null ? "" : seriesDescription);
        sampleInstance.setSliceThicknessMm(sliceThicknessMm);
        List<InstanceRecord> sampleInstances = List.of(sampleInstance);

        List<RoutingRule> rules = routingRuleRepository.findBySiteIdAndActiveTrueOrderByPriorityOrderAsc(siteId)
                .stream()
                .sorted(Comparator.<RoutingRule, Boolean>comparing(r -> !isSpecificStudyMatch(sampleStudy, r))
                        .thenComparingInt(RoutingRule::getPriorityOrder)
                        .thenComparing(RoutingRule::isAllowMultiVendor))
                .toList();
        boolean anySpecificMatch = rules.stream()
                .anyMatch(r -> matchesRule(sampleStudy, sampleInstances, r) && isSpecificStudyMatch(sampleStudy, r));
        List<RuleMatchEvaluationDto> matches = new ArrayList<>();

        for (RoutingRule rule : rules) {
            if (!matchesRule(sampleStudy, sampleInstances, rule)) {
                continue;
            }
            if (anySpecificMatch && !isSpecificStudyMatch(sampleStudy, rule)) {
                continue;
            }
            Optional<VendorConfig> vendorOpt = vendorConfigRepository.findById(rule.getVendorId());
            if (vendorOpt.isEmpty() || !vendorOpt.get().isActive()) {
                continue;
            }
            VendorConfig v = vendorOpt.get();
            matches.add(new RuleMatchEvaluationDto(
                    rule.getId(),
                    rule.getRuleName(),
                    rule.getPriorityOrder(),
                    rule.getCanonicalRegion() != null ? rule.getCanonicalRegion() : resolvedRegion,
                    v.getId(),
                    v.getVendorCode(),
                    v.getVendorName(),
                    v.getUseCaseName(),
                    v.getDestinationType(),
                    v.getDestinationAeTitle(),
                    v.getDestinationHost(),
                    v.getDestinationPort(),
                    v.isShadowMode(),
                    rule.isAllowMultiVendor()));
            if (!rule.isAllowMultiVendor()) {
                break;
            }
        }

        return new RuleTestSandboxResultDto(
                siteId,
                resolvedRegion,
                matches.size(),
                matches.size() > 1,
                matches);
    }

    public boolean isSpecificStudyMatch(StudyRecord study, RoutingRule rule) {
        if (study == null || rule == null || !modalityMatches(rule.getModality(), study.getModality())) {
            return false;
        }
        String studyDesc = study.getStudyDescription() == null ? "" : study.getStudyDescription();
        String bodyPart = study.getBodyPartExamined() == null ? "" : study.getBodyPartExamined();
        String protocolName = study.getProtocolName() == null ? "" : study.getProtocolName();
        List<String> metadataCandidates = buildStudyMetadataCandidates(studyDesc, bodyPart, protocolName);
        if (metadataCandidates.isEmpty()) {
            return false;
        }
        if (metadataCandidates.stream().anyMatch(c -> matchesMultiPatternSpecificOnly(c, rule.getStudyDescriptionPattern(), rule.getMatchOperator()))) {
            return true;
        }
        if (rule.getBodyPartPattern() != null
                && !rule.getBodyPartPattern().isBlank()
                && metadataCandidates.stream().anyMatch(c -> matchesMultiPatternSpecificOnly(c, rule.getBodyPartPattern(), "CONTAINS"))) {
            return true;
        }
        if (rule.getVendorId() != null) {
            Optional<VendorConfig> vendorOpt = vendorConfigRepository.findById(rule.getVendorId());
            if (vendorOpt.isPresent()
                    && vendorOpt.get().getStudyDescriptions() != null
                    && !vendorOpt.get().getStudyDescriptions().isBlank()
                    && metadataCandidates.stream().anyMatch(c -> matchesMultiPatternSpecificOnly(c, vendorOpt.get().getStudyDescriptions(), "CONTAINS"))) {
                return true;
            }
        }
        return false;
    }

    public boolean matchesRule(StudyRecord study, List<InstanceRecord> instances, RoutingRule rule) {
        if (!modalityMatches(rule.getModality(), study.getModality())) {
            return false;
        }
        String studyDesc = study.getStudyDescription() == null ? "" : study.getStudyDescription();
        String bodyPart = study.getBodyPartExamined() == null ? "" : study.getBodyPartExamined();
        String protocolName = study.getProtocolName() == null ? "" : study.getProtocolName();
        List<String> metadataCandidates = buildStudyMetadataCandidates(studyDesc, bodyPart, protocolName);
        if (metadataCandidates.isEmpty()) {
            metadataCandidates = List.of(normalizeModality(study.getModality()));
        }

        Optional<StudyRegionMapping> regionMapping = resolveRegionMapping(
                study.getSiteId(), study.getModality(), studyDesc, bodyPart, protocolName);

        boolean descMatchesDirectly = metadataCandidates.stream()
                .anyMatch(c -> matchesMultiPattern(c, rule.getStudyDescriptionPattern(), rule.getMatchOperator()));
        if (!descMatchesDirectly
                && rule.getBodyPartPattern() != null
                && !rule.getBodyPartPattern().isBlank()
                && metadataCandidates.stream().anyMatch(c -> matchesMultiPattern(c, rule.getBodyPartPattern(), "CONTAINS"))) {
            descMatchesDirectly = true;
        }
        if (!descMatchesDirectly && rule.getVendorId() != null) {
            Optional<VendorConfig> vendorOpt = vendorConfigRepository.findById(rule.getVendorId());
            if (vendorOpt.isPresent()
                    && vendorOpt.get().isActive()
                    && modalityMatches(vendorOpt.get().getModality() != null ? vendorOpt.get().getModality() : rule.getModality(), study.getModality())
                    && vendorOpt.get().getStudyDescriptions() != null
                    && !vendorOpt.get().getStudyDescriptions().isBlank()) {
                descMatchesDirectly = metadataCandidates.stream()
                        .anyMatch(c -> matchesMultiPattern(c, vendorOpt.get().getStudyDescriptions(), "CONTAINS"));
            }
        }

        boolean descMatchesViaRegionMapping = false;
        if (regionMapping.isPresent()) {
            StudyRegionMapping rm = regionMapping.get();
            String effectiveMappedRegion = adaptRegionToModality(rm.getCanonicalRegion(), study.getModality());
            if (rule.getCanonicalRegion() != null && !rule.getCanonicalRegion().isBlank()) {
                String effectiveRuleRegion = adaptRegionToModality(rule.getCanonicalRegion(), rule.getModality());
                if (effectiveRuleRegion.equalsIgnoreCase(effectiveMappedRegion)
                        || rule.getCanonicalRegion().equalsIgnoreCase(rm.getCanonicalRegion())) {
                    descMatchesViaRegionMapping = true;
                }
            } else if (matchesMultiPattern(effectiveMappedRegion, rule.getStudyDescriptionPattern(), rule.getMatchOperator())
                    || matchesMultiPattern(rm.getCanonicalRegion(), rule.getStudyDescriptionPattern(), rule.getMatchOperator())) {
                descMatchesViaRegionMapping = true;
            } else if (rm.getTargetVendorId() != null && rm.getTargetVendorId().equals(rule.getVendorId())) {
                descMatchesViaRegionMapping = true;
            }
        }

        if (!descMatchesDirectly && !descMatchesViaRegionMapping) {
            return false;
        }

        if (instances != null && !instances.isEmpty()) {
            if (selectEligibleInstancesForRule(instances, rule).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private String normalizeModality(String modality) {
        if (modality == null || modality.isBlank()) {
            return "CT";
        }
        String upper = modality.trim().toUpperCase(Locale.ROOT);
        if ("MRI".equals(upper)) {
            return "MR";
        }
        return upper;
    }

    private boolean modalityMatches(String ruleModality, String studyModality) {
        if (ruleModality == null || studyModality == null) {
            return false;
        }
        String normStudy = normalizeModality(studyModality);
        for (String token : ruleModality.split("[,|]")) {
            String trimmed = token.trim();
            if ("*".equals(trimmed) || "ALL".equalsIgnoreCase(trimmed) || "ANY".equalsIgnoreCase(trimmed)) {
                return true;
            }
            String normRule = normalizeModality(trimmed);
            if (normRule.equalsIgnoreCase(normStudy)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesMultiPatternSpecificOnly(String candidate, String patternList, String operator) {
        if (patternList == null || patternList.isBlank()) {
            return false;
        }
        String effectiveCandidate = candidate == null ? "" : candidate;
        String op = operator == null ? "CONTAINS" : operator.toUpperCase(Locale.ROOT);
        if ("REGEX".equals(op)) {
            return Pattern.compile(patternList, Pattern.CASE_INSENSITIVE).matcher(effectiveCandidate).find();
        }
        String[] tokens = patternList.split("[,|]");
        for (String rawToken : tokens) {
            String token = rawToken.trim();
            String tokenUpper = token.toUpperCase(Locale.ROOT);
            if (token.isEmpty() || "*".equals(tokenUpper) || "ALL".equals(tokenUpper) || "ANY".equals(tokenUpper)) {
                continue;
            }
            if (matchesSinglePattern(effectiveCandidate, token, op)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesMultiPattern(String candidate, String patternList, String operator) {
        if (patternList == null || patternList.isBlank()) {
            return false;
        }
        String effectiveCandidate = candidate == null ? "" : candidate;
        String op = operator == null ? "CONTAINS" : operator.toUpperCase(Locale.ROOT);
        if ("REGEX".equals(op)) {
            return Pattern.compile(patternList, Pattern.CASE_INSENSITIVE).matcher(effectiveCandidate).find();
        }
        String[] tokens = patternList.split("[,|]");
        for (String rawToken : tokens) {
            String token = rawToken.trim();
            if (!token.isEmpty() && matchesSinglePattern(effectiveCandidate, token, op)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSinglePattern(String candidate, String pattern, String operator) {
        String candUpper = candidate.toUpperCase(Locale.ROOT);
        String patUpper = pattern.toUpperCase(Locale.ROOT);
        if ("*".equals(patUpper) || "ALL".equals(patUpper) || "ANY".equals(patUpper)) {
            return true;
        }
        if (patUpper.contains("*") && !"REGEX".equals(operator)) {
            String globRegex = buildGlobRegex(patUpper);
            if (Pattern.compile(globRegex, Pattern.CASE_INSENSITIVE).matcher(candUpper).find()
                    || Pattern.compile(globRegex, Pattern.CASE_INSENSITIVE).matcher(normalizeSeparators(candUpper)).find()) {
                return true;
            }
        }
        boolean directMatch = switch (operator) {
            case "EQUALS" -> candUpper.equals(patUpper);
            case "STARTS_WITH" -> candUpper.startsWith(patUpper);
            case "ENDS_WITH" -> candUpper.endsWith(patUpper);
            case "REGEX" -> Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(candidate).find();
            default -> candUpper.contains(patUpper);
        };
        if (directMatch) {
            return true;
        }
        String normCand = normalizeSeparators(candUpper);
        String normPat = normalizeSeparators(patUpper);
        if (!normPat.isEmpty()) {
            return switch (operator) {
                case "EQUALS" -> normCand.equals(normPat);
                case "STARTS_WITH" -> normCand.startsWith(normPat);
                case "ENDS_WITH" -> normCand.endsWith(normPat);
                case "REGEX" -> false;
                default -> normCand.contains(normPat);
            };
        }
        return false;
    }

    private String normalizeSeparators(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("[\\^_\\-/]+", " ").replaceAll("\\s+", " ").trim();
    }

    private String buildGlobRegex(String globPattern) {
        String[] parts = globPattern.split("\\*", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(".*");
            }
            String part = parts[i].trim();
            if (!part.isEmpty()) {
                sb.append(Pattern.quote(part));
            }
        }
        return sb.toString();
    }
}

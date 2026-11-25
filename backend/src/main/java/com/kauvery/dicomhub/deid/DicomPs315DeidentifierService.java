package com.kauvery.dicomhub.deid;

import com.kauvery.dicomhub.audit.AuditService;
import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.deid.spi.BurnedInTextInspector;
import com.kauvery.dicomhub.deid.spi.BurnedInTextInspector.InspectionResult;
import com.kauvery.dicomhub.domain.entity.DeidProfile;
import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import com.kauvery.dicomhub.domain.entity.PseudonymMapping;
import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.repository.PseudonymMappingRepository;
import com.kauvery.dicomhub.security.PseudonymCryptoService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Fragments;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.util.TagUtils;
import org.dcm4che3.util.UIDUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements DICOM PS3.15 Basic Application Level Confidentiality Profile,
 * burned-in pixel annotation detection, pixel banner masking or study blocking via BurnedInTextInspector pipeline (FR-14),
 * configurable Study/Series date shifting/keeping/removal, private tag allow-listing,
 * and AES-256 encrypted pseudonym mapping (FR-11 to FR-15).
 * Never alters files in the Original DICOM Repository.
 */
@Service
public class DicomPs315DeidentifierService {

    private static final int[] DATE_TAGS_TO_SHIFT = {
        Tag.StudyDate,
        Tag.SeriesDate,
        Tag.AcquisitionDate,
        Tag.ContentDate,
        Tag.OverlayDate,
        Tag.CurveDate,
        Tag.InstanceCreationDate,
        Tag.ScheduledProcedureStepStartDate,
        Tag.ScheduledProcedureStepEndDate,
        Tag.PerformedProcedureStepStartDate,
        Tag.PerformedProcedureStepEndDate,
        Tag.IssueDateOfImagingServiceRequest
    };

    private static final int[] TIME_TAGS_CONFIGURABLE = {
        Tag.StudyTime,
        Tag.SeriesTime,
        Tag.AcquisitionTime,
        Tag.ContentTime,
        Tag.OverlayTime,
        Tag.CurveTime,
        Tag.InstanceCreationTime,
        Tag.ScheduledProcedureStepStartTime,
        Tag.ScheduledProcedureStepEndTime,
        Tag.PerformedProcedureStepStartTime,
        Tag.PerformedProcedureStepEndTime,
        Tag.IssueTimeOfImagingServiceRequest
    };

    private static final int[] DATETIME_TAGS_CONFIGURABLE = {
        Tag.AcquisitionDateTime,
        Tag.FrameAcquisitionDateTime,
        Tag.FrameReferenceDateTime
    };

    private static final int[] UID_TAGS_TO_REMAP = {
        Tag.FrameOfReferenceUID,
        Tag.SynchronizationFrameOfReferenceUID,
        Tag.ConcatenationUID,
        Tag.DimensionOrganizationUID,
        Tag.IrradiationEventUID,
        Tag.ReferencedSOPInstanceUID,
        Tag.ReferencedFrameOfReferenceUID
    };

    private static final int[] TAGS_TO_REMOVE = {
        // Rule 1: Patient Name, Birth Date, Address, Phone Numbers -> Remove
        Tag.PatientName,
        Tag.PatientBirthDate,
        Tag.PatientBirthTime,
        Tag.PatientAddress,
        Tag.PatientTelephoneNumbers,
        Tag.PatientMotherBirthName,
        Tag.MilitaryRank,
        Tag.BranchOfService,
        Tag.MedicalRecordLocator,
        Tag.ReferencedPatientSequence,
        Tag.OtherPatientNames,
        Tag.OtherPatientIDsSequence,
        Tag.IssuerOfPatientID,
        Tag.PatientComments,
        Tag.RegionOfResidence,
        Tag.CountryOfResidence,
        Tag.EthnicGroup,
        Tag.Occupation,
        Tag.PatientInsurancePlanCodeSequence,
        Tag.PatientReligiousPreference,
        Tag.OriginalAttributesSequence,
        Tag.ModifiedAttributesSequence,
        Tag.PersonName,
        Tag.UID,
        // Rule 5: Institution, Referring and Performing Physician Names -> Remove
        Tag.InstitutionName,
        Tag.InstitutionAddress,
        Tag.InstitutionalDepartmentName,
        Tag.ReferringPhysicianName,
        Tag.ReferringPhysicianAddress,
        Tag.ReferringPhysicianTelephoneNumbers,
        Tag.ReferringPhysicianIdentificationSequence,
        Tag.PhysiciansOfRecord,
        Tag.PhysiciansOfRecordIdentificationSequence,
        Tag.PerformingPhysicianName,
        Tag.PerformingPhysicianIdentificationSequence,
        Tag.NameOfPhysiciansReadingStudy,
        Tag.PhysiciansReadingStudyIdentificationSequence,
        Tag.OperatorsName,
        Tag.OperatorIdentificationSequence,
        Tag.RequestingPhysician,
        Tag.RequestingService,
        Tag.ScheduledPerformingPhysicianName,
        Tag.HumanPerformerName,
        Tag.ActualHumanPerformersSequence,
        Tag.VerifyingObserverSequence,
        Tag.AuthorObserverSequence,
        Tag.ParticipantSequence,
        Tag.CustodialOrganizationSequence,
        Tag.StationName,
        Tag.DeviceSerialNumber,
        // RIS / Procedure Step order identifiers
        Tag.RequestedProcedureID,
        Tag.ScheduledProcedureStepID,
        Tag.PerformedProcedureStepID,
        Tag.PlacerOrderNumberImagingServiceRequest,
        Tag.FillerOrderNumberImagingServiceRequest
    };

    private final HubProperties hubProperties;
    private final PseudonymCryptoService cryptoService;
    private final PseudonymMappingRepository pseudonymMappingRepository;
    private final List<BurnedInTextInspector> burnedInTextInspectors;
    private final AuditService auditService;

    public DicomPs315DeidentifierService(
            HubProperties hubProperties,
            PseudonymCryptoService cryptoService,
            PseudonymMappingRepository pseudonymMappingRepository,
            List<BurnedInTextInspector> burnedInTextInspectors,
            AuditService auditService) {
        this.hubProperties = hubProperties;
        this.cryptoService = cryptoService;
        this.pseudonymMappingRepository = pseudonymMappingRepository;
        this.burnedInTextInspectors = burnedInTextInspectors;
        this.auditService = auditService;
    }

    public record DeidentificationOutcome(
            boolean success,
            String errorReason,
            PseudonymMapping mapping,
            Path workingCopyDirectory,
            List<Path> deidentifiedFiles
    ) {}

    private record SavedPrivateAttribute(
            int tag,
            String privateCreator,
            VR vr,
            String[] stringValues,
            byte[] byteValues
    ) {}

    public boolean isMaskPixelRegionAction(DeidProfile profile) {
        if (profile == null) {
            return false;
        }
        if ("MASK_PIXEL_REGION".equalsIgnoreCase(profile.getBurnedInTextAction())) {
            return true;
        }
        return !profile.isBlockOnBurnedInText();
    }

    @Transactional
    public DeidentificationOutcome createDeidentifiedWorkingCopy(
            String siteCode,
            StudyRecord study,
            List<InstanceRecord> originalInstances,
            VendorConfig vendor,
            DeidProfile profile,
            VendorDeliveryJob job) {

        if (originalInstances == null || originalInstances.isEmpty()) {
            return new DeidentificationOutcome(
                    false,
                    "FR-08 Blocked: No eligible DICOM instances matched series selection criteria",
                    null,
                    null,
                    List.of());
        }

        boolean maskPixelMode = isMaskPixelRegionAction(profile);

        // 1. Pre-flight check for burned-in annotations across all instances (FR-14) when action is BLOCK_STUDY
        if (!maskPixelMode) {
            for (InstanceRecord instance : originalInstances) {
                for (BurnedInTextInspector inspector : burnedInTextInspectors) {
                    InspectionResult res = inspector.inspect(instance, null);
                    if (!res.safe()) {
                        auditService.record(
                                study.getSiteId(),
                                "SYSTEM_DEID_GATE",
                                "SYSTEM",
                                "DEIDENTIFICATION",
                                "BURNED_IN_TEXT_BLOCKED",
                                "VendorDeliveryJob",
                                job.getId().toString(),
                                "BLOCKED",
                                "{\"reason\":\"" + res.riskReason() + "\"}");
                        return new DeidentificationOutcome(false, res.riskReason(), null, null, List.of());
                    }
                }
            }
        }

        try {
            // 2. Prepare isolated vendor working copy directory (purge any stale files from previous runs/resends)
            Path workingDir = Path.of(
                    hubProperties.storage().rootDir(),
                    "working-copies",
                    siteCode,
                    vendor.getVendorCode(),
                    job.getId().toString());
            deleteDirectoryQuietly(workingDir);
            Files.createDirectories(workingDir);

            // 3. Read first instance to extract real Patient ID and Accession for the encrypted vault
            Attributes firstAttrs;
            try (DicomInputStream dis = new DicomInputStream(new File(originalInstances.getFirst().getOriginalRepoPath()))) {
                firstAttrs = dis.readDataset();
            }
            String realPatientId = firstAttrs.getString(Tag.PatientID, "UNKNOWN");
            String realAccession = firstAttrs.getString(Tag.AccessionNumber, "");

            // 4. Create or reuse PseudonymMapping for (siteId, studyId, vendorId)
            PseudonymMapping mapping = pseudonymMappingRepository
                    .findBySiteIdAndStudyIdAndVendorId(study.getSiteId(), study.getId(), vendor.getId())
                    .map(existing -> {
                        if (existing.getDateShiftDays() != profile.getDateShiftDays()) {
                            existing.setDateShiftDays(profile.getDateShiftDays());
                            return pseudonymMappingRepository.save(existing);
                        }
                        return existing;
                    })
                    .orElseGet(() -> {
                        PseudonymMapping m = new PseudonymMapping();
                        m.setId(UUID.randomUUID());
                        m.setSiteId(study.getSiteId());
                        m.setStudyId(study.getId());
                        m.setVendorId(vendor.getId());
                        m.setPseudoPatientId("PSEUDO-PAT-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
                        m.setPseudoAccessionNumber("PSEUDO-ACC-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase());
                        m.setPseudoStudyUid(UIDUtils.createUID());
                        m.setEncryptedRealPatientId(cryptoService.encrypt(realPatientId));
                        m.setEncryptedRealAccession(cryptoService.encrypt(realAccession));
                        m.setOriginalStudyUid(study.getStudyInstanceUid());
                        m.setDateShiftDays(profile.getDateShiftDays());
                        m.setCreatedAt(OffsetDateTime.now());
                        return pseudonymMappingRepository.save(m);
                    });

            Map<String, String> seriesUidMap = new HashMap<>();
            Map<String, String> uidRemapTable = new HashMap<>();
            if (study.getStudyInstanceUid() != null && !study.getStudyInstanceUid().isBlank()) {
                uidRemapTable.put(study.getStudyInstanceUid(), mapping.getPseudoStudyUid());
            }
            List<Path> outputFiles = new ArrayList<>();
            int maskedInstancesCount = 0;

            // 5. Transform each original DICOM object into a separate working copy
            for (InstanceRecord instance : originalInstances) {
                File sourceFile = new File(instance.getOriginalRepoPath());
                Attributes fmi;
                Attributes dataset;
                try (DicomInputStream dis = new DicomInputStream(sourceFile)) {
                    fmi = dis.readFileMetaInformation();
                    dataset = dis.readDataset();
                }

                // Deep dataset inspection via BurnedInTextInspector pipeline (Rule 10)
                boolean burnedInDetected = false;
                String detectedReason = null;
                for (BurnedInTextInspector inspector : burnedInTextInspectors) {
                    InspectionResult res = inspector.inspect(instance, dataset);
                    if (!res.safe()) {
                        burnedInDetected = true;
                        detectedReason = res.riskReason();
                        break;
                    }
                }

                if (burnedInDetected) {
                    if (!maskPixelMode) {
                        deleteDirectoryQuietly(workingDir);
                        auditService.record(
                                study.getSiteId(),
                                "SYSTEM_DEID_GATE",
                                "SYSTEM",
                                "DEIDENTIFICATION",
                                "BURNED_IN_TEXT_BLOCKED",
                                "VendorDeliveryJob",
                                job.getId().toString(),
                                "BLOCKED",
                                "{\"reason\":\"" + detectedReason + "\"}");
                        return new DeidentificationOutcome(false, detectedReason, null, null, List.of());
                    } else {
                        boolean maskedOk = maskBurnedInPixelRegion(dataset, fmi);
                        if (!maskedOk) {
                            String blockReason = "FR-14 Blocked: Burned-in text detected ("
                                    + detectedReason
                                    + ") and pixel banner cannot be safely masked (compressed or missing PixelData)";
                            deleteDirectoryQuietly(workingDir);
                            auditService.record(
                                    study.getSiteId(),
                                    "SYSTEM_DEID_GATE",
                                    "SYSTEM",
                                    "DEIDENTIFICATION",
                                    "BURNED_IN_TEXT_BLOCKED",
                                    "VendorDeliveryJob",
                                    job.getId().toString(),
                                    "BLOCKED",
                                    "{\"reason\":\"" + blockReason + "\"}");
                            return new DeidentificationOutcome(false, blockReason, null, null, List.of());
                        }
                        maskedInstancesCount++;
                    }
                }

                String origStudyUid = dataset.getString(Tag.StudyInstanceUID);
                if (origStudyUid != null && !origStudyUid.isBlank()) {
                    uidRemapTable.putIfAbsent(origStudyUid, mapping.getPseudoStudyUid());
                }
                String origSeriesUid = dataset.getString(Tag.SeriesInstanceUID);
                String pseudoSeriesUid = seriesUidMap.computeIfAbsent(origSeriesUid, k -> UIDUtils.createUID());
                if (origSeriesUid != null && !origSeriesUid.isBlank()) {
                    uidRemapTable.put(origSeriesUid, pseudoSeriesUid);
                }
                String origSopUid = dataset.getString(Tag.SOPInstanceUID);
                String pseudoSopUid = (origSopUid != null && !origSopUid.isBlank())
                        ? uidRemapTable.computeIfAbsent(origSopUid, k -> UIDUtils.createUID())
                        : UIDUtils.createUID();

                applyPs315ConfidentialityProfile(
                        dataset,
                        fmi,
                        mapping,
                        pseudoSeriesUid,
                        pseudoSopUid,
                        profile,
                        burnedInDetected && maskPixelMode,
                        uidRemapTable);

                Path outFile = workingDir.resolve(pseudoSopUid + ".dcm");
                try (DicomOutputStream dos = new DicomOutputStream(outFile.toFile())) {
                    dos.writeDataset(fmi, dataset);
                }
                outputFiles.add(outFile);
            }

            if (maskedInstancesCount > 0) {
                auditService.record(
                        study.getSiteId(),
                        "SYSTEM_DEID_GATE",
                        "SYSTEM",
                        "DEIDENTIFICATION",
                        "BURNED_IN_TEXT_PIXEL_MASKED",
                        "VendorDeliveryJob",
                        job.getId().toString(),
                        "SUCCESS",
                        "{\"maskedInstances\":" + maskedInstancesCount
                                + ",\"action\":\"MASK_PIXEL_REGION\"}");
            }

            auditService.record(
                    study.getSiteId(),
                    "SYSTEM_DEID_GATE",
                    "SYSTEM",
                    "DEIDENTIFICATION",
                    "PS315_WORKING_COPY_CREATED",
                    "VendorDeliveryJob",
                    job.getId().toString(),
                    "SUCCESS",
                    "{\"profileCode\":\"" + profile.getProfileCode()
                            + "\",\"profileVersion\":" + profile.getProfileVersion()
                            + ",\"dateTimePolicy\":\"" + profile.getDateTimePolicy()
                            + "\",\"burnedInTextAction\":\"" + profile.getBurnedInTextAction()
                            + "\",\"instances\":" + outputFiles.size() + "}");

            return new DeidentificationOutcome(true, null, mapping, workingDir, outputFiles);
        } catch (IOException e) {
            String reason = "De-identification I/O failure: " + e.getMessage();
            return new DeidentificationOutcome(false, reason, null, null, List.of());
        }
    }

    public void applyPs315ConfidentialityProfile(
            Attributes dataset,
            Attributes fmi,
            PseudonymMapping mapping,
            String pseudoSeriesUid,
            String pseudoSopUid,
            DeidProfile profile,
            boolean pixelMasked) {
        applyPs315ConfidentialityProfile(
                dataset, fmi, mapping, pseudoSeriesUid, pseudoSopUid, profile, pixelMasked, new HashMap<>());
    }

    public void applyPs315ConfidentialityProfile(
            Attributes dataset,
            Attributes fmi,
            PseudonymMapping mapping,
            String pseudoSeriesUid,
            String pseudoSopUid,
            DeidProfile profile,
            boolean pixelMasked,
            Map<String, String> uidRemapTable) {

        Map<String, String> effectiveUidMap = uidRemapTable != null ? uidRemapTable : new HashMap<>();
        String origStudyUid = dataset.getString(Tag.StudyInstanceUID);
        if (origStudyUid != null && !origStudyUid.isBlank()) {
            effectiveUidMap.putIfAbsent(origStudyUid, mapping.getPseudoStudyUid());
        }
        String origSeriesUid = dataset.getString(Tag.SeriesInstanceUID);
        if (origSeriesUid != null && !origSeriesUid.isBlank()) {
            effectiveUidMap.putIfAbsent(origSeriesUid, pseudoSeriesUid);
        }
        String origSopUid = dataset.getString(Tag.SOPInstanceUID);
        if (origSopUid != null && !origSopUid.isBlank()) {
            effectiveUidMap.putIfAbsent(origSopUid, pseudoSopUid);
        }

        // Rule 1 & Rule 5: Remove Patient Name, Birth Date, Address, Phone Numbers,
        // Institution, Referring and Performing Physician Names, and RIS Order IDs recursively
        scrubDirectAndInstitutionalIdentifiersRecursively(dataset, mapping);

        // Rule 9: Private tags -> Remove unless on an agreed allow-list (recursively across sequences)
        if (profile.isStripPrivateTags()) {
            stripPrivateTagsExceptAllowList(dataset, profile.getPrivateTagAllowList());
        }

        // Rule 7: Patient Age, Sex, Size, Weight -> Keep (when retainPatientAgeSex is true)
        if (!profile.isRetainPatientAgeSex()) {
            dataset.remove(Tag.PatientAge);
            dataset.remove(Tag.PatientSex);
            dataset.remove(Tag.PatientSize);
            dataset.remove(Tag.PatientWeight);
        }

        // Rule 8: Pixel spacing, slice thickness, kVp, mAs, contrast -> Keep (when retainDeviceAcquisitionParams is true)
        if (!profile.isRetainDeviceAcquisitionParams()) {
            dataset.remove(Tag.PixelSpacing);
            dataset.remove(Tag.SliceThickness);
            dataset.remove(Tag.KVP);
            dataset.remove(Tag.XRayTubeCurrent);
            dataset.remove(Tag.Exposure);
            dataset.remove(Tag.ExposureInuAs);
            dataset.remove(Tag.ContrastBolusAgent);
            dataset.remove(Tag.ContrastBolusRoute);
            dataset.remove(Tag.ProtocolName);
            dataset.remove(Tag.Manufacturer);
            dataset.remove(Tag.ManufacturerModelName);
            dataset.remove(Tag.SoftwareVersions);
        }

        // Rule 6: Study and series dates and times -> Configurable (SHIFT_DATES, KEEP_DATES, REMOVE_DATES)
        String datePolicy = profile.getDateTimePolicy() != null
                ? profile.getDateTimePolicy().trim().toUpperCase(Locale.ROOT)
                : "SHIFT_DATES";
        applyDateTimePolicyRecursively(dataset, datePolicy, profile.getDateShiftDays());

        // Rule 2: Patient ID, Other Patient IDs -> Replace with pseudonym (FR-12)
        dataset.setString(Tag.PatientID, VR.LO, mapping.getPseudoPatientId());
        dataset.setString(Tag.OtherPatientIDs, VR.LO, mapping.getPseudoPatientId());

        // Rule 3: Accession number & StudyID -> Replace with pseudonym
        dataset.setString(Tag.AccessionNumber, VR.SH, mapping.getPseudoAccessionNumber());
        dataset.setString(Tag.StudyID, VR.SH, mapping.getPseudoAccessionNumber());

        // Rule 4: Study, Series, SOP Instance, FrameOfReference, and Referenced UIDs -> Replace with new UIDs
        remapInstanceAndReferenceUidsRecursively(dataset, effectiveUidMap);
        dataset.setString(Tag.StudyInstanceUID, VR.UI, mapping.getPseudoStudyUid());
        dataset.setString(Tag.SeriesInstanceUID, VR.UI, pseudoSeriesUid);
        dataset.setString(Tag.SOPInstanceUID, VR.UI, pseudoSopUid);

        // Standard DICOM PS3.15 de-identification markers
        dataset.setString(Tag.PatientIdentityRemoved, VR.CS, "YES");
        String methodDesc = "DICOM PS3.15 Basic Application Level Confidentiality Profile - "
                + profile.getProfileCode() + " v" + profile.getProfileVersion()
                + " [" + datePolicy + (profile.getDateShiftDays() != 0 && "SHIFT_DATES".equals(datePolicy) ? ":" + profile.getDateShiftDays() + "d" : "") + "]"
                + (pixelMasked ? " [PIXEL_BANNER_MASKED]" : "");
        dataset.setString(Tag.DeidentificationMethod, VR.LO, methodDesc);

        if (fmi != null) {
            fmi.setString(Tag.MediaStorageSOPInstanceUID, VR.UI, pseudoSopUid);
        }
    }

    private void scrubDirectAndInstitutionalIdentifiersRecursively(Attributes item, PseudonymMapping mapping) {
        if (item == null) {
            return;
        }
        for (int tag : TAGS_TO_REMOVE) {
            item.remove(tag);
        }
        if (item.contains(Tag.PatientID)) {
            item.setString(Tag.PatientID, VR.LO, mapping.getPseudoPatientId());
        }
        if (item.contains(Tag.OtherPatientIDs)) {
            item.setString(Tag.OtherPatientIDs, VR.LO, mapping.getPseudoPatientId());
        }
        if (item.contains(Tag.AccessionNumber)) {
            item.setString(Tag.AccessionNumber, VR.SH, mapping.getPseudoAccessionNumber());
        }
        if (item.contains(Tag.StudyID)) {
            item.setString(Tag.StudyID, VR.SH, mapping.getPseudoAccessionNumber());
        }
        for (int tag : item.tags()) {
            if (item.getVR(tag) == VR.SQ) {
                Sequence seq = item.getSequence(tag);
                if (seq != null) {
                    for (Attributes child : seq) {
                        scrubDirectAndInstitutionalIdentifiersRecursively(child, mapping);
                    }
                }
            }
        }
    }

    private void applyDateTimePolicyRecursively(Attributes item, String datePolicy, int dateShiftDays) {
        if (item == null) {
            return;
        }
        if ("REMOVE_DATES".equals(datePolicy)) {
            for (int dateTag : DATE_TAGS_TO_SHIFT) {
                item.remove(dateTag);
            }
            for (int timeTag : TIME_TAGS_CONFIGURABLE) {
                item.remove(timeTag);
            }
            for (int dtTag : DATETIME_TAGS_CONFIGURABLE) {
                item.remove(dtTag);
            }
            for (int tag : item.tags()) {
                VR vr = item.getVR(tag);
                if (vr == VR.DA || vr == VR.TM || vr == VR.DT) {
                    item.remove(tag);
                }
            }
        } else if (!"KEEP_DATES".equals(datePolicy) && dateShiftDays != 0) {
            Set<Integer> shiftedTags = new HashSet<>();
            for (int dateTag : DATE_TAGS_TO_SHIFT) {
                if (shiftedTags.add(dateTag)) {
                    shiftSingleDateAttribute(item, dateTag, dateShiftDays);
                }
            }
            for (int dtTag : DATETIME_TAGS_CONFIGURABLE) {
                if (shiftedTags.add(dtTag)) {
                    shiftSingleDateTimeAttribute(item, dtTag, dateShiftDays);
                }
            }
            for (int tag : item.tags()) {
                if (!shiftedTags.add(tag)) {
                    continue;
                }
                VR vr = item.getVR(tag);
                if (vr == VR.DA) {
                    shiftSingleDateAttribute(item, tag, dateShiftDays);
                } else if (vr == VR.DT) {
                    shiftSingleDateTimeAttribute(item, tag, dateShiftDays);
                }
            }
        }

        for (int tag : item.tags()) {
            if (item.getVR(tag) == VR.SQ) {
                Sequence seq = item.getSequence(tag);
                if (seq != null) {
                    for (Attributes child : seq) {
                        applyDateTimePolicyRecursively(child, datePolicy, dateShiftDays);
                    }
                }
            }
        }
    }

    private void shiftSingleDateAttribute(Attributes item, int dateTag, int dateShiftDays) {
        String rawDate = item.getString(dateTag);
        if (rawDate == null || rawDate.isBlank()) {
            return;
        }
        String digits = rawDate.replaceAll("[^0-9]", "");
        if (digits.length() >= 8) {
            try {
                LocalDate parsed = LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
                LocalDate shifted = parsed.plusDays(dateShiftDays);
                item.setString(dateTag, VR.DA, shifted.format(DateTimeFormatter.BASIC_ISO_DATE));
            } catch (DateTimeParseException ignored) {
                item.remove(dateTag);
            }
        } else {
            item.remove(dateTag);
        }
    }

    private void shiftSingleDateTimeAttribute(Attributes item, int dtTag, int dateShiftDays) {
        String rawDt = item.getString(dtTag);
        if (rawDt == null || rawDt.isBlank()) {
            return;
        }
        String trimmed = rawDt.trim();
        if (trimmed.length() >= 8 && trimmed.substring(0, 8).matches("[0-9]{8}")) {
            try {
                LocalDate parsed = LocalDate.parse(trimmed.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
                LocalDate shifted = parsed.plusDays(dateShiftDays);
                String suffix = trimmed.substring(8);
                item.setString(dtTag, VR.DT, shifted.format(DateTimeFormatter.BASIC_ISO_DATE) + suffix);
            } catch (DateTimeParseException ignored) {
                item.remove(dtTag);
            }
        } else {
            item.remove(dtTag);
        }
    }

    private void remapInstanceAndReferenceUidsRecursively(Attributes item, Map<String, String> uidRemapTable) {
        if (item == null) {
            return;
        }
        int[] instanceUidTags = {
            Tag.StudyInstanceUID,
            Tag.SeriesInstanceUID,
            Tag.SOPInstanceUID,
            Tag.FrameOfReferenceUID,
            Tag.SynchronizationFrameOfReferenceUID,
            Tag.ConcatenationUID,
            Tag.DimensionOrganizationUID,
            Tag.IrradiationEventUID,
            Tag.ReferencedSOPInstanceUID,
            Tag.ReferencedFrameOfReferenceUID
        };
        for (int uidTag : instanceUidTags) {
            String rawUid = item.getString(uidTag);
            if (rawUid != null && !rawUid.isBlank() && !isStandardDicomClassOrSyntaxUid(rawUid)) {
                String mappedUid = uidRemapTable.computeIfAbsent(rawUid.trim(), k -> UIDUtils.createUID());
                item.setString(uidTag, VR.UI, mappedUid);
            }
        }
        for (int tag : item.tags()) {
            if (item.getVR(tag) == VR.SQ) {
                Sequence seq = item.getSequence(tag);
                if (seq != null) {
                    for (Attributes child : seq) {
                        remapInstanceAndReferenceUidsRecursively(child, uidRemapTable);
                    }
                }
            }
        }
    }

    private boolean isStandardDicomClassOrSyntaxUid(String uid) {
        return uid != null && uid.trim().startsWith("1.2.840.10008.");
    }

    /**
     * Rule 9: Removes all private tags (odd group numbers) recursively across the dataset and nested sequences
     * unless explicitly listed on the agreed allow-list.
     * Supports allow-list entries formatted as hex tags (e.g. "0009,1001", "(0009,1001)", "00091001")
     * or Private Creator names (e.g. "GEMS_IDEN_01", "TOSHIBA_MEC_CT3").
     */
    public void stripPrivateTagsExceptAllowList(Attributes dataset, String allowListRaw) {
        if (dataset == null) {
            return;
        }
        Set<String> allowedTokens = parsePrivateAllowListTokens(allowListRaw);
        if (allowedTokens.isEmpty()) {
            dataset.removePrivateAttributes();
            return;
        }
        stripPrivateTagsRecursively(dataset, allowedTokens);
    }

    private Set<String> parsePrivateAllowListTokens(String allowListRaw) {
        Set<String> allowedTokens = new HashSet<>();
        if (allowListRaw == null || allowListRaw.isBlank()) {
            return allowedTokens;
        }
        // Collapse (gggg,eeee) or gggg,eeee hex tag pairs into ggggeeee before splitting on commas/semicolons
        String normalizedRaw = allowListRaw.replaceAll("(?i)\\(?([0-9a-f]{4})\\s*,\\s*([0-9a-f]{4})\\)?", "$1$2");
        for (String token : normalizedRaw.split("[,;\\n]")) {
            String cleaned = token.trim().toUpperCase(Locale.ROOT)
                    .replace("(", "")
                    .replace(")", "")
                    .replace("0X", "");
            if (!cleaned.isEmpty()) {
                allowedTokens.add(cleaned);
                allowedTokens.add(cleaned.replace(",", "").replace(" ", ""));
            }
        }
        return allowedTokens;
    }

    private boolean isPrivateTagAllowed(Attributes item, int tag, Set<String> allowedTokens) {
        if (allowedTokens == null || allowedTokens.isEmpty()) {
            return false;
        }
        int group = TagUtils.groupNumber(tag);
        int element = TagUtils.elementNumber(tag);
        String hexComma = String.format("%04X,%04X", group, element);
        String hex8 = String.format("%08X", tag);
        String hexBaseElement = String.format("%04X,%04X", group, element & 0x00FF);
        String hexBase8 = String.format("%04X%04X", group, element & 0x00FF);
        String creator = item.privateCreatorOf(tag);
        String creatorUpper = creator != null ? creator.trim().toUpperCase(Locale.ROOT) : "";

        return allowedTokens.contains(hexComma)
                || allowedTokens.contains(hex8)
                || allowedTokens.contains(hexBaseElement)
                || allowedTokens.contains(hexBase8)
                || (!creatorUpper.isEmpty() && allowedTokens.contains(creatorUpper));
    }

    private void stripPrivateTagsRecursively(Attributes item, Set<String> allowedTokens) {
        if (item == null) {
            return;
        }
        int[] tags = item.tags();
        // First recurse into nested Sequence items
        for (int tag : tags) {
            if (item.getVR(tag) == VR.SQ) {
                Sequence seq = item.getSequence(tag);
                if (seq != null) {
                    for (Attributes child : seq) {
                        stripPrivateTagsRecursively(child, allowedTokens);
                    }
                }
            }
        }

        Set<Integer> allowedTagNumbers = new HashSet<>();
        for (int tag : tags) {
            if (!TagUtils.isPrivateTag(tag) || TagUtils.isPrivateCreator(tag)) {
                continue;
            }
            if (isPrivateTagAllowed(item, tag, allowedTokens)) {
                allowedTagNumbers.add(tag);
                allowedTagNumbers.add(TagUtils.creatorTagOf(tag));
            }
        }

        for (int tag : tags) {
            if (TagUtils.isPrivateTag(tag) && !allowedTagNumbers.contains(tag)) {
                item.remove(tag);
            }
        }
    }

    /**
     * Rule 10 (MASK_PIXEL_REGION): Blacks out the top header banner rows of uncompressed pixel data
     * across all frames in the working copy dataset and marks BurnedInAnnotation (0028,0301) = NO.
     * Returns false if PixelData is compressed/encapsulated or missing so the caller can fail-safe block the study.
     */
    public boolean maskBurnedInPixelRegion(Attributes dataset) {
        return maskBurnedInPixelRegion(dataset, null);
    }

    public boolean maskBurnedInPixelRegion(Attributes dataset, Attributes fmi) {
        if (dataset == null) {
            return false;
        }
        if (fmi != null) {
            String tsuid = fmi.getString(Tag.TransferSyntaxUID);
            if (tsuid != null && !isUncompressedTransferSyntax(tsuid)) {
                return false;
            }
        }
        Object pixelVal = dataset.getValue(Tag.PixelData);
        if (pixelVal == null || pixelVal instanceof Fragments) {
            return false;
        }
        try {
            byte[] rawPixels = dataset.getBytes(Tag.PixelData);
            if (rawPixels == null || rawPixels.length == 0) {
                return false;
            }
            byte[] maskedPixels = Arrays.copyOf(rawPixels, rawPixels.length);
            int rows = dataset.getInt(Tag.Rows, 0);
            int cols = dataset.getInt(Tag.Columns, 0);
            int frames = Math.max(1, dataset.getInt(Tag.NumberOfFrames, 1));
            int bitsAllocated = dataset.getInt(Tag.BitsAllocated, 16);
            int samplesPerPixel = dataset.getInt(Tag.SamplesPerPixel, 1);
            int bytesPerPixel = Math.max(1, ((bitsAllocated + 7) / 8) * samplesPerPixel);

            if (rows > 0 && cols > 0) {
                int frameBytes = rows * cols * bytesPerPixel;
                int bannerRows = Math.max(1, (int) Math.ceil(rows * 0.15));
                int bannerBytesPerFrame = Math.min(frameBytes, bannerRows * cols * bytesPerPixel);
                for (int f = 0; f < frames; f++) {
                    int frameOffset = f * frameBytes;
                    if (frameOffset >= maskedPixels.length) {
                        break;
                    }
                    int endOffset = Math.min(maskedPixels.length, frameOffset + bannerBytesPerFrame);
                    Arrays.fill(maskedPixels, frameOffset, endOffset, (byte) 0);
                }
            } else {
                int bytesToMask = Math.min(maskedPixels.length, Math.max(1, maskedPixels.length * 15 / 100));
                Arrays.fill(maskedPixels, 0, bytesToMask, (byte) 0);
            }
            VR pixelVr = dataset.getVR(Tag.PixelData);
            dataset.setBytes(Tag.PixelData, pixelVr != null ? pixelVr : VR.OW, maskedPixels);
        } catch (IOException | RuntimeException e) {
            return false;
        }
        dataset.setString(Tag.BurnedInAnnotation, VR.CS, "NO");
        dataset.setString(Tag.RecognizableVisualFeatures, VR.CS, "NO");
        return true;
    }

    private boolean isUncompressedTransferSyntax(String tsuid) {
        String trimmed = tsuid.trim();
        return UID.ImplicitVRLittleEndian.equals(trimmed)
                || UID.ExplicitVRLittleEndian.equals(trimmed)
                || UID.ExplicitVRBigEndian.equals(trimmed)
                || UID.DeflatedExplicitVRLittleEndian.equals(trimmed);
    }

    public int countPrivateDataTags(Attributes dataset) {
        if (dataset == null) {
            return 0;
        }
        int count = 0;
        for (int tag : dataset.tags()) {
            if (TagUtils.isPrivateTag(tag) && !TagUtils.isPrivateCreator(tag)) {
                count++;
            }
            if (dataset.getVR(tag) == VR.SQ) {
                Sequence seq = dataset.getSequence(tag);
                if (seq != null) {
                    for (Attributes child : seq) {
                        count += countPrivateDataTags(child);
                    }
                }
            }
        }
        return count;
    }

    public int countUnapprovedPrivateDataTags(Attributes dataset, String allowListRaw) {
        if (dataset == null) {
            return 0;
        }
        Set<String> allowedTokens = parsePrivateAllowListTokens(allowListRaw);
        return countUnapprovedPrivateDataTagsRecursively(dataset, allowedTokens);
    }

    private int countUnapprovedPrivateDataTagsRecursively(Attributes item, Set<String> allowedTokens) {
        if (item == null) {
            return 0;
        }
        int count = 0;
        for (int tag : item.tags()) {
            if (TagUtils.isPrivateTag(tag) && !TagUtils.isPrivateCreator(tag)) {
                if (!isPrivateTagAllowed(item, tag, allowedTokens)) {
                    count++;
                }
            }
            if (item.getVR(tag) == VR.SQ) {
                Sequence seq = item.getSequence(tag);
                if (seq != null) {
                    for (Attributes child : seq) {
                        count += countUnapprovedPrivateDataTagsRecursively(child, allowedTokens);
                    }
                }
            }
        }
        return count;
    }

    private void deleteDirectoryQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
        }
    }
}

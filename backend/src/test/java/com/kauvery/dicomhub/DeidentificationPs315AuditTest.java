package com.kauvery.dicomhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.kauvery.dicomhub.api.CommandCentreController;
import com.kauvery.dicomhub.api.CommandCentreController.AnonymizeRuleComparisonRowDto;
import com.kauvery.dicomhub.api.CommandCentreController.StudyAnonymizationPreviewDto;
import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.dicom.DicomWorkflowOrchestratorService;
import com.kauvery.dicomhub.domain.entity.DeidProfile;
import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import com.kauvery.dicomhub.domain.entity.PseudonymMapping;
import com.kauvery.dicomhub.domain.entity.RoutingRule;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.repository.DeidProfileRepository;
import com.kauvery.dicomhub.domain.repository.PseudonymMappingRepository;
import com.kauvery.dicomhub.domain.repository.RoutingRuleRepository;
import com.kauvery.dicomhub.security.PseudonymCryptoService;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.util.UIDUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DeidentificationPs315AuditTest {

    @Autowired
    private HubProperties hubProperties;

    @Autowired
    private DicomWorkflowOrchestratorService orchestratorService;

    @Autowired
    private PseudonymMappingRepository pseudonymMappingRepository;

    @Autowired
    private DeidProfileRepository deidProfileRepository;

    @Autowired
    private RoutingRuleRepository routingRuleRepository;

    @Autowired
    private PseudonymCryptoService cryptoService;

    @Autowired
    private CommandCentreController commandCentreController;

    private UUID modifiedRuleId;
    private String originalRuleModality;

    @BeforeEach
    void ensureCtModalityRoutableForAuditTests() {
        UUID siteId = hubProperties.defaultSiteId();
        List<RoutingRule> activeRules = routingRuleRepository.findBySiteIdAndActiveTrueOrderByPriorityOrderAsc(siteId);
        boolean hasCtRule = activeRules.stream()
                .anyMatch(r -> r.getModality() != null && (r.getModality().contains("CT") || r.getModality().contains("*")));
        if (!hasCtRule && !activeRules.isEmpty()) {
            RoutingRule first = activeRules.getFirst();
            modifiedRuleId = first.getId();
            originalRuleModality = first.getModality();
            first.setModality(originalRuleModality + ",CT");
            routingRuleRepository.save(first);
        }
    }

    @AfterEach
    void restoreRoutingRuleModality() {
        if (modifiedRuleId != null && originalRuleModality != null) {
            routingRuleRepository.findById(modifiedRuleId).ifPresent(r -> {
                r.setModality(originalRuleModality);
                routingRuleRepository.save(r);
            });
            modifiedRuleId = null;
            originalRuleModality = null;
        }
    }

    @Test
    void shouldEnforcePs315DeidentificationAndKeepOriginalRepoUnmodified() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();
        String originalStudyUid = UIDUtils.createUID();
        String originalSeriesUid = UIDUtils.createUID();
        String originalSopUid = UIDUtils.createUID();
        String realPatientId = "KAUVERY-REAL-PAT-998877";
        String realOtherPatientId = "KAUVERY-ALT-ID-112233";
        String realPatientName = "RAMESH^KUMAR^^^";
        String realAccession = "KAUVERY-ACC-554433";

        Attributes dataset = new Attributes();
        // Rule 1: Patient Name, Birth Date, Address, Phone Numbers
        dataset.setString(Tag.PatientName, VR.PN, realPatientName);
        dataset.setString(Tag.PatientBirthDate, VR.DA, "19750412");
        dataset.setString(Tag.PatientBirthTime, VR.TM, "083000");
        dataset.setString(Tag.PatientAddress, VR.LO, "14 Alwarpet Street, Chennai");
        dataset.setString(Tag.PatientTelephoneNumbers, VR.SH, "+919840012345");
        dataset.setString(Tag.OtherPatientNames, VR.PN, "RAMESH^K");
        // Rule 2: Patient ID, Other Patient IDs
        dataset.setString(Tag.PatientID, VR.LO, realPatientId);
        dataset.setString(Tag.OtherPatientIDs, VR.LO, realOtherPatientId);
        // Rule 3: Accession Number
        dataset.setString(Tag.AccessionNumber, VR.SH, realAccession);
        // Rule 4: Study, Series, SOP Instance UIDs
        dataset.setString(Tag.StudyInstanceUID, VR.UI, originalStudyUid);
        dataset.setString(Tag.SeriesInstanceUID, VR.UI, originalSeriesUid);
        dataset.setString(Tag.SOPInstanceUID, VR.UI, originalSopUid);
        dataset.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
        // Rule 5: Institution, Referring and Performing Physician Names
        dataset.setString(Tag.InstitutionName, VR.LO, "Kauvery Hospital Chennai");
        dataset.setString(Tag.ReferringPhysicianName, VR.PN, "DR^SHARMA");
        dataset.setString(Tag.PerformingPhysicianName, VR.PN, "DR^SUNDAR");
        dataset.setString(Tag.OperatorsName, VR.PN, "TECH^RAMESH");
        // Rule 6: Study and Series Dates and Times
        dataset.setString(Tag.StudyDate, VR.DA, "20261123");
        dataset.setString(Tag.SeriesDate, VR.DA, "20261123");
        dataset.setString(Tag.AcquisitionDate, VR.DA, "20261123");
        dataset.setString(Tag.ContentDate, VR.DA, "20261123");
        dataset.setString(Tag.StudyTime, VR.TM, "101500");
        // Rule 7: Patient Age, Sex, Size, Weight
        dataset.setString(Tag.PatientAge, VR.AS, "051Y");
        dataset.setString(Tag.PatientSex, VR.CS, "M");
        dataset.setDouble(Tag.PatientSize, VR.DS, 1.75);
        dataset.setDouble(Tag.PatientWeight, VR.DS, 76.0);
        // Rule 8: Pixel spacing, slice thickness, kVp, mAs, contrast
        dataset.setString(Tag.PixelSpacing, VR.DS, "0.625", "0.625");
        dataset.setDouble(Tag.SliceThickness, VR.DS, 1.25);
        dataset.setDouble(Tag.KVP, VR.DS, 120.0);
        dataset.setInt(Tag.XRayTubeCurrent, VR.IS, 250);
        dataset.setInt(Tag.Exposure, VR.IS, 190);
        dataset.setString(Tag.ContrastBolusAgent, VR.LO, "IOHEXOL 350");
        // Rule 9: Private tags
        dataset.setString("KAUVERY_PRIVATE_CREATOR", 0x00091001, VR.LO, "SECRET_SCANNER_NOTE");
        // Rule 10: Burned-in text
        dataset.setString(Tag.BurnedInAnnotation, VR.CS, "NO");
        dataset.setString(Tag.Modality, VR.CS, "CT");
        dataset.setString(Tag.StudyDescription, VR.LO, "CT KUB PLAIN");
        dataset.setString(Tag.BodyPartExamined, VR.CS, "ABDOMEN");

        Attributes fmi = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
        InstanceRecord storedInstance = orchestratorService.ingestDicomInstance(
                siteId, "KAUVERY_CT01", "DICOMHUB_AE", fmi, dataset);

        // 1. Verify Original DICOM Repository preserves unmodified patient attributes
        Attributes originalRepoDataset;
        try (DicomInputStream dis = new DicomInputStream(new File(storedInstance.getOriginalRepoPath()))) {
            originalRepoDataset = dis.readDataset();
        }
        assertThat(originalRepoDataset.getString(Tag.PatientID)).isEqualTo(realPatientId);
        assertThat(originalRepoDataset.getString(Tag.OtherPatientIDs)).isEqualTo(realOtherPatientId);
        assertThat(originalRepoDataset.getString(Tag.PatientName)).isEqualTo(realPatientName);
        assertThat(originalRepoDataset.getString(Tag.StudyInstanceUID)).isEqualTo(originalStudyUid);

        // 2. Complete & route study -> generates vendor working copy
        List<VendorDeliveryJob> jobs = orchestratorService.completeValidateAndRouteStudy(
                siteId, storedInstance.getStudyId(), false);
        assertThat(jobs).hasSize(1);
        VendorDeliveryJob job = jobs.getFirst();
        assertThat(job.getStatus()).isEqualTo("QUEUED");

        // 3. AC-2 Audit on de-identified vendor working copy across all 10 PS3.15 rule groups
        Path workingDir = Path.of(job.getWorkingCopyDir());
        List<Path> workingFiles = Files.list(workingDir).filter(p -> p.toString().endsWith(".dcm")).toList();
        assertThat(workingFiles).hasSize(1);

        Attributes workingFmi;
        Attributes workingDataset;
        try (DicomInputStream dis = new DicomInputStream(workingFiles.getFirst().toFile())) {
            workingFmi = dis.readFileMetaInformation();
            workingDataset = dis.readDataset();
        }

        // Rule 1: Direct patient identifiers must be removed
        assertThat(workingDataset.getString(Tag.PatientName)).isNull();
        assertThat(workingDataset.getString(Tag.PatientBirthDate)).isNull();
        assertThat(workingDataset.getString(Tag.PatientBirthTime)).isNull();
        assertThat(workingDataset.getString(Tag.PatientAddress)).isNull();
        assertThat(workingDataset.getString(Tag.PatientTelephoneNumbers)).isNull();
        assertThat(workingDataset.getString(Tag.OtherPatientNames)).isNull();

        // Rule 2: Patient ID and Other Patient IDs replaced with pseudonym
        assertThat(workingDataset.getString(Tag.PatientID))
                .isNotEqualTo(realPatientId)
                .startsWith("PSEUDO-PAT-");
        assertThat(workingDataset.getString(Tag.OtherPatientIDs))
                .isNotEqualTo(realOtherPatientId)
                .isEqualTo(workingDataset.getString(Tag.PatientID))
                .startsWith("PSEUDO-PAT-");

        // Rule 3: Accession number replaced with pseudonym
        assertThat(workingDataset.getString(Tag.AccessionNumber))
                .isNotEqualTo(realAccession)
                .startsWith("PSEUDO-ACC-");

        // Rule 4: Study, Series, SOP Instance UIDs replaced with new UIDs
        assertThat(workingDataset.getString(Tag.StudyInstanceUID)).isNotEqualTo(originalStudyUid);
        assertThat(workingDataset.getString(Tag.SeriesInstanceUID)).isNotEqualTo(originalSeriesUid);
        assertThat(workingDataset.getString(Tag.SOPInstanceUID)).isNotEqualTo(originalSopUid);
        assertThat(workingFmi.getString(Tag.MediaStorageSOPInstanceUID))
                .isEqualTo(workingDataset.getString(Tag.SOPInstanceUID));
        assertThat(workingDataset.getString(Tag.PatientIdentityRemoved)).isEqualTo("YES");

        // Rule 5: Institution, referring and performing physician names removed
        assertThat(workingDataset.getString(Tag.InstitutionName)).isNull();
        assertThat(workingDataset.getString(Tag.ReferringPhysicianName)).isNull();
        assertThat(workingDataset.getString(Tag.PerformingPhysicianName)).isNull();
        assertThat(workingDataset.getString(Tag.OperatorsName)).isNull();

        // Rule 6: Study and series dates shifted by profile offset (-42 days from 20261123 -> 20261012)
        assertThat(workingDataset.getString(Tag.StudyDate)).isEqualTo("20261012");
        assertThat(workingDataset.getString(Tag.SeriesDate)).isEqualTo("20261012");
        assertThat(workingDataset.getString(Tag.AcquisitionDate)).isEqualTo("20261012");
        assertThat(workingDataset.getString(Tag.ContentDate)).isEqualTo("20261012");

        // Rule 7: Patient Age, Sex, Size, Weight kept
        assertThat(workingDataset.getString(Tag.PatientAge)).isEqualTo("051Y");
        assertThat(workingDataset.getString(Tag.PatientSex)).isEqualTo("M");
        assertThat(workingDataset.getDouble(Tag.PatientSize, 0.0)).isEqualTo(1.75);
        assertThat(workingDataset.getDouble(Tag.PatientWeight, 0.0)).isEqualTo(76.0);

        // Rule 8: Pixel spacing, slice thickness, kVp, mAs, contrast kept
        assertThat(workingDataset.getStrings(Tag.PixelSpacing)).containsExactly("0.625", "0.625");
        assertThat(workingDataset.getDouble(Tag.SliceThickness, 0.0)).isEqualTo(1.25);
        assertThat(workingDataset.getDouble(Tag.KVP, 0.0)).isEqualTo(120.0);
        assertThat(workingDataset.getInt(Tag.XRayTubeCurrent, 0)).isEqualTo(250);
        assertThat(workingDataset.getInt(Tag.Exposure, 0)).isEqualTo(190);
        assertThat(workingDataset.getString(Tag.ContrastBolusAgent)).isEqualTo("IOHEXOL 350");

        // Rule 9: Unapproved private tags removed
        assertThat(workingDataset.getString(0x00091001)).isNull();

        // 4. Verify encrypted pseudonym vault on-premise
        PseudonymMapping mapping = pseudonymMappingRepository
                .findBySiteIdAndStudyIdAndVendorId(siteId, storedInstance.getStudyId(), job.getVendorId())
                .orElseThrow();
        assertThat(mapping.getEncryptedRealPatientId()).isNotEqualTo(realPatientId);
        assertThat(cryptoService.decrypt(mapping.getEncryptedRealPatientId())).isEqualTo(realPatientId);
        assertThat(cryptoService.decrypt(mapping.getEncryptedRealAccession())).isEqualTo(realAccession);

        // 5. Verify live side-by-side anonymization preview endpoint returns all 10 compliant rule comparisons
        StudyAnonymizationPreviewDto preview = commandCentreController.getStudyAnonymizationPreview(
                storedInstance.getStudyId(), siteId, false);
        assertThat(preview.allTenRulesCompliant()).isTrue();
        assertThat(preview.ruleComparisons()).hasSize(10);
        assertThat(preview.ruleComparisons())
                .allMatch(AnonymizeRuleComparisonRowDto::compliant);
    }

    @Test
    void shouldSupportPrivateTagAllowListAndBurnedInPixelMaskingWhenConfigured() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();
        DeidProfile profile = deidProfileRepository.findBySiteIdOrderByProfileCodeAsc(siteId).getFirst();

        // Save original settings to restore after test
        String origAllowList = profile.getPrivateTagAllowList();
        String origBurnedAction = profile.getBurnedInTextAction();
        boolean origBlockBurned = profile.isBlockOnBurnedInText();

        try {
            // Configure allow-list for private tag (0009,1010) and MASK_PIXEL_REGION for burned-in text
            profile.setPrivateTagAllowList("0009,1010");
            profile.setBurnedInTextAction("MASK_PIXEL_REGION");
            commandCentreController.updateDeidProfile(profile.getId(), siteId, profile);

            Attributes dataset = new Attributes();
            dataset.setString(Tag.PatientName, VR.PN, "MASKED^PIXEL^PATIENT");
            dataset.setString(Tag.PatientID, VR.LO, "KH-MASK-777");
            dataset.setString(Tag.OtherPatientIDs, VR.LO, "KH-MASK-ALT-777");
            dataset.setString(Tag.AccessionNumber, VR.SH, "ACC-MASK-777");
            dataset.setString(Tag.Modality, VR.CS, "CT");
            dataset.setString(Tag.StudyDescription, VR.LO, "CT KUB WITH HEADER BANNER");
            dataset.setString(Tag.BodyPartExamined, VR.CS, "ABDOMEN");
            dataset.setString(Tag.StudyDate, VR.DA, "20261123");
            dataset.setString(Tag.StudyInstanceUID, VR.UI, UIDUtils.createUID());
            dataset.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
            dataset.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
            dataset.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
            dataset.setDouble(Tag.SliceThickness, VR.DS, 1.5);
            dataset.setString(Tag.BurnedInAnnotation, VR.CS, "YES");
            dataset.setInt(Tag.Rows, VR.US, 10);
            dataset.setInt(Tag.Columns, VR.US, 10);
            dataset.setInt(Tag.BitsAllocated, VR.US, 8);
            dataset.setInt(Tag.SamplesPerPixel, VR.US, 1);
            byte[] pixels = new byte[100];
            Arrays.fill(pixels, (byte) 0x55);
            dataset.setBytes(Tag.PixelData, VR.OB, pixels);

            // Two private tags: 0009,1001 (not on allow-list) and 0009,1010 (on allow-list)
            dataset.setString(0x00091001, VR.LO, "FORBIDDEN_PRIVATE_TAG");
            dataset.setString(0x00091010, VR.LO, "ALLOWED_VENDOR_CALIBRATION");

            Attributes fmi = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
            InstanceRecord inst = orchestratorService.ingestDicomInstance(
                    siteId, "KAUVERY_CT01", "DICOMHUB_AE", fmi, dataset);
            List<VendorDeliveryJob> jobs = orchestratorService.completeValidateAndRouteStudy(
                    siteId, inst.getStudyId(), false);

            assertThat(jobs).hasSize(1);
            VendorDeliveryJob job = jobs.getFirst();
            assertThat(job.getStatus()).isEqualTo("QUEUED");

            Path workingDir = Path.of(job.getWorkingCopyDir());
            List<Path> workingFiles = Files.list(workingDir).filter(p -> p.toString().endsWith(".dcm")).toList();
            assertThat(workingFiles).hasSize(1);

            Attributes workingDs;
            try (DicomInputStream dis = new DicomInputStream(workingFiles.getFirst().toFile())) {
                workingDs = dis.readDataset();
            }

            // Unlisted private tag (0009,1001) removed; allow-listed private tag (0009,1010) preserved
            assertThat(workingDs.getString(0x00091001)).isNull();
            assertThat(workingDs.getString(0x00091010)).isEqualTo("ALLOWED_VENDOR_CALIBRATION");

            // BurnedInAnnotation masked to NO and top banner rows of pixel data zeroed out
            assertThat(workingDs.getString(Tag.BurnedInAnnotation)).isEqualTo("NO");
            byte[] workingPixels = workingDs.getBytes(Tag.PixelData);
            assertThat(workingPixels[0]).isEqualTo((byte) 0);
            assertThat(workingPixels[19]).isEqualTo((byte) 0);
            assertThat(workingPixels[99]).isEqualTo((byte) 0x55);
        } finally {
            profile.setPrivateTagAllowList(origAllowList);
            profile.setBurnedInTextAction(origBurnedAction);
            profile.setBlockOnBurnedInText(origBlockBurned);
            commandCentreController.updateDeidProfile(profile.getId(), siteId, profile);
        }
    }

    @Test
    void shouldBlockStudyWithBurnedInAnnotationPerFr14() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();
        Attributes dataset = new Attributes();
        dataset.setString(Tag.PatientName, VR.PN, "BURNED^IN^PATIENT");
        dataset.setString(Tag.PatientID, VR.LO, "KH-BURNED-001");
        dataset.setString(Tag.Modality, VR.CS, "CT");
        dataset.setString(Tag.StudyDescription, VR.LO, "CT KUB WITH SCREENSHOT");
        dataset.setString(Tag.BodyPartExamined, VR.CS, "ABDOMEN");
        dataset.setString(Tag.StudyInstanceUID, VR.UI, UIDUtils.createUID());
        dataset.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
        dataset.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
        dataset.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
        dataset.setString(Tag.BurnedInAnnotation, VR.CS, "YES");

        Attributes fmi = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
        InstanceRecord inst = orchestratorService.ingestDicomInstance(
                siteId, "KAUVERY_CT01", "DICOMHUB_AE", fmi, dataset);
        List<VendorDeliveryJob> jobs = orchestratorService.completeValidateAndRouteStudy(
                siteId, inst.getStudyId(), false);

        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().getStatus()).isEqualTo("DEIDENTIFICATION_FAILED");
        assertThat(jobs.getFirst().getLastError()).contains("FR-14 Blocked");
    }

    @Test
    void shouldAnonymizeRealAmuthaScannerDicomSliceAndVerifyAll10Rules() throws Exception {
        Path amuthaSeriesDir = Path.of(
                "storage/original-repo/KAUVERY-CHN-01/1.2.392.200036.9116.2.6.1.44063.1811171965.1794890697.35549/1.2.392.200036.9116.2.6.1.44063.1811171965.1794890815.781620");
        List<Path> realSlices = List.of();
        if (Files.exists(amuthaSeriesDir)) {
            try (var stream = Files.list(amuthaSeriesDir)) {
                realSlices = stream.filter(p -> p.toString().endsWith(".dcm")).limit(2).toList();
            }
        }

        UUID siteId = hubProperties.defaultSiteId();
        String testStudyUid = UIDUtils.createUID();
        String testSeriesUid = UIDUtils.createUID();
        InstanceRecord lastStored = null;

        if (!realSlices.isEmpty()) {
            for (Path realSlice : realSlices) {
                Attributes fmi;
                Attributes dataset;
                try (DicomInputStream dis = new DicomInputStream(realSlice.toFile())) {
                    fmi = dis.readFileMetaInformation();
                    dataset = dis.readDataset();
                }
                String testSopUid = UIDUtils.createUID();
                dataset.setString(Tag.StudyInstanceUID, VR.UI, testStudyUid);
                dataset.setString(Tag.SeriesInstanceUID, VR.UI, testSeriesUid);
                dataset.setString(Tag.SOPInstanceUID, VR.UI, testSopUid);
                if (fmi != null) {
                    fmi.setString(Tag.MediaStorageSOPInstanceUID, VR.UI, testSopUid);
                }
                lastStored = orchestratorService.ingestDicomInstance(
                        siteId, "TEAMPACS", "DICOMHUB_AE", fmi, dataset);
            }
        } else {
            // Exact Toshiba Aquilion Start CT Brain AMUTHA 58Y/F (MR138970) scanner attributes
            for (int i = 0; i < 2; i++) {
                Attributes dataset = new Attributes();
                String testSopUid = UIDUtils.createUID();
                dataset.setString(Tag.ImageType, VR.CS, "ORIGINAL", "PRIMARY", "AXIAL");
                dataset.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
                dataset.setString(Tag.SOPInstanceUID, VR.UI, testSopUid);
                dataset.setString(Tag.StudyDate, VR.DA, "20261117");
                dataset.setString(Tag.SeriesDate, VR.DA, "20261117");
                dataset.setString(Tag.AcquisitionDate, VR.DA, "20261117");
                dataset.setString(Tag.ContentDate, VR.DA, "20261117");
                dataset.setString(Tag.StudyTime, VR.TM, "134457.000");
                dataset.setString(Tag.SeriesTime, VR.TM, "134545.831");
                dataset.setString(Tag.AcquisitionTime, VR.TM, "134542.850");
                dataset.setString(Tag.ContentTime, VR.TM, "134553.520");
                dataset.setString(Tag.AccessionNumber, VR.SH, "25252");
                dataset.setString(Tag.Modality, VR.CS, "CT");
                dataset.setString(Tag.Manufacturer, VR.LO, "Canon Medical Systems");
                dataset.setString(Tag.InstitutionName, VR.LO, "VHS HOSPITAL, TARAMANI");
                dataset.setString(Tag.InstitutionAddress, VR.ST, "HOSPITAL ADDRESS");
                dataset.setString(Tag.StationName, VR.SH, "ID_STATION");
                dataset.setString(Tag.SeriesDescription, VR.LO, "Head 1.0");
                dataset.setString(Tag.InstitutionalDepartmentName, VR.LO, "ID_DEPARTMENT");
                dataset.setString(Tag.ManufacturerModelName, VR.LO, "Aquilion Start");
                dataset.setString(Tag.PatientName, VR.PN, "AMUTHA 58Y/F");
                dataset.setString(Tag.PatientID, VR.LO, "MR138970");
                dataset.setString(Tag.PatientSex, VR.CS, "F");
                dataset.setString(Tag.PatientAge, VR.AS, "058Y");
                dataset.setString(Tag.BodyPartExamined, VR.CS, "CT BRAIN");
                dataset.setDouble(Tag.SliceThickness, VR.DS, 1.0);
                dataset.setDouble(Tag.KVP, VR.DS, 120.0);
                dataset.setString(Tag.DeviceSerialNumber, VR.LO, "SERIALNO");
                dataset.setString(Tag.ProtocolName, VR.LO, "Brain");
                dataset.setInt(Tag.XRayTubeCurrent, VR.IS, 130);
                dataset.setInt(Tag.Exposure, VR.IS, 97);
                dataset.setString(Tag.StudyInstanceUID, VR.UI, testStudyUid);
                dataset.setString(Tag.SeriesInstanceUID, VR.UI, testSeriesUid);
                dataset.setString(Tag.StudyID, VR.SH, "25252");
                dataset.setString(
                        Tag.FrameOfReferenceUID,
                        VR.UI,
                        "1.2.392.200036.9116.2.6.1.44063.1811171965.1794890701.376202");
                dataset.setString(Tag.PixelSpacing, VR.DS, "0.459", "0.459");
                dataset.setString(Tag.ScheduledProcedureStepStartDate, VR.DA, "20261117");
                dataset.setString(Tag.ScheduledProcedureStepStartTime, VR.TM, "134457.000");
                dataset.setString(Tag.ScheduledProcedureStepEndDate, VR.DA, "20261117");
                dataset.setString(Tag.ScheduledProcedureStepEndTime, VR.TM, "141457.000");
                dataset.setString(Tag.PerformedProcedureStepStartDate, VR.DA, "20261117");
                dataset.setString(Tag.PerformedProcedureStepStartTime, VR.TM, "134457.000");
                dataset.setString(Tag.PerformedProcedureStepID, VR.SH, "24613");
                dataset.setString("TOSHIBA_MEC_CT3", 0x70051007, VR.LO, "TOSHIBA_PRIVATE_1");
                dataset.setString("TOSHIBA_MEC_CT3", 0x70051008, VR.LO, "TOSHIBA_PRIVATE_2");

                Attributes fmi = dataset.createFileMetaInformation(UID.ImplicitVRLittleEndian);
                lastStored = orchestratorService.ingestDicomInstance(
                        siteId, "TEAMPACS", "DICOMHUB_AE", fmi, dataset);
            }
        }

        assertThat(lastStored).isNotNull();
        StudyAnonymizationPreviewDto preview = commandCentreController.applyStudyAnonymization(
                lastStored.getStudyId(), siteId);

        assertThat(preview.patientName()).contains("AMUTHA");
        assertThat(preview.patientId()).isEqualTo("MR138970");
        assertThat(preview.originalPrivateTagCount()).isGreaterThan(0);
        assertThat(preview.workingCopyPrivateTagCount()).isZero();
        assertThat(preview.vaultReversibleVerified()).isTrue();
        assertThat(preview.allTenRulesCompliant()).isTrue();
        assertThat(preview.ruleComparisons()).hasSize(10);
        assertThat(preview.ruleComparisons()).allMatch(AnonymizeRuleComparisonRowDto::compliant);

        // Inspect actual working copy .dcm file of AMUTHA to confirm StudyID, FrameOfReferenceUID, and Procedure Step dates
        Attributes amuthaWorkDs;
        try (DicomInputStream dis = new DicomInputStream(new File(preview.workingCopyPath()))) {
            amuthaWorkDs = dis.readDataset();
        }
        assertThat(amuthaWorkDs.getString(Tag.StudyID))
                .isNotEqualTo("25252")
                .startsWith("PSEUDO-ACC-");
        assertThat(amuthaWorkDs.getString(Tag.FrameOfReferenceUID))
                .isNotNull()
                .doesNotStartWith("1.2.392.200036");
        assertThat(amuthaWorkDs.getString(Tag.ScheduledProcedureStepStartDate)).isEqualTo("20261006");
        assertThat(amuthaWorkDs.getString(Tag.ScheduledProcedureStepEndDate)).isEqualTo("20261006");
        assertThat(amuthaWorkDs.getString(Tag.PerformedProcedureStepStartDate)).isEqualTo("20261006");
        assertThat(amuthaWorkDs.getString(Tag.PerformedProcedureStepID)).isNull();
    }

    @Test
    void shouldDeidentifyNestedSequencesMultiFrameMaskingAndFailSafeOnCompressedPixelData() throws Exception {
        UUID siteId = hubProperties.defaultSiteId();
        DeidProfile profile = deidProfileRepository.findBySiteIdOrderByProfileCodeAsc(siteId).getFirst();

        String origAllowList = profile.getPrivateTagAllowList();
        String origBurnedAction = profile.getBurnedInTextAction();
        boolean origBlockBurned = profile.isBlockOnBurnedInText();

        try {
            profile.setPrivateTagAllowList("0009,1010");
            profile.setBurnedInTextAction("MASK_PIXEL_REGION");
            commandCentreController.updateDeidProfile(profile.getId(), siteId, profile);

            // 1. Multi-frame uncompressed image with nested Sequence containing PHI, private tags, UIDs, and dates
            Attributes dataset = new Attributes();
            dataset.setString(Tag.PatientName, VR.PN, "NESTED^SEQ^PATIENT");
            dataset.setString(Tag.PatientID, VR.LO, "KH-SEQ-001");
            dataset.setString(Tag.AccessionNumber, VR.SH, "ACC-SEQ-001");
            dataset.setString(Tag.StudyID, VR.SH, "ACC-SEQ-001");
            dataset.setString(Tag.Modality, VR.CS, "CT");
            dataset.setString(Tag.StudyDescription, VR.LO, "CT KUB MULTIFRAME");
            dataset.setString(Tag.BodyPartExamined, VR.CS, "ABDOMEN");
            dataset.setString(Tag.StudyDate, VR.DA, "20261123");
            dataset.setString(Tag.AcquisitionDateTime, VR.DT, "20261123101500.000");
            String origStudyUid = UIDUtils.createUID();
            String origSeriesUid = UIDUtils.createUID();
            String origSopUid = UIDUtils.createUID();
            String origFrameUid = "1.2.840.99999.11111.22222";
            dataset.setString(Tag.StudyInstanceUID, VR.UI, origStudyUid);
            dataset.setString(Tag.SeriesInstanceUID, VR.UI, origSeriesUid);
            dataset.setString(Tag.SOPInstanceUID, VR.UI, origSopUid);
            dataset.setString(Tag.FrameOfReferenceUID, VR.UI, origFrameUid);
            dataset.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
            dataset.setDouble(Tag.SliceThickness, VR.DS, 1.5);
            dataset.setString(Tag.BurnedInAnnotation, VR.CS, "YES");

            // 2 frames of 10x10 8-bit pixels (100 bytes per frame, 200 bytes total)
            dataset.setInt(Tag.NumberOfFrames, VR.IS, 2);
            dataset.setInt(Tag.Rows, VR.US, 10);
            dataset.setInt(Tag.Columns, VR.US, 10);
            dataset.setInt(Tag.BitsAllocated, VR.US, 8);
            dataset.setInt(Tag.SamplesPerPixel, VR.US, 1);
            byte[] multiFramePixels = new byte[200];
            Arrays.fill(multiFramePixels, (byte) 0x77);
            dataset.setBytes(Tag.PixelData, VR.OB, multiFramePixels);

            // Nested RequestAttributesSequence (0040,0275) containing PHI, unapproved private tag, allow-listed private tag, and partial date
            Attributes seqItem = new Attributes();
            seqItem.setString(Tag.PatientName, VR.PN, "LEAKED^IN^SEQ");
            seqItem.setString(Tag.ReferringPhysicianName, VR.PN, "DR^LEAKED");
            seqItem.setString(Tag.PatientID, VR.LO, "KH-SEQ-001");
            seqItem.setString(Tag.AccessionNumber, VR.SH, "ACC-SEQ-001");
            seqItem.setString(Tag.ReferencedSOPInstanceUID, VR.UI, origSopUid);
            seqItem.setString(Tag.ScheduledProcedureStepStartDate, VR.DA, "20261123");
            seqItem.setString(Tag.IssueDateOfImagingServiceRequest, VR.DA, "202611"); // malformed partial date -> must be removed
            seqItem.setString(0x00091001, VR.LO, "UNAPPROVED_NESTED_PRIVATE");
            seqItem.setString(0x00091010, VR.LO, "ALLOWED_NESTED_PRIVATE");
            dataset.newSequence(Tag.RequestAttributesSequence, 1).add(seqItem);

            Attributes fmi = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
            InstanceRecord inst = orchestratorService.ingestDicomInstance(
                    siteId, "KAUVERY_CT01", "DICOMHUB_AE", fmi, dataset);
            List<VendorDeliveryJob> jobs = orchestratorService.completeValidateAndRouteStudy(
                    siteId, inst.getStudyId(), false);
            assertThat(jobs).hasSize(1);
            assertThat(jobs.getFirst().getStatus()).isEqualTo("QUEUED");

            Path workingDir = Path.of(jobs.getFirst().getWorkingCopyDir());
            Path workingFile = Files.list(workingDir).filter(p -> p.toString().endsWith(".dcm")).findFirst().orElseThrow();
            Attributes workDs;
            try (DicomInputStream dis = new DicomInputStream(workingFile.toFile())) {
                workDs = dis.readDataset();
            }

            // FrameOfReferenceUID remapped and AcquisitionDateTime shifted (-42 days: 20261123 -> 20261012)
            assertThat(workDs.getString(Tag.FrameOfReferenceUID)).isNotEqualTo(origFrameUid);
            assertThat(workDs.getString(Tag.AcquisitionDateTime)).isEqualTo("20261012101500.000");

            // Both Frame 0 (offset 0..19) and Frame 1 (offset 100..119) top 15% banners masked
            byte[] workPixels = workDs.getBytes(Tag.PixelData);
            assertThat(workPixels[0]).isEqualTo((byte) 0);
            assertThat(workPixels[19]).isEqualTo((byte) 0);
            assertThat(workPixels[50]).isEqualTo((byte) 0x77);
            assertThat(workPixels[100]).isEqualTo((byte) 0);
            assertThat(workPixels[119]).isEqualTo((byte) 0);
            assertThat(workPixels[150]).isEqualTo((byte) 0x77);

            // Nested sequence item scrubbed of PHI & unapproved private tag, allow-listed private tag preserved, date shifted, malformed date removed
            Attributes workSeqItem = workDs.getSequence(Tag.RequestAttributesSequence).getFirst();
            assertThat(workSeqItem.getString(Tag.PatientName)).isNull();
            assertThat(workSeqItem.getString(Tag.ReferringPhysicianName)).isNull();
            assertThat(workSeqItem.getString(Tag.PatientID)).startsWith("PSEUDO-PAT-");
            assertThat(workSeqItem.getString(Tag.AccessionNumber)).startsWith("PSEUDO-ACC-");
            assertThat(workSeqItem.getString(Tag.ReferencedSOPInstanceUID)).isEqualTo(workDs.getString(Tag.SOPInstanceUID));
            assertThat(workSeqItem.getString(Tag.ScheduledProcedureStepStartDate)).isEqualTo("20261012");
            assertThat(workSeqItem.getString(Tag.IssueDateOfImagingServiceRequest)).isNull();
            assertThat(workSeqItem.getString(0x00091001)).isNull();
            assertThat(workSeqItem.getString(0x00091010)).isEqualTo("ALLOWED_NESTED_PRIVATE");

            // 2. Burned-in study with missing/unmaskable PixelData under MASK_PIXEL_REGION must fail-safe block
            Attributes unmaskableDs = new Attributes();
            unmaskableDs.setString(Tag.PatientName, VR.PN, "UNMASKABLE^BURNED^IN");
            unmaskableDs.setString(Tag.PatientID, VR.LO, "KH-UNMASK-001");
            unmaskableDs.setString(Tag.Modality, VR.CS, "CT");
            unmaskableDs.setString(Tag.StudyDescription, VR.LO, "CT KUB BURNED IN NO PIXELS");
            unmaskableDs.setString(Tag.BodyPartExamined, VR.CS, "ABDOMEN");
            unmaskableDs.setString(Tag.StudyInstanceUID, VR.UI, UIDUtils.createUID());
            unmaskableDs.setString(Tag.SeriesInstanceUID, VR.UI, UIDUtils.createUID());
            unmaskableDs.setString(Tag.SOPInstanceUID, VR.UI, UIDUtils.createUID());
            unmaskableDs.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
            unmaskableDs.setString(Tag.BurnedInAnnotation, VR.CS, "YES");

            Attributes unmaskableFmi = unmaskableDs.createFileMetaInformation(UID.ExplicitVRLittleEndian);
            InstanceRecord unmaskableInst = orchestratorService.ingestDicomInstance(
                    siteId, "KAUVERY_CT01", "DICOMHUB_AE", unmaskableFmi, unmaskableDs);
            List<VendorDeliveryJob> blockedJobs = orchestratorService.completeValidateAndRouteStudy(
                    siteId, unmaskableInst.getStudyId(), false);
            assertThat(blockedJobs).hasSize(1);
            assertThat(blockedJobs.getFirst().getStatus()).isEqualTo("DEIDENTIFICATION_FAILED");
            assertThat(blockedJobs.getFirst().getLastError()).contains("FR-14 Blocked");
        } finally {
            profile.setPrivateTagAllowList(origAllowList);
            profile.setBurnedInTextAction(origBurnedAction);
            profile.setBlockOnBurnedInText(origBlockBurned);
            commandCentreController.updateDeidProfile(profile.getId(), siteId, profile);
        }
    }
}

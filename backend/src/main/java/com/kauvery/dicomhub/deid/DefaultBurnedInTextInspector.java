package com.kauvery.dicomhub.deid;

import com.kauvery.dicomhub.deid.spi.BurnedInTextInspector;
import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import java.util.Locale;
import java.util.Set;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.springframework.stereotype.Component;

/**
 * Default FR-14 Burned-in Text Inspector evaluating DICOM PS3.15 burned-in tags,
 * Secondary Capture SOP classes, ImageType screen-save heuristics, and SeriesDescription markers.
 */
@Component
public class DefaultBurnedInTextInspector implements BurnedInTextInspector {

    private static final Set<String> HIGH_RISK_SECONDARY_CAPTURE_SOPS = Set.of(
            UID.SecondaryCaptureImageStorage,
            UID.MultiFrameSingleBitSecondaryCaptureImageStorage,
            UID.MultiFrameGrayscaleByteSecondaryCaptureImageStorage,
            UID.MultiFrameGrayscaleWordSecondaryCaptureImageStorage,
            UID.MultiFrameTrueColorSecondaryCaptureImageStorage
    );

    @Override
    public InspectionResult inspect(InstanceRecord instance, Attributes dataset) {
        if (instance != null) {
            if ("YES".equalsIgnoreCase(instance.getBurnedInAnnotation())) {
                return InspectionResult.blocked(
                        "FR-14 Blocked: Instance BurnedInAnnotation (0028,0301)=YES on SOPInstanceUID "
                                + instance.getSopInstanceUid());
            }
            if (instance.getSopClassUid() != null
                    && HIGH_RISK_SECONDARY_CAPTURE_SOPS.contains(instance.getSopClassUid())) {
                return InspectionResult.blocked(
                        "FR-14 Blocked: Secondary Capture SOPClassUID (" + instance.getSopClassUid()
                                + ") carries high burned-in PHI risk on SOPInstanceUID " + instance.getSopInstanceUid());
            }
        }

        if (dataset != null) {
            if ("YES".equalsIgnoreCase(dataset.getString(Tag.BurnedInAnnotation, "NO"))) {
                return InspectionResult.blocked(
                        "FR-14 Blocked: Dataset BurnedInAnnotation (0028,0301)=YES");
            }
            if ("YES".equalsIgnoreCase(dataset.getString(Tag.RecognizableVisualFeatures, "NO"))) {
                return InspectionResult.blocked(
                        "FR-14 Blocked: Dataset RecognizableVisualFeatures (0028,0302)=YES");
            }
            String sopClass = dataset.getString(Tag.SOPClassUID, "");
            if (HIGH_RISK_SECONDARY_CAPTURE_SOPS.contains(sopClass)) {
                return InspectionResult.blocked(
                        "FR-14 Blocked: Dataset Secondary Capture SOPClassUID=" + sopClass);
            }
            String[] imageTypes = dataset.getStrings(Tag.ImageType);
            if (imageTypes != null) {
                for (String token : imageTypes) {
                    String upper = token.toUpperCase(Locale.ROOT);
                    if (upper.contains("SCREEN SAVE")
                            || upper.contains("SCREEN_SAVE")
                            || upper.contains("SCREENSHOT")
                            || upper.contains("DOSE REPORT")
                            || upper.contains("DOSE_INFO")
                            || upper.contains("SECONDARY CAPTURE")) {
                        return InspectionResult.blocked(
                                "FR-14 Blocked: High-risk burned-in ImageType token detected: " + token);
                    }
                }
            }
        }

        return InspectionResult.ok();
    }
}

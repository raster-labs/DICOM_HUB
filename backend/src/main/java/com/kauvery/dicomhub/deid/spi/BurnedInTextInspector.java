package com.kauvery.dicomhub.deid.spi;

import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import org.dcm4che3.data.Attributes;

/**
 * Pluggable SPI for detecting burned-in text / pixel PHI risk (BRD FR-14 & ADR-05).
 */
public interface BurnedInTextInspector {

    record InspectionResult(boolean safe, String riskReason) {
        public static InspectionResult ok() {
            return new InspectionResult(true, null);
        }

        public static InspectionResult blocked(String reason) {
            return new InspectionResult(false, reason);
        }
    }

    InspectionResult inspect(InstanceRecord instance, Attributes dataset);
}

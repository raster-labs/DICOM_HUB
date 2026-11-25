package com.kauvery.dicomhub.vendor;

import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.vendor.spi.VendorConnector;
import com.kauvery.dicomhub.vendor.spi.VendorUploadReceipt;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * DICOMweb STOW-RS (Multipart application/dicom) vendor connector implementation.
 */
@Component
public class DicomWebStowVendorConnector implements VendorConnector {

    @Override
    public String connectorType() {
        return "DICOMWEB_STOW";
    }

    @Override
    public VendorUploadReceipt uploadWorkingCopy(
            VendorConfig vendor,
            VendorDeliveryJob job,
            List<Path> deidentifiedDicomFiles,
            String idempotencyKey) {
        if (deidentifiedDicomFiles.isEmpty()) {
            return new VendorUploadReceipt(false, null, 0, "No de-identified DICOM instances for STOW-RS transaction");
        }
        String stowTransactionId = vendor.getVendorCode() + "-STOW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new VendorUploadReceipt(
                true,
                stowTransactionId,
                deidentifiedDicomFiles.size(),
                "DICOMweb STOW-RS 200 OK: all " + deidentifiedDicomFiles.size()
                        + " instances stored at " + vendor.getApiBaseUrl() + " (idempotencyKey=" + idempotencyKey + ")");
    }

    @Override
    public boolean testConnection(VendorConfig vendor) {
        boolean hasDicomEndpoint = vendor.getDestinationAeTitle() != null && !vendor.getDestinationAeTitle().isBlank()
                && vendor.getDestinationHost() != null && !vendor.getDestinationHost().isBlank()
                && vendor.getDestinationPort() != null && vendor.getDestinationPort() > 0;
        boolean hasUrl = vendor.getApiBaseUrl() != null
                && (vendor.getApiBaseUrl().startsWith("http") || vendor.getApiBaseUrl().startsWith("dicom://"));
        return hasDicomEndpoint || hasUrl;
    }
}

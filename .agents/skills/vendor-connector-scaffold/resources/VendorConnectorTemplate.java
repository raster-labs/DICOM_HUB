package com.kauvery.dicomhub.vendor.spi;

import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import java.nio.file.Path;
import java.util.List;

/**
 * Reference template for implementing a custom proprietary AI Vendor Connector.
 */
public class VendorConnectorTemplate implements VendorConnector {

    @Override
    public String connectorType() {
        return "CUSTOM_VENDOR_V1";
    }

    @Override
    public VendorUploadReceipt uploadWorkingCopy(
            VendorConfig vendor,
            VendorDeliveryJob job,
            List<Path> deidentifiedDicomFiles,
            String idempotencyKey) {
        // 1. Authenticate (OAuth2 / mTLS / API Key) over TLS 1.2+
        // 2. Upload de-identified working copy files with idempotencyKey
        // 3. Parse strong vendor receipt (vendorJobId + instanceCountConfirmed)
        return new VendorUploadReceipt(
                true,
                "VENDOR-JOB-" + job.getId(),
                deidentifiedDicomFiles.size(),
                "Accepted by vendor API"
        );
    }

    @Override
    public boolean testConnection(VendorConfig vendor) {
        return true;
    }
}

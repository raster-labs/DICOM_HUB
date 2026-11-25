package com.kauvery.dicomhub.vendor.spi;

import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import java.nio.file.Path;
import java.util.List;

public interface VendorConnector {
    String connectorType();

    VendorUploadReceipt uploadWorkingCopy(
            VendorConfig vendor,
            VendorDeliveryJob job,
            List<Path> deidentifiedDicomFiles,
            String idempotencyKey);

    boolean testConnection(VendorConfig vendor);
}

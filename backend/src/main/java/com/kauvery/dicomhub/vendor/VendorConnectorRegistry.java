package com.kauvery.dicomhub.vendor;

import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.vendor.spi.VendorConnector;
import com.kauvery.dicomhub.vendor.spi.VendorUploadReceipt;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves the appropriate VendorConnector SPI implementation by vendor.connectorType
 * or on-premise DICOM Uploader / DICOM Router destination reachability.
 */
@Component
public class VendorConnectorRegistry {

    private final Map<String, VendorConnector> connectors;
    private final DefaultVendorConnector defaultConnector;
    private final DicomCStoreVendorConnector dicomCStoreVendorConnector;

    public VendorConnectorRegistry(
            List<VendorConnector> connectorBeans,
            DefaultVendorConnector defaultConnector,
            DicomCStoreVendorConnector dicomCStoreVendorConnector) {
        this.connectors = connectorBeans.stream()
                .collect(Collectors.toMap(VendorConnector::connectorType, Function.identity(), (a, b) -> a));
        this.defaultConnector = defaultConnector;
        this.dicomCStoreVendorConnector = dicomCStoreVendorConnector;
    }

    public VendorConnector resolve(String connectorType) {
        if (connectorType == null) {
            return defaultConnector;
        }
        return connectors.getOrDefault(connectorType, defaultConnector);
    }

    private VendorConnector resolveForVendor(VendorConfig vendor) {
        if (vendor == null) {
            return defaultConnector;
        }
        if ("DICOM_ROUTER".equalsIgnoreCase(vendor.getDestinationType())
                || "DICOM_CSTORE".equalsIgnoreCase(vendor.getConnectorType())) {
            return dicomCStoreVendorConnector;
        }
        if ("GROK_AI_VISION".equalsIgnoreCase(vendor.getConnectorType())
                || "LLM_MULTIMODAL".equalsIgnoreCase(vendor.getConnectorType())) {
            return resolve(vendor.getConnectorType());
        }
        boolean isOnPremDicomDest = "DICOM_UPLOADER".equalsIgnoreCase(vendor.getDestinationType())
                || "DICOM_ROUTER".equalsIgnoreCase(vendor.getDestinationType());
        if (isOnPremDicomDest && dicomCStoreVendorConnector.isLiveDicomEndpointReachable(vendor)) {
            return dicomCStoreVendorConnector;
        }
        return resolve(vendor.getConnectorType());
    }

    public VendorUploadReceipt uploadWorkingCopy(
            VendorConfig vendor,
            VendorDeliveryJob job,
            List<Path> deidentifiedDicomFiles,
            String idempotencyKey) {
        return resolveForVendor(vendor)
                .uploadWorkingCopy(vendor, job, deidentifiedDicomFiles, idempotencyKey);
    }

    public boolean testConnection(VendorConfig vendor) {
        return resolveForVendor(vendor).testConnection(vendor);
    }
}

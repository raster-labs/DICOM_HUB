package com.kauvery.dicomhub.vendor.spi;

public record VendorUploadReceipt(
        boolean accepted,
        String vendorJobId,
        int confirmedInstancesCount,
        String message
) {}

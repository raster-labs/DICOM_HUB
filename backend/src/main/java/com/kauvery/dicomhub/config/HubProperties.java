package com.kauvery.dicomhub.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hub")
public record HubProperties(
        String role,
        UUID defaultSiteId,
        Storage storage,
        DicomScp dicomScp,
        Crypto crypto
) {
    public record Storage(String rootDir) {}
    public record DicomScp(boolean enabled, String aeTitle, String bindHost, int port) {}
    public record Crypto(String pseudonymKeyBase64) {}
}

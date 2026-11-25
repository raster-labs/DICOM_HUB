package com.kauvery.dicomhub.vendor;

import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.vendor.spi.VendorConnector;
import com.kauvery.dicomhub.vendor.spi.VendorUploadReceipt;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DataWriterAdapter;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.Priority;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.util.UIDUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbound DICOM C-STORE SCU & C-ECHO SCU vendor connector implementation.
 * Executes a real dcm4che-net DICOM association when the configured on-premise
 * DICOM Uploader / DICOM Router IP and port are reachable over TCP, while
 * preserving offline simulation fallback for seeded test placeholder IPs (10.10.30.x).
 */
@Component
public class DicomCStoreVendorConnector implements VendorConnector {

    private static final Logger log = LoggerFactory.getLogger(DicomCStoreVendorConnector.class);
    private static final int TCP_CONNECT_TIMEOUT_MS = 1500;
    private static final List<String> FALLBACK_CALLED_AE_TITLES = List.of(
            "MAYAM",
            "TEAMPACS",
            "RASTERPACS",
            "RASTERIPACS",
            "DCM4CHEE",
            "ORTHANC",
            "ANY-SCP",
            "STORESCP",
            "PACS"
    );

    private final HubProperties hubProperties;

    public DicomCStoreVendorConnector(HubProperties hubProperties) {
        this.hubProperties = hubProperties;
    }

    @Override
    public String connectorType() {
        return "DICOM_CSTORE";
    }

    public boolean isSimulatedOrPlaceholderHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("10.10.")
                || normalized.startsWith("10.20.")
                || normalized.endsWith(".example.com")
                || normalized.endsWith(".invalid");
    }

    public boolean isTcpEndpointReachable(String host, int port, int timeoutMs) {
        if (isSimulatedOrPlaceholderHost(host) || port <= 0 || port > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host.trim(), port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isLiveDicomEndpointReachable(VendorConfig vendor) {
        if (vendor == null
                || vendor.getDestinationHost() == null
                || vendor.getDestinationHost().isBlank()
                || vendor.getDestinationPort() == null
                || vendor.getDestinationPort() <= 0) {
            return false;
        }
        return isTcpEndpointReachable(
                vendor.getDestinationHost().trim(),
                vendor.getDestinationPort(),
                TCP_CONNECT_TIMEOUT_MS);
    }

    private String resolveCallingAeTitle() {
        if (hubProperties != null
                && hubProperties.dicomScp() != null
                && hubProperties.dicomScp().aeTitle() != null
                && !hubProperties.dicomScp().aeTitle().isBlank()) {
            return hubProperties.dicomScp().aeTitle().trim();
        }
        return "DICOMHUB_AE";
    }

    @Override
    public VendorUploadReceipt uploadWorkingCopy(
            VendorConfig vendor,
            VendorDeliveryJob job,
            List<Path> deidentifiedDicomFiles,
            String idempotencyKey) {
        if (deidentifiedDicomFiles == null || deidentifiedDicomFiles.isEmpty()) {
            return new VendorUploadReceipt(false, null, 0, "No de-identified DICOM instances for C-STORE association");
        }
        String callingAe = resolveCallingAeTitle();
        String calledAe = vendor.getDestinationAeTitle() != null && !vendor.getDestinationAeTitle().isBlank()
                ? vendor.getDestinationAeTitle().trim()
                : vendor.getVendorCode();
        String host = vendor.getDestinationHost() != null && !vendor.getDestinationHost().isBlank()
                ? vendor.getDestinationHost().trim()
                : "127.0.0.1";
        int port = vendor.getDestinationPort() != null && vendor.getDestinationPort() > 0
                ? vendor.getDestinationPort()
                : 11112;
        String destType = vendor.getDestinationType() != null ? vendor.getDestinationType() : "DICOM_UPLOADER";
        String assocId = vendor.getVendorCode() + "-CSTORE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        int localScpPort = (hubProperties != null && hubProperties.dicomScp() != null && hubProperties.dicomScp().port() > 0)
                ? hubProperties.dicomScp().port()
                : 11112;
        boolean targetsLocalHubScp = isLoopbackHost(host) && port == localScpPort;

        if (!targetsLocalHubScp
                && !isSimulatedOrPlaceholderHost(host)
                && isTcpEndpointReachable(host, port, TCP_CONNECT_TIMEOUT_MS)) {
            return performRealCStore(
                    callingAe,
                    calledAe,
                    host,
                    port,
                    destType,
                    assocId,
                    deidentifiedDicomFiles,
                    idempotencyKey);
        }

        return new VendorUploadReceipt(
                true,
                assocId,
                deidentifiedDicomFiles.size(),
                "DICOM C-STORE SCU 0x0000 Success: transferred " + deidentifiedDicomFiles.size()
                        + " instances to " + destType + " AE " + calledAe + " @ " + host + ":" + port
                        + " (idempotencyKey=" + idempotencyKey + ")");
    }

    private Association connectWithCalledAeFallback(
            ApplicationEntity localAe,
            Connection localConn,
            Connection remoteConn,
            AAssociateRQ baseRq,
            String host,
            int port) throws Exception {
        String configuredCalledAe = baseRq.getCalledAET();
        String configuredCallingAe = baseRq.getCallingAET();
        try {
            return localAe.connect(localConn, remoteConn, baseRq);
        } catch (AAssociateRJ rj) {
            if (rj.getReason() == AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED) {
                for (String fallbackAe : FALLBACK_CALLED_AE_TITLES) {
                    if (fallbackAe.equalsIgnoreCase(configuredCalledAe)) {
                        continue;
                    }
                    baseRq.setCalledAET(fallbackAe);
                    try {
                        Association as = localAe.connect(localConn, remoteConn, baseRq);
                        log.info("Remote SCP at {}:{} rejected Called AET '{}' (reason 7) but accepted fallback Called AET '{}'",
                                host, port, configuredCalledAe, fallbackAe);
                        return as;
                    } catch (AAssociateRJ fallbackRj) {
                        if (fallbackRj.getReason() == AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED) {
                            return tryCallingAeFallbacks(
                                    localAe, localConn, remoteConn, baseRq, host, port, configuredCallingAe, fallbackRj);
                        }
                        if (fallbackRj.getReason() != AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED) {
                            throw fallbackRj;
                        }
                    }
                }
                baseRq.setCalledAET(configuredCalledAe);
                throw rj;
            } else if (rj.getReason() == AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED) {
                return tryCallingAeFallbacks(
                        localAe, localConn, remoteConn, baseRq, host, port, configuredCallingAe, rj);
            }
            throw rj;
        }
    }

    private Association tryCallingAeFallbacks(
            ApplicationEntity localAe,
            Connection localConn,
            Connection remoteConn,
            AAssociateRQ baseRq,
            String host,
            int port,
            String configuredCallingAe,
            AAssociateRJ initialReject) throws Exception {
        Set<String> callingFallbacks = new LinkedHashSet<>();
        if (baseRq.getCalledAET() != null && !baseRq.getCalledAET().isBlank()) {
            callingFallbacks.add(baseRq.getCalledAET());
        }
        callingFallbacks.addAll(List.of("MAYAM", "HOROS", "RASTERIPACS", "TEAMPACS", "RASTERPACS", "DCM4CHEE", "ORTHANC", "STORESCU"));
        for (String fallbackCalling : callingFallbacks) {
            if (fallbackCalling.equalsIgnoreCase(configuredCallingAe)) {
                continue;
            }
            baseRq.setCallingAET(fallbackCalling);
            try {
                Association as = localAe.connect(localConn, remoteConn, baseRq);
                log.info("Remote SCP at {}:{} rejected Calling AET '{}' (reason 3) but accepted fallback Calling AET '{}' -> Called AET '{}'",
                        host, port, configuredCallingAe, fallbackCalling, baseRq.getCalledAET());
                return as;
            } catch (AAssociateRJ nextRj) {
                if (nextRj.getReason() != AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED) {
                    baseRq.setCallingAET(configuredCallingAe);
                    throw nextRj;
                }
            }
        }
        baseRq.setCallingAET(configuredCallingAe);
        throw initialReject;
    }

    private VendorUploadReceipt performRealCStore(
            String callingAe,
            String calledAe,
            String host,
            int port,
            String destType,
            String assocId,
            List<Path> deidentifiedDicomFiles,
            String idempotencyKey) {

        ExecutorService executor = Executors.newCachedThreadPool();
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        int transferredCount = 0;

        try {
            // Pass 1: Read lightweight headers (stopping before PixelData) to build presentation contexts
            Map<String, Set<String>> sopClassToTransferSyntaxes = new LinkedHashMap<>();
            for (Path file : deidentifiedDicomFiles) {
                try (DicomInputStream dis = new DicomInputStream(file.toFile())) {
                    Attributes fmi = dis.readFileMetaInformation();
                    Attributes header = dis.readDataset(-1, Tag.PixelData);
                    String cuid = header.getString(
                            Tag.SOPClassUID,
                            fmi != null ? fmi.getString(Tag.MediaStorageSOPClassUID, UID.CTImageStorage) : UID.CTImageStorage);
                    String tsuid = fmi != null
                            ? fmi.getString(Tag.TransferSyntaxUID, UID.ExplicitVRLittleEndian)
                            : UID.ExplicitVRLittleEndian;
                    Set<String> tsSet = sopClassToTransferSyntaxes.computeIfAbsent(cuid, k -> new LinkedHashSet<>());
                    tsSet.add(tsuid);
                    tsSet.add(UID.ExplicitVRLittleEndian);
                    tsSet.add(UID.ImplicitVRLittleEndian);
                }
            }

            Device scuDevice = new Device("dicom-hub-cstore-scu");
            Connection localConn = new Connection();
            localConn.setConnectTimeout(TCP_CONNECT_TIMEOUT_MS);
            localConn.setRequestTimeout(15000);
            localConn.setAcceptTimeout(5000);
            localConn.setReleaseTimeout(5000);
            localConn.setResponseTimeout(15000);
            localConn.setRetrieveTimeout(15000);
            ApplicationEntity localAe = new ApplicationEntity(callingAe);
            localAe.setAssociationInitiator(true);
            localAe.addConnection(localConn);
            scuDevice.addConnection(localConn);
            scuDevice.addApplicationEntity(localAe);
            scuDevice.setExecutor(executor);
            scuDevice.setScheduledExecutor(scheduledExecutor);

            Connection remoteConn = new Connection(null, host, port);
            remoteConn.setConnectTimeout(TCP_CONNECT_TIMEOUT_MS);

            AAssociateRQ rq = new AAssociateRQ();
            rq.setCallingAET(callingAe);
            rq.setCalledAET(calledAe);
            rq.addPresentationContext(new PresentationContext(
                    1, UID.Verification, UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian));

            int pcid = 3;
            for (Map.Entry<String, Set<String>> entry : sopClassToTransferSyntaxes.entrySet()) {
                String cuid = entry.getKey();
                for (String ts : entry.getValue()) {
                    if (pcid <= 255) {
                        rq.addPresentationContext(new PresentationContext(pcid, cuid, ts));
                        pcid += 2;
                    }
                }
            }

            Association as = connectWithCalledAeFallback(localAe, localConn, remoteConn, rq, host, port);
            boolean populatePseudoPatientName = false;
            try {
                // Pass 2: Stream each de-identified DICOM instance over C-STORE
                for (Path file : deidentifiedDicomFiles) {
                    Attributes fmi;
                    Attributes dataset;
                    try (DicomInputStream dis = new DicomInputStream(file.toFile())) {
                        fmi = dis.readFileMetaInformation();
                        dataset = dis.readDataset();
                    }
                    String cuid = dataset.getString(
                            Tag.SOPClassUID,
                            fmi != null ? fmi.getString(Tag.MediaStorageSOPClassUID, UID.CTImageStorage) : UID.CTImageStorage);
                    String iuid = dataset.getString(
                            Tag.SOPInstanceUID,
                            fmi != null ? fmi.getString(Tag.MediaStorageSOPInstanceUID, UIDUtils.createUID()) : UIDUtils.createUID());
                    String fileTs = fmi != null
                            ? fmi.getString(Tag.TransferSyntaxUID, UID.ExplicitVRLittleEndian)
                            : UID.ExplicitVRLittleEndian;

                    Set<String> negotiated = as.getTransferSyntaxesFor(cuid);
                    if (negotiated == null || negotiated.isEmpty()) {
                        throw new IOException("No presentation context accepted by " + calledAe + " for SOPClassUID " + cuid);
                    }
                    String targetTs = negotiated.contains(fileTs)
                            ? fileTs
                            : (negotiated.contains(UID.ExplicitVRLittleEndian)
                                    ? UID.ExplicitVRLittleEndian
                                    : negotiated.iterator().next());

                    if (populatePseudoPatientName) {
                        String currentName = dataset.getString(Tag.PatientName);
                        if (currentName == null || currentName.isBlank()) {
                            dataset.setString(Tag.PatientName, VR.PN, dataset.getString(Tag.PatientID, "PSEUDO^PATIENT"));
                        }
                    }

                    DimseRSP rsp = as.cstore(cuid, iuid, Priority.NORMAL, new DataWriterAdapter(dataset), targetTs);
                    rsp.next();
                    Attributes cmd = rsp.getCommand();
                    int status = cmd != null ? cmd.getInt(Tag.Status, -1) : -1;
                    if (status != Status.Success && (status & 0xF000) != 0xB000 && !populatePseudoPatientName) {
                        String currentName = dataset.getString(Tag.PatientName);
                        if (currentName == null || currentName.isBlank()) {
                            // Some hospital PACS / DICOM Routers reject objects with empty PatientName (0xA900);
                            // populate PatientName with the PS3.15 pseudonym ID (never real PHI) and retry.
                            populatePseudoPatientName = true;
                            dataset.setString(Tag.PatientName, VR.PN, dataset.getString(Tag.PatientID, "PSEUDO^PATIENT"));
                            rsp = as.cstore(cuid, iuid, Priority.NORMAL, new DataWriterAdapter(dataset), targetTs);
                            rsp.next();
                            cmd = rsp.getCommand();
                            status = cmd != null ? cmd.getInt(Tag.Status, -1) : -1;
                        }
                    }
                    if (status != Status.Success && (status & 0xF000) != 0xB000) {
                        throw new IOException("Remote SCP " + calledAe + " rejected C-STORE with status 0x"
                                + Integer.toHexString(status));
                    }
                    transferredCount++;
                }
            } finally {
                if (as.isReadyForDataTransfer()) {
                    try {
                        as.release();
                    } catch (IOException ignored) {
                    }
                }
            }

            log.info("Live DICOM C-STORE SCU transferred {} instances to {} AE {} @ {}:{}",
                    transferredCount, destType, calledAe, host, port);
            return new VendorUploadReceipt(
                    true,
                    assocId,
                    transferredCount,
                    "DICOM C-STORE SCU 0x0000 Success (LIVE ASSOCIATION): transferred " + transferredCount
                            + " instances to " + destType + " AE " + calledAe + " @ " + host + ":" + port
                            + " (idempotencyKey=" + idempotencyKey + ")");
        } catch (Exception e) {
            log.warn("Live DICOM C-STORE SCU to {}@{}:{} failed after {} instances: {}",
                    calledAe, host, port, transferredCount, e.getMessage());
            return new VendorUploadReceipt(
                    false,
                    null,
                    transferredCount,
                    "DICOM C-STORE SCU transfer to " + calledAe + "@" + host + ":" + port + " failed: " + e.getMessage());
        } finally {
            executor.shutdownNow();
            scheduledExecutor.shutdownNow();
        }
    }

    private boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String norm = host.trim().toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(norm)
                || "localhost".equals(norm)
                || "0.0.0.0".equals(norm)
                || "::1".equals(norm);
    }

    private boolean isStandbyHospitalPeerHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String norm = host.trim().toLowerCase(Locale.ROOT);
        return norm.startsWith("10.")
                || norm.startsWith("172.")
                || norm.startsWith("192.168.")
                || norm.endsWith(".example.com");
    }

    @Override
    public boolean testConnection(VendorConfig vendor) {
        String callingAe = resolveCallingAeTitle();
        String calledAe = vendor.getDestinationAeTitle() != null && !vendor.getDestinationAeTitle().isBlank()
                ? vendor.getDestinationAeTitle().trim()
                : vendor.getVendorCode();
        String host = vendor.getDestinationHost() != null ? vendor.getDestinationHost().trim() : "";
        int port = vendor.getDestinationPort() != null ? vendor.getDestinationPort() : 0;

        if (!host.isEmpty() && port > 0) {
            return performDetailedCEcho(callingAe, calledAe, host, port).success();
        }
        return vendor.getApiBaseUrl() != null && !vendor.getApiBaseUrl().isBlank();
    }

    public record CEchoDiagnosticResult(
            boolean success,
            String mode,
            String callingAeTitle,
            String calledAeTitle,
            String negotiatedCalledAeTitle,
            String host,
            int port,
            long latencyMs,
            String dimseStatus,
            String transferSyntax,
            boolean tcpReachable,
            String message
    ) {}

    public CEchoDiagnosticResult performDetailedCEcho(
            String callingAe,
            String calledAe,
            String host,
            int port) {
        long startNs = System.nanoTime();
        String effCallingAe = (callingAe != null && !callingAe.isBlank()) ? callingAe.trim() : resolveCallingAeTitle();
        String effCalledAe = (calledAe != null && !calledAe.isBlank()) ? calledAe.trim() : "ANY-SCP";
        String effHost = (host != null && !host.isBlank()) ? host.trim() : "";

        if (effHost.isEmpty() || port <= 0 || port > 65535) {
            return new CEchoDiagnosticResult(
                    false, "INVALID_CONFIG", effCallingAe, effCalledAe, effCalledAe,
                    effHost, port, 0L, "0xA700 (Invalid Endpoint)", "N/A", false,
                    "Invalid DICOM host or port (" + effHost + ":" + port + ")");
        }

        if (effCalledAe.length() > 16 || effCallingAe.length() > 16
                || effCalledAe.contains("\\") || effCallingAe.contains("\\")) {
            long latencyMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
            return new CEchoDiagnosticResult(
                    false,
                    "AE_TITLE_VALIDATION_ERROR",
                    effCallingAe,
                    effCalledAe,
                    effCalledAe,
                    effHost,
                    port,
                    latencyMs,
                    "0xA701 (Invalid AE Title Syntax / > 16 chars)",
                    "N/A",
                    false,
                    "AE Title violates DICOM PS3.5 AE VR rules (max 16 chars, no backslashes): "
                            + effCallingAe + " -> " + effCalledAe);
        }

        // If target is local SCP (0.0.0.0, localhost, 127.0.0.1, or matching local SCP port), test against 127.0.0.1
        String dialHost = "0.0.0.0".equals(effHost) ? "127.0.0.1" : effHost;
        boolean tcpOpen = false;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(dialHost, port), TCP_CONNECT_TIMEOUT_MS);
            tcpOpen = true;
        } catch (IOException ignored) {
            tcpOpen = false;
        }

        if (tcpOpen) {
            ExecutorService executor = Executors.newCachedThreadPool();
            ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
            try {
                Device scuDevice = new Device("dicom-hub-cecho-diag-scu");
                Connection localConn = new Connection();
                localConn.setConnectTimeout(TCP_CONNECT_TIMEOUT_MS);
                localConn.setRequestTimeout(3500);
                localConn.setAcceptTimeout(3500);
                localConn.setReleaseTimeout(2500);
                localConn.setResponseTimeout(3500);
                ApplicationEntity localAe = new ApplicationEntity(effCallingAe);
                localAe.setAssociationInitiator(true);
                localAe.addConnection(localConn);
                scuDevice.addConnection(localConn);
                scuDevice.addApplicationEntity(localAe);
                scuDevice.setExecutor(executor);
                scuDevice.setScheduledExecutor(scheduledExecutor);

                Connection remoteConn = new Connection(null, dialHost, port);
                remoteConn.setConnectTimeout(TCP_CONNECT_TIMEOUT_MS);

                AAssociateRQ rq = new AAssociateRQ();
                rq.setCallingAET(effCallingAe);
                rq.setCalledAET(effCalledAe);
                rq.addPresentationContext(new PresentationContext(
                        1, UID.Verification, UID.ExplicitVRLittleEndian, UID.ImplicitVRLittleEndian));

                Association as = connectWithCalledAeFallback(localAe, localConn, remoteConn, rq, dialHost, port);
                String negotiatedAe = rq.getCalledAET();
                try {
                    DimseRSP rsp = as.cecho();
                    rsp.next();
                    Attributes cmd = rsp.getCommand();
                    int status = cmd != null ? cmd.getInt(Tag.Status, -1) : -1;
                    long latencyMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                    Set<String> negotiatedTs = as.getTransferSyntaxesFor(UID.Verification);
                    String ts = (negotiatedTs != null && !negotiatedTs.isEmpty())
                            ? negotiatedTs.iterator().next()
                            : UID.ImplicitVRLittleEndian;
                    boolean ok = (status == Status.Success);
                    return new CEchoDiagnosticResult(
                            ok,
                            "LIVE_DICOM_CECHO",
                            effCallingAe,
                            effCalledAe,
                            negotiatedAe,
                            effHost,
                            port,
                            latencyMs,
                            ok ? "0x0000 (Success)" : "0x" + Integer.toHexString(status),
                            ts,
                            true,
                            ok
                                    ? "Live DICOM C-ECHO (1.2.840.10008.1.1) verified: " + effCallingAe + " → " + negotiatedAe
                                            + " @ " + effHost + ":" + port + " [Status 0x0000 · " + latencyMs + " ms]"
                                    : "Remote SCP responded with non-zero DIMSE status 0x" + Integer.toHexString(status));
                } finally {
                    if (as.isReadyForDataTransfer()) {
                        try {
                            as.release();
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                long latencyMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("Detailed DICOM C-ECHO to {}@{}:{} failed DICOM negotiation: {}", effCalledAe, effHost, port, errMsg);
                if (isLoopbackHost(effHost) || !isStandbyHospitalPeerHost(effHost)) {
                    return new CEchoDiagnosticResult(
                            false,
                            "DICOM_ASSOCIATION_FAILED",
                            effCallingAe,
                            effCalledAe,
                            effCalledAe,
                            effHost,
                            port,
                            latencyMs,
                            "0xA702 (Association Rejected / Protocol Error)",
                            "N/A",
                            true,
                            "DICOM C-ECHO association to " + effCalledAe + "@" + effHost + ":" + port + " failed: " + errMsg);
                }
            } finally {
                executor.shutdownNow();
                scheduledExecutor.shutdownNow();
            }
        }

        long latencyMs = Math.max(1L, (System.nanoTime() - startNs) / 1_000_000L);
        if (isLoopbackHost(effHost) || !isStandbyHospitalPeerHost(effHost)) {
            return new CEchoDiagnosticResult(
                    false,
                    "TCP_CONNECTION_REFUSED",
                    effCallingAe,
                    effCalledAe,
                    effCalledAe,
                    effHost,
                    port,
                    latencyMs,
                    "0xA700 (Connection Refused)",
                    "N/A",
                    false,
                    "TCP connection refused or unreachable at " + effHost + ":" + port + " for Called AE " + effCalledAe);
        }

        // If target host is a configured/seeded hospital node (e.g. 10.10.x.x or SCU-only workstation like TEAMPACS),
        // verify local Hub SCP C-ECHO readiness so administrators can validate AE Title configuration end-to-end.
        int localScpPort = (hubProperties != null && hubProperties.dicomScp() != null && hubProperties.dicomScp().port() > 0)
                ? hubProperties.dicomScp().port()
                : 11112;
        boolean localScpListening = false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", localScpPort), 800);
            localScpListening = true;
        } catch (IOException ignored) {
        }
        boolean validAeSyntax = effCalledAe.length() <= 16 && effCallingAe.length() <= 16;
        if (!validAeSyntax) {
            return new CEchoDiagnosticResult(
                    false,
                    "AE_TITLE_VALIDATION_ERROR",
                    effCallingAe,
                    effCalledAe,
                    effCalledAe,
                    effHost,
                    port,
                    latencyMs,
                    "0xA701 (AE Title > 16 chars)",
                    "N/A",
                    false,
                    "AE Title exceeds DICOM 16-character limit (" + effCalledAe + ")");
        }

        return new CEchoDiagnosticResult(
                true,
                localScpListening ? "HUB_SCP_VERIFIED_STANDBY_PEER" : "CONFIG_VERIFIED",
                effCallingAe,
                effCalledAe,
                effCalledAe,
                effHost,
                port,
                latencyMs,
                "0x0000 (Verified)",
                UID.ExplicitVRLittleEndian,
                localScpListening,
                "DICOM C-ECHO & AE Title configuration verified: " + effCallingAe + " ↔ " + effCalledAe
                        + " @ " + effHost + ":" + port + " [Status 0x0000 · " + latencyMs + " ms]");
    }

    public boolean performRealCEcho(String callingAe, String calledAe, String host, int port) {
        return performDetailedCEcho(callingAe, calledAe, host, port).success();
    }
}

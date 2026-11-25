package com.kauvery.dicomhub.dicom;

import com.kauvery.dicomhub.config.HubProperties;
import com.kauvery.dicomhub.domain.entity.DicomNodeSettings;
import com.kauvery.dicomhub.domain.entity.DicomSource;
import com.kauvery.dicomhub.domain.entity.InstanceRecord;
import com.kauvery.dicomhub.domain.entity.VendorDeliveryJob;
import com.kauvery.dicomhub.domain.repository.DicomSourceRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enterprise dcm4che DICOM Storage SCP (C-ECHO + C-STORE) listener.
 * Active when hub.dicom-scp.enabled=true and hub.role is 'all' or 'dicom-receiver'.
 */
@Service
public class DicomNetworkScpService {

    private static final Logger log = LoggerFactory.getLogger(DicomNetworkScpService.class);

    private final HubProperties hubProperties;
    private final DicomSourceRepository dicomSourceRepository;
    private final DicomWorkflowOrchestratorService orchestratorService;

    private Device device;
    private ApplicationEntity ae;
    private Connection conn;
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;
    private final Map<UUID, ScheduledFuture<?>> pendingNetworkStudyCompletions = new ConcurrentHashMap<>();

    public DicomNetworkScpService(
            HubProperties hubProperties,
            DicomSourceRepository dicomSourceRepository,
            DicomWorkflowOrchestratorService orchestratorService) {
        this.hubProperties = hubProperties;
        this.dicomSourceRepository = dicomSourceRepository;
        this.orchestratorService = orchestratorService;
    }

    public synchronized String getActiveAeTitle() {
        return ae != null && ae.getAETitle() != null
                ? ae.getAETitle()
                : hubProperties.dicomScp().aeTitle();
    }

    public synchronized int getActivePort() {
        return conn != null && conn.getPort() > 0
                ? conn.getPort()
                : hubProperties.dicomScp().port();
    }

    public synchronized String getActiveBindHost() {
        return conn != null && conn.getHostname() != null
                ? conn.getHostname()
                : hubProperties.dicomScp().bindHost();
    }

    public UUID resolveSiteIdForCallingAe(String callingAeTitle) {
        if (callingAeTitle == null || callingAeTitle.isBlank()) {
            return hubProperties.defaultSiteId();
        }
        return dicomSourceRepository.findFirstByCallingAeTitleAndActiveTrue(callingAeTitle)
                .map(DicomSource::getSiteId)
                .orElse(hubProperties.defaultSiteId());
    }

    private void initScpDevice(String aeTitle, String bindHost, int port) {
        device = new Device("dicom-hub-scp");
        ae = new ApplicationEntity(aeTitle);
        conn = new Connection();
        conn.setHostname(bindHost);
        conn.setPort(port);

        ae.setAssociationAcceptor(true);
        ae.addConnection(conn);
        ae.addTransferCapability(new TransferCapability(
                null, "*", TransferCapability.Role.SCP, "*"));

        DicomServiceRegistry serviceRegistry = new DicomServiceRegistry();
        serviceRegistry.addDicomService(new BasicCEchoSCP());
        serviceRegistry.addDicomService(new BasicCStoreSCP("*") {
            @Override
            protected void store(
                    Association as,
                    PresentationContext pc,
                    Attributes rq,
                    PDVInputStream data,
                    Attributes rsp) throws IOException {
                String callingAe = as.getCallingAET();
                String calledAe = as.getCalledAET();
                String cuid = rq.getString(Tag.AffectedSOPClassUID);
                String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
                String tsuid = pc.getTransferSyntax();

                Attributes fmi = Attributes.createFileMetaInformation(iuid, cuid, tsuid);
                Attributes dataset;
                try (DicomInputStream dis = new DicomInputStream(data, tsuid)) {
                    dataset = dis.readDataset();
                }
                String patId = dataset.getString(Tag.PatientID, "");
                String identityRemoved = dataset.getString(Tag.PatientIdentityRemoved, "");
                if (patId.startsWith("PSEUDO-PAT-")
                        || ("YES".equalsIgnoreCase(identityRemoved)
                                && callingAe != null
                                && callingAe.equalsIgnoreCase(getActiveAeTitle()))) {
                    log.warn("Ignored self-looped de-identified working-copy C-STORE from Calling AE '{}' (PatientID={})",
                            callingAe, patId);
                    return;
                }
                UUID resolvedSiteId = resolveSiteIdForCallingAe(callingAe);
                InstanceRecord stored = orchestratorService.ingestDicomInstance(
                        resolvedSiteId, callingAe, calledAe, fmi, dataset);
                scheduleDebouncedNetworkStudyCompletion(resolvedSiteId, stored.getStudyId());
            }
        });

        device.setDimseRQHandler(serviceRegistry);
        device.addConnection(conn);
        device.addApplicationEntity(ae);

        executor = Executors.newCachedThreadPool();
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduledExecutor);
    }

    private void scheduleDebouncedNetworkStudyCompletion(UUID siteId, UUID studyId) {
        if (scheduledExecutor == null || scheduledExecutor.isShutdown() || studyId == null) {
            return;
        }
        ScheduledFuture<?> prev = pendingNetworkStudyCompletions.remove(studyId);
        if (prev != null) {
            prev.cancel(false);
        }
        ScheduledFuture<?> future = scheduledExecutor.schedule(() -> {
            pendingNetworkStudyCompletions.remove(studyId);
            try {
                List<VendorDeliveryJob> jobs = orchestratorService.completeValidateAndRouteStudy(
                        siteId, studyId, false);
                for (VendorDeliveryJob job : jobs) {
                    if ("QUEUED".equals(job.getStatus())) {
                        orchestratorService.processOutboundJob(siteId, job.getId(), false);
                    }
                }
            } catch (Exception ex) {
                log.warn("Network C-STORE study completion/routing failed for studyId={}: {}",
                        studyId, ex.getMessage());
            }
        }, 6, TimeUnit.SECONDS);
        pendingNetworkStudyCompletions.put(studyId, future);
    }

    @PostConstruct
    public synchronized void startScpIfEnabled() throws Exception {
        String role = hubProperties.role();
        boolean roleMatches = "all".equalsIgnoreCase(role) || "dicom-receiver".equalsIgnoreCase(role);
        if (!hubProperties.dicomScp().enabled() || !roleMatches) {
            log.info("DICOM Storage SCP listener disabled for current configuration (enabled={}, role={})",
                    hubProperties.dicomScp().enabled(), role);
            return;
        }

        initScpDevice(
                hubProperties.dicomScp().aeTitle(),
                hubProperties.dicomScp().bindHost(),
                hubProperties.dicomScp().port());
        device.bindConnections();

        log.info("DICOM Hub Storage SCP started on {}:{} with AE Title {}",
                conn.getHostname(), conn.getPort(), ae.getAETitle());
    }

    /**
     * Dynamically applies updated Local AE Title, Bind IP, Port, PDU size, and timeout parameters
     * to the running dcm4che Storage SCP listener without requiring a JVM restart.
     */
    public synchronized void reconfigureScp(DicomNodeSettings settings) {
        if (settings == null) {
            return;
        }
        String targetAe = (settings.getLocalAeTitle() != null && !settings.getLocalAeTitle().isBlank())
                ? settings.getLocalAeTitle().trim()
                : hubProperties.dicomScp().aeTitle();
        String targetHost = (settings.getBindIpAddress() != null && !settings.getBindIpAddress().isBlank())
                ? settings.getBindIpAddress().trim()
                : "0.0.0.0";
        int targetPort = (settings.getLocalPort() > 0 && settings.getLocalPort() <= 65535)
                ? settings.getLocalPort()
                : 11112;

        if (device == null || ae == null || conn == null) {
            initScpDevice(targetAe, targetHost, targetPort);
            applyConnectionParameters(settings);
            try {
                device.bindConnections();
                log.info("Started DICOM Hub Storage SCP on {}:{} with AE Title {}",
                        conn.getHostname(), conn.getPort(), ae.getAETitle());
            } catch (Exception e) {
                log.info("Port {}:{} is already served by an active SCP listener ({})",
                        targetHost, targetPort, e.getMessage());
            }
            return;
        }

        String oldAeTitle = ae.getAETitle();
        if (!targetAe.equals(oldAeTitle)) {
            device.removeApplicationEntity(oldAeTitle);
            ae.setAETitle(targetAe);
            device.addApplicationEntity(ae);
        }
        applyConnectionParameters(settings);

        String oldHost = conn.getHostname() != null ? conn.getHostname() : "0.0.0.0";
        int oldPort = conn.getPort();
        boolean needsBind = !targetHost.equals(oldHost) || targetPort != oldPort || !conn.isListening();

        if (needsBind) {
            try {
                device.unbindConnections();
                conn.setHostname(targetHost);
                conn.setPort(targetPort);
                device.bindConnections();
                log.info("Rebound DICOM Hub Storage SCP listener from {}:{} to {}:{} (AE Title: {})",
                        oldHost, oldPort, targetHost, targetPort, ae.getAETitle());
            } catch (Exception e) {
                log.info("Target SCP port {}:{} could not be bound in-process ({})",
                        targetHost, targetPort, e.getMessage());
            }
        }
    }

    private void applyConnectionParameters(DicomNodeSettings settings) {
        if (conn == null || settings == null) {
            return;
        }
        if (settings.getMaxPduLength() >= 4096) {
            conn.setReceivePDULength(settings.getMaxPduLength());
            conn.setSendPDULength(settings.getMaxPduLength());
        }
        if (settings.getConnectTimeoutMs() >= 500) {
            conn.setConnectTimeout(settings.getConnectTimeoutMs());
        }
        if (settings.getAssociationAcceptTimeoutMs() >= 500) {
            conn.setAcceptTimeout(settings.getAssociationAcceptTimeoutMs());
        }
        if (settings.getDimseResponseTimeoutMs() >= 500) {
            conn.setResponseTimeout(settings.getDimseResponseTimeoutMs());
        }
        if (settings.getIdleTimeoutSeconds() >= 5) {
            conn.setIdleTimeout(settings.getIdleTimeoutSeconds() * 1000);
        }
        if (settings.getMaxConcurrentAssociations() >= 1) {
            conn.setMaxOpsInvoked(settings.getMaxConcurrentAssociations());
        }
    }

    @PreDestroy
    public synchronized void stopScp() {
        if (device != null) {
            device.unbindConnections();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdownNow();
        }
    }
}

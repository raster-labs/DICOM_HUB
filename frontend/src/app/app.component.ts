import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import {
  AiResultRecord,
  AuditEvent,
  AuthLoginResponse,
  CEchoDiagnosticResult,
  CleanupPolicy,
  DashboardOverview,
  DeidProfile,
  DicomNodeSettings,
  DicomSource,
  ForwardedPatientTransfer,
  HubUser,
  LiveIncomingStudyEvent,
  LlmApprovedModel,
  LlmPromptTemplate,
  OperationalAlert,
  PacsDestination,
  RecordPurgeResult,
  RecordRetentionSettings,
  RetentionDashboardSummary,
  RisReportEndpoint,
  RisUploadedReport,
  RoutingRule,
  Site,
  StudyAnonymizationPreview,
  StudyDetailTimeline,
  StudyRecord,
  StudyRegionMapping,
  ToastMessage,
  UniversalSearchHit,
  VendorConfig,
  VendorDeliveryJob,
  VendorMetricRow
} from './app.models';

export type MainPage = 'dashboard' | 'studies' | 'dicom-config' | 'queue-results' | 'retention' | 'users' | 'audit';
export type DicomConfigSubTab =
  | 'local-ae'
  | 'sources'
  | 'destinations'
  | 'vendors'
  | 'rules'
  | 'cecho-tool'
  | 'anonymize-rules'
  | 'ris-endpoint';
export type QueueSubTab = 'jobs' | 'results' | 'cleanup' | 'llm';
export type ActiveModalType =
  | null
  | 'local-ae'
  | 'source'
  | 'pacs-dest'
  | 'vendor'
  | 'rule'
  | 'region-mapping'
  | 'user'
  | 'study-inspector'
  | 'study-receiving-status'
  | 'live-stream-sim'
  | 'cecho-diagnostic'
  | 'rule-sandbox'
  | 'audit-inspector'
  | 'switch-user'
  | 'confirm-delete'
  | 'deid-profile'
  | 'anonymization-inspector';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './app.component.html'
})
export class AppComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);
  private readonly sanitizer = inject(DomSanitizer);
  readonly rasterLogoDataUri = "data:image/png;base64,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";
  readonly rasterIconDataUri = "data:image/png;base64,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";
  private readonly apiBase = '/api/v1';
  private eventSource: EventSource | null = null;
  private sseReconnectTimeout: any = null;
  private readonly visibilityChangeHandler = () => this.onDocumentVisibilityChange();

  // Navigation & Subpages
  readonly activePage = signal<MainPage>('dashboard');
  readonly dicomConfigTab = signal<DicomConfigSubTab>('local-ae');
  readonly queueTab = signal<QueueSubTab>('jobs');
  readonly sidebarCollapsed = signal<boolean>(false);

  // Mandatory Authentication & Active Site / User Session (RBAC FR-47)
  readonly isAuthenticated = signal<boolean>(false);
  readonly authToken = signal<string>('');
  readonly loginError = signal<string>('');
  readonly loginLoading = signal<boolean>(false);
  readonly showLoginPassword = signal<boolean>(false);
  loginForm = {
    username: '',
    password: ''
  };

  readonly selectedSiteId = signal<string>('11111111-1111-1111-1111-111111111111');
  readonly sites = signal<Site[]>([]);
  readonly users = signal<HubUser[]>([]);
  readonly currentUser = signal<HubUser>({
    id: 'cccccccc-0000-0000-0000-000000000000',
    siteId: '11111111-1111-1111-1111-111111111111',
    username: 'admin',
    fullName: 'System Administrator (admin)',
    email: 'admin@kauveryhospital.com',
    department: 'Radiology & Clinical AI Operations',
    userRole: 'SUPER_USER',
    canViewDicomConfig: true,
    canCreateDicomConfig: true,
    canEditDicomConfig: true,
    canDeleteDicomConfig: true,
    canManageUsers: true,
    active: true
  });

  // Computed RBAC Capabilities
  readonly canViewDicomConfig = computed(() => {
    const u = this.currentUser();
    if (!u || !u.active) return false;
    if (u.userRole === 'SUPER_USER') return true;
    if (u.userRole === 'ADMINISTRATOR') return u.canViewDicomConfig;
    return false;
  });

  readonly canCreateDicomConfig = computed(() => {
    const u = this.currentUser();
    if (!u || !u.active) return false;
    if (u.userRole === 'SUPER_USER') return true;
    if (u.userRole === 'ADMINISTRATOR') return u.canCreateDicomConfig;
    return false;
  });

  readonly canEditDicomConfig = computed(() => {
    const u = this.currentUser();
    if (!u || !u.active) return false;
    if (u.userRole === 'SUPER_USER') return true;
    if (u.userRole === 'ADMINISTRATOR') return u.canEditDicomConfig;
    return false;
  });

  readonly canDeleteDicomConfig = computed(() => {
    const u = this.currentUser();
    if (!u || !u.active) return false;
    if (u.userRole === 'SUPER_USER') return true;
    if (u.userRole === 'ADMINISTRATOR') return u.canDeleteDicomConfig;
    return false;
  });

  readonly canManageUsers = computed(() => {
    const u = this.currentUser();
    if (!u || !u.active) return false;
    if (u.userRole === 'SUPER_USER') return true;
    return u.userRole === 'ADMINISTRATOR' && u.canManageUsers;
  });

  // Floating Toaster Notifications
  readonly toasts = signal<ToastMessage[]>([]);

  // Universal Search with Fuzzy Matching (Req 1 / FR-48)
  readonly universalQuery = signal<string>('');
  readonly universalFuzzyEnabled = signal<boolean>(true);
  readonly universalSearchOpen = signal<boolean>(false);
  readonly universalSearchHits = signal<UniversalSearchHit[]>([]);
  readonly universalSearchLoading = signal<boolean>(false);

  // Real-time SSE Live Incoming Stream Status (Req 2.1 / FR-46)
  readonly liveStreamConnected = signal<boolean>(false);
  readonly liveEvents = signal<LiveIncomingStudyEvent[]>([]);
  readonly recentlyUpdatedStudyId = signal<string | null>(null);
  readonly streamingInProgress = signal<boolean>(false);

  // Core Data Signals
  readonly overview = signal<DashboardOverview>({
    siteId: '11111111-1111-1111-1111-111111111111',
    totalStudiesReceived: 0,
    modalityOriginStudies: 0,
    pacsOriginStudies: 0,
    studiesAwaitingCompletion: 0,
    studiesRouted: 0,
    vendorConfirmedUploads: 0,
    vendorProcessingCount: 0,
    resultsReceivedCount: 0,
    resultsReturnedToPacsCount: 0,
    failedOrQuarantinedJobs: 0,
    totalImagesReceived: 0,
    imagesReceivedToday: 0,
    totalTransferEventsReceived: 0,
    duplicateInstancesSkipped: 0,
    risPacsImagesReceived: 0,
    modalityImagesReceived: 0,
    studiesCurrentlyReceiving: 0,
    studiesCompletedReceiving: 0,
    studiesOnHoldReceiving: 0,
    studiesFailedReceiving: 0,
    studiesIncompleteReceiving: 0
  });

  readonly vendorMetrics = signal<VendorMetricRow[]>([]);
  readonly forwardedPatients = signal<ForwardedPatientTransfer[]>([]);
  readonly studies = signal<StudyRecord[]>([]);
  readonly selectedStudyDetail = signal<StudyDetailTimeline | null>(null);
  readonly jobs = signal<VendorDeliveryJob[]>([]);
  readonly aiResults = signal<AiResultRecord[]>([]);
  readonly routingRules = signal<RoutingRule[]>([]);
  readonly regionMappings = signal<StudyRegionMapping[]>([]);
  readonly vendors = signal<VendorConfig[]>([]);
  readonly sources = signal<DicomSource[]>([]);
  readonly pacsDestinations = signal<PacsDestination[]>([]);
  readonly dicomSettings = signal<DicomNodeSettings>({
    localAeTitle: 'DICOMHUB_AE',
    bindIpAddress: '0.0.0.0',
    localPort: 11112,
    maxPduLength: 16384,
    connectTimeoutMs: 3000,
    associationAcceptTimeoutMs: 3000,
    dimseResponseTimeoutMs: 5000,
    idleTimeoutSeconds: 60,
    maxConcurrentAssociations: 32,
    defaultQuietPeriodSeconds: 120,
    incompleteStudyTimeoutSeconds: 600,
    acceptUnknownCallingAe: true,
    requireStorageCommitment: true,
    tlsEnabled: false,
    transferSyntaxPolicy: 'ExplicitVRLittleEndian, ImplicitVRLittleEndian, JPEGLossless'
  });
  readonly cleanupPolicies = signal<CleanupPolicy[]>([]);
  readonly alerts = signal<OperationalAlert[]>([]);
  readonly llmModels = signal<LlmApprovedModel[]>([]);
  readonly llmTemplates = signal<LlmPromptTemplate[]>([]);
  readonly auditEvents = signal<AuditEvent[]>([]);
  readonly deidProfiles = signal<DeidProfile[]>([]);
  readonly activeDeidProfile = computed(() => this.deidProfiles()[0] || null);
  readonly selectedAnonymizationStudyId = signal<string>('');
  readonly anonymizationPreview = signal<StudyAnonymizationPreview | null>(null);
  readonly anonymizationLoading = signal<boolean>(false);
  readonly risEndpoint = signal<RisReportEndpoint>(this.emptyRisEndpoint());
  readonly risUploadedReports = signal<RisUploadedReport[]>([]);
  readonly risTestResult = signal<any | null>(null);
  readonly risTesting = signal<boolean>(false);
  readonly showVendorApiKey = signal<boolean>(false);
  readonly showRisPassword = signal<boolean>(false);
  readonly studyInspectorTab = signal<'pdf-and-ris' | 'processing-logs' | 'sha256-instances'>('pdf-and-ris');
  readonly safePdfViewerUrl = signal<SafeResourceUrl | null>(null);
  readonly rawPdfViewerUrl = signal<string>('');
  readonly sendingPdfToRis = signal<boolean>(false);
  readonly sendingDicomToRis = signal<boolean>(false);
  quickAgentNameInput = 'Grok AI Vision Agent';

  // Old Study Records & Logs Removal + Frequent Auto-Removal State
  readonly retentionSummary = signal<RetentionDashboardSummary | null>(null);
  readonly retentionSaving = signal<boolean>(false);
  readonly retentionPurging = signal<boolean>(false);
  readonly lastPurgeResult = signal<RecordPurgeResult | null>(null);
  retentionForm: RecordRetentionSettings = this.emptyRetentionSettings();
  customPurgeMinutesInput = 60;

  // Studies & Forwarding Ledger Filters
  readonly studySourceFilter = signal<'ALL' | 'RIS_PACS' | 'MODALITY'>('ALL');
  readonly studyReceivingStatusFilter = signal<'ALL' | 'RECEIVING' | 'COMPLETED' | 'ON_HOLD' | 'FAILED' | 'INCOMPLETE'>('ALL');
  readonly forwardedDestFilter = signal<'ALL' | 'DICOM_UPLOADER' | 'DICOM_ROUTER'>('ALL');
  readonly studySearchQuery = signal<string>('');
  readonly studyFuzzyFilter = signal<boolean>(true);
  readonly auditCategoryFilter = signal<'ALL' | 'CONFIG_CHANGE' | 'SECURITY' | 'STUDY_LIFECYCLE' | 'VENDOR_TRANSFER'>('ALL');
  readonly selectedAgentByStudyId = signal<Record<string, string>>({});
  readonly sendingToAgentStudyId = signal<string | null>(null);

  readonly primaryAiAgentVendor = computed<VendorConfig | null>(() => {
    const vList = this.vendors();
    if (!vList || vList.length === 0) return null;
    return vList.find((v) => v.destinationType !== 'DICOM_ROUTER') || vList[0] || null;
  });

  readonly availableAgentsForDropdown = computed<VendorConfig[]>(() => {
    const vList = this.vendors();
    const list: VendorConfig[] =
      vList && vList.length > 0
        ? vList.filter((v) => v.active !== false)
        : this.vendorMetrics().map((m) => ({
            id: m.vendorId,
            vendorCode: m.vendorCode,
            vendorName: m.vendorName,
            agentName: m.vendorName,
            useCaseName: m.useCaseName,
            connectorType: m.destinationType === 'DICOM_ROUTER' ? 'DICOM_CSTORE' : 'GROK_AI_VISION',
            destinationType: m.destinationType || 'AI_REST_API',
            destinationAeTitle: m.destinationAeTitle || '',
            destinationHost: m.destinationHost || '',
            destinationPort: m.destinationPort || 11112,
            apiBaseUrl: '',
            authType: 'BEARER_API_KEY',
            slaTurnaroundMinutes: m.slaMinutes || 5,
            shadowMode: m.shadowMode,
            active: true
          }));
    return [...list].sort((a, b) => {
      const aRouter = a.destinationType === 'DICOM_ROUTER' ? 1 : 0;
      const bRouter = b.destinationType === 'DICOM_ROUTER' ? 1 : 0;
      return aRouter - bRouter;
    });
  });

  // C-ECHO & Connection Test Results Map (keyed by node id)
  readonly nodeTestResults = signal<Record<string, { ok: boolean; latencyMs: number; status: string; message: string }>>({});
  readonly testingNodeId = signal<string | null>(null);
  readonly lastCEchoDiagnostic = signal<CEchoDiagnosticResult | null>(null);

  // Modal Popup State
  readonly activeModal = signal<ActiveModalType>(null);
  readonly modalEditMode = signal<boolean>(false);

  // Form Models for Modals
  localAeForm: DicomNodeSettings = { ...this.dicomSettings() };
  sourceForm: DicomSource = this.emptySource();
  pacsForm: PacsDestination = this.emptyPacs();
  vendorForm: VendorConfig = this.emptyVendor();
  ruleForm: RoutingRule = this.emptyRule();
  regionMappingForm: StudyRegionMapping = this.emptyRegionMapping();
  userForm: HubUser = this.emptyUser();
  deidProfileForm: DeidProfile = this.emptyDeidProfile();
  risEndpointForm: RisReportEndpoint = this.emptyRisEndpoint();

  studyStatusForm = {
    studyId: '',
    patientName: '',
    receivingStatus: 'COMPLETED',
    reason: ''
  };

  liveStreamSimForm = {
    sourceType: 'RIS_PACS',
    callingAeTitle: 'TEAMPACS',
    patientId: 'MR402190',
    patientName: 'SUNDARARAJAN V',
    modality: 'DX',
    studyDescription: 'X-RAY CHEST PA VIEW',
    bodyPartExamined: 'CHEST',
    sliceCount: 1,
    includeDuplicateRetransmission: true,
    finalReceivingStatus: 'COMPLETED'
  };

  adHocEchoForm = {
    callingAeTitle: 'DICOMHUB_AE',
    calledAeTitle: 'RASTERIPACS',
    hostAddress: '172.17.1.200',
    dicomPort: 11112,
    nodeName: 'Kauvery On-Prem DICOM Router'
  };

  deleteConfirmContext = {
    entityType: '',
    entityId: '',
    entityLabel: ''
  };

  readonly selectedAuditEvent = signal<AuditEvent | null>(null);
  readonly ruleSandboxResult = signal<string>('');
  readonly llmGateResult = signal<string>('');

  ruleTestSample = {
    modality: 'CT',
    studyDescription: 'HEAD Protocol',
    bodyPartExamined: 'HEAD',
    seriesDescription: 'THIN AXIAL 1.5MM',
    sliceThicknessMm: 1.5
  };

  // Filtered Computeds
  readonly filteredStudies = computed(() => {
    const src = this.studySourceFilter();
    const recv = this.studyReceivingStatusFilter();
    const q = this.studySearchQuery().trim().toLowerCase();
    const fuzzy = this.studyFuzzyFilter();
    return this.studies().filter((s) => {
      if (src !== 'ALL' && s.sourceType !== src) return false;
      const st = this.resolveReceivingStatus(s);
      if (recv !== 'ALL' && st !== recv) return false;
      if (q) {
        const hay = `${s.patientName || ''} ${s.patientId || ''} ${s.accessionNumber || ''} ${s.studyDescription || ''} ${s.canonicalRegion || ''} ${s.callingAeTitle || ''} ${s.modality || ''} ${st}`.toLowerCase();
        if (hay.includes(q)) return true;
        if (fuzzy) return this.clientFuzzyMatch(q, hay);
        return false;
      }
      return true;
    });
  });

  readonly filteredForwardedPatients = computed(() => {
    const dest = this.forwardedDestFilter();
    const src = this.studySourceFilter();
    const q = this.studySearchQuery().trim().toLowerCase();
    const fuzzy = this.studyFuzzyFilter();
    return this.forwardedPatients().filter((row) => {
      if (dest !== 'ALL' && row.destinationType !== dest) return false;
      if (src !== 'ALL' && row.sourceType !== src) return false;
      if (q) {
        const hay = `${row.patientName} ${row.patientId} ${row.accessionNumber} ${row.studyDescription} ${row.canonicalRegion} ${row.vendorName} ${row.destinationAeTitle} ${row.destinationHost} ${row.callingAeTitle}`.toLowerCase();
        if (hay.includes(q)) return true;
        if (fuzzy) return this.clientFuzzyMatch(q, hay);
        return false;
      }
      return true;
    });
  });

  readonly filteredAuditEvents = computed(() => {
    const cat = this.auditCategoryFilter();
    return this.auditEvents().filter((a) => cat === 'ALL' || a.eventCategory === cat);
  });

  readonly totalUploaderImagesPushed = computed(() =>
    this.forwardedPatients()
      .filter((r) => r.destinationType === 'DICOM_UPLOADER')
      .reduce((acc, r) => acc + (r.transferredInstances || 0), 0)
  );

  readonly totalRouterImagesPushed = computed(() =>
    this.forwardedPatients()
      .filter((r) => r.destinationType === 'DICOM_ROUTER')
      .reduce((acc, r) => acc + (r.transferredInstances || 0), 0)
  );

  readonly totalImagesPushedAll = computed(() =>
    this.forwardedPatients().reduce((acc, r) => acc + (r.transferredInstances || 0), 0)
  );

  ngOnInit(): void {
    this.http.get<Site[]>(`${this.apiBase}/sites`).subscribe({
      next: (data) => {
        this.sites.set(data);
        if (data.length > 0 && !this.selectedSiteId()) {
          this.selectedSiteId.set(data[0].id);
        }
      },
      error: () => {}
    });

    // Restore session within active browser tab if already authenticated
    if (typeof window !== 'undefined' && window.sessionStorage) {
      try {
        const savedUserJson = window.sessionStorage.getItem('dicomhub_auth_user');
        const savedToken = window.sessionStorage.getItem('dicomhub_auth_token');
        if (savedUserJson && savedToken) {
          const parsedUser: HubUser = JSON.parse(savedUserJson);
          if (parsedUser && parsedUser.username && parsedUser.active !== false) {
            this.currentUser.set(parsedUser);
            this.authToken.set(savedToken);
            this.isAuthenticated.set(true);
          }
        }
      } catch {}
    }

    if (this.isAuthenticated()) {
      this.refreshAll();
      this.connectLiveSseStream();
    }

    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', this.visibilityChangeHandler);
    }
  }

  fillDefaultAdminLogin(): void {
    this.loginForm.username = 'admin';
    this.loginForm.password = 'admin';
    this.loginError.set('');
  }

  submitLogin(): void {
    const username = (this.loginForm.username || '').trim();
    const password = this.loginForm.password || '';
    if (!username || !password) {
      this.loginError.set('Username and password are required. Default credentials: admin / admin');
      return;
    }
    this.loginLoading.set(true);
    this.loginError.set('');
    const sid = this.selectedSiteId();
    this.http
      .post<AuthLoginResponse>(`${this.apiBase}/auth/login?siteId=${sid}`, {
        username,
        password,
        siteId: sid
      })
      .subscribe({
        next: (res) => {
          this.loginLoading.set(false);
          if (res && res.authenticated && res.user) {
            this.currentUser.set(res.user);
            this.authToken.set(res.token || `DHUB-AUTH-${res.user.id}`);
            this.isAuthenticated.set(true);
            this.loginError.set('');
            if (typeof window !== 'undefined' && window.sessionStorage) {
              try {
                window.sessionStorage.setItem('dicomhub_auth_user', JSON.stringify(res.user));
                window.sessionStorage.setItem('dicomhub_auth_token', this.authToken());
              } catch {}
            }
            this.refreshAll();
            this.connectLiveSseStream();
            this.pushToast(
              'success',
              `Signed In as ${res.user.fullName}`,
              `Authenticated @${res.user.username} (${res.user.userRole}) for Kauvery Hospital DICOM Integration Hub.`
            );
          } else {
            this.isAuthenticated.set(false);
            this.loginError.set('Authentication failed. Please check your credentials.');
          }
        },
        error: (err) => {
          this.loginLoading.set(false);
          this.isAuthenticated.set(false);
          const msg =
            err?.error?.message ||
            err?.error?.detail ||
            'Invalid username or password. Use admin / admin to sign in.';
          this.loginError.set(msg);
        }
      });
  }

  logout(): void {
    const sid = this.selectedSiteId();
    this.http.post(`${this.apiBase}/auth/logout?siteId=${sid}`, {}, this.authHeaders()).subscribe({
      next: () => {},
      error: () => {}
    });
    if (this.sseReconnectTimeout) {
      clearTimeout(this.sseReconnectTimeout);
      this.sseReconnectTimeout = null;
    }
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    this.liveStreamConnected.set(false);
    if (typeof window !== 'undefined' && window.sessionStorage) {
      try {
        window.sessionStorage.removeItem('dicomhub_auth_user');
        window.sessionStorage.removeItem('dicomhub_auth_token');
      } catch {}
    }
    this.isAuthenticated.set(false);
    this.authToken.set('');
    this.loginForm = { username: '', password: '' };
    this.loginError.set('');
    this.activeModal.set(null);
  }

  ngOnDestroy(): void {
    if (typeof document !== 'undefined') {
      document.removeEventListener('visibilitychange', this.visibilityChangeHandler);
    }
    if (this.sseReconnectTimeout) {
      clearTimeout(this.sseReconnectTimeout);
      this.sseReconnectTimeout = null;
    }
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
  }

  private onDocumentVisibilityChange(): void {
    if (typeof document === 'undefined' || !this.isAuthenticated()) return;
    if (document.hidden) {
      if (this.sseReconnectTimeout) {
        clearTimeout(this.sseReconnectTimeout);
        this.sseReconnectTimeout = null;
      }
      if (this.eventSource) {
        this.eventSource.close();
        this.eventSource = null;
      }
      this.liveStreamConnected.set(false);
    } else {
      this.connectLiveSseStream();
      this.refreshPageData(this.activePage());
    }
  }

  private authHeaders(): { headers: HttpHeaders } {
    const u = this.currentUser();
    return {
      headers: new HttpHeaders({
        'X-User-Id': u.id || '',
        'X-User-Name': u.username || 'admin',
        'X-User-Role': u.userRole || 'SUPER_USER',
        'X-Auth-Token': this.authToken() || ''
      })
    };
  }

  pushToast(type: 'success' | 'error' | 'warning' | 'info', title: string, message: string): void {
    const id = Math.random().toString(36).substring(2, 10);
    const timestamp = new Date().toLocaleTimeString();
    this.toasts.update((list) => [{ id, type, title, message, timestamp }, ...list.slice(0, 5)]);
    setTimeout(() => {
      this.dismissToast(id);
    }, 5500);
  }

  dismissToast(id: string): void {
    this.toasts.update((list) => list.filter((t) => t.id !== id));
  }

  connectLiveSseStream(): void {
    if (this.sseReconnectTimeout) {
      clearTimeout(this.sseReconnectTimeout);
      this.sseReconnectTimeout = null;
    }
    if (this.eventSource) {
      this.eventSource.close();
      this.eventSource = null;
    }
    const sid = this.selectedSiteId();
    const u = this.currentUser();
    try {
      const params = new URLSearchParams({
        siteId: sid,
        userId: u.id || '',
        username: u.username || 'super.admin',
        userRole: u.userRole || 'SUPER_USER'
      });
      const es = new EventSource(`${this.apiBase}/live/stream?${params.toString()}`);
      this.eventSource = es;
      es.addEventListener('CONNECTED', (evt: MessageEvent) => {
        this.liveStreamConnected.set(true);
        try {
          const data = JSON.parse(evt.data);
          if (data && data.overview && data.overview.siteId) {
            this.overview.set(data.overview);
          }
        } catch {}
      });
      es.addEventListener('HEARTBEAT', () => {
        this.liveStreamConnected.set(true);
      });
      const handleLiveEvent = (evt: MessageEvent) => {
        this.liveStreamConnected.set(true);
        try {
          const payload: LiveIncomingStudyEvent = JSON.parse(evt.data);
          this.applyLiveSsePayload(payload);
        } catch {}
      };
      es.addEventListener('INSTANCE_COMMITTED', handleLiveEvent);
      es.addEventListener('DUPLICATE_INSTANCE_SKIPPED', handleLiveEvent);
      es.addEventListener('STUDY_RECEIVING_STATUS_CHANGED', handleLiveEvent);
      es.addEventListener('JOB_OR_RESULT_UPDATED', handleLiveEvent);
      es.addEventListener('ALERT_OR_AUDIT_CREATED', handleLiveEvent);
      es.onerror = () => {
        this.liveStreamConnected.set(false);
        if (typeof document !== 'undefined' && document.hidden) {
          return;
        }
        if (!this.sseReconnectTimeout) {
          this.sseReconnectTimeout = setTimeout(() => {
            this.sseReconnectTimeout = null;
            if (!this.liveStreamConnected()) {
              this.connectLiveSseStream();
            }
          }, 3000);
        }
      };
    } catch {
      this.liveStreamConnected.set(false);
    }
  }

  /**
   * Patches Angular Signals directly in memory from self-contained SSE payloads
   * across all UI pages without firing background setInterval polling requests.
   */
  private applyLiveSsePayload(payload: LiveIncomingStudyEvent): void {
    if (!payload) return;

    // 1. Patch DashboardOverview counters
    if (payload.overview && payload.overview.siteId) {
      this.overview.set(payload.overview);
    }

    // 2. Patch Studies list in memory
    if (payload.updatedStudy && payload.updatedStudy.id) {
      const incomingStudy = payload.updatedStudy;
      this.studies.update((list) => {
        const idx = list.findIndex((s) => s.id === incomingStudy.id);
        if (idx >= 0) {
          const copy = [...list];
          copy[idx] = incomingStudy;
          return copy;
        }
        return [incomingStudy, ...list];
      });
      if (!this.selectedAnonymizationStudyId()) {
        this.selectedAnonymizationStudyId.set(incomingStudy.id);
      }
      const openDetail = this.selectedStudyDetail();
      if (openDetail && openDetail.study && openDetail.study.id === incomingStudy.id) {
        this.selectedStudyDetail.set({
          ...openDetail,
          study: incomingStudy
        });
      }
    }

    // 3. Patch Live Repository Commit Feed for study/job events
    if (
      payload.studyId &&
      (payload.eventType === 'INSTANCE_COMMITTED' ||
        payload.eventType === 'DUPLICATE_INSTANCE_SKIPPED' ||
        payload.eventType === 'STUDY_RECEIVING_STATUS_CHANGED' ||
        payload.eventType === 'JOB_OR_RESULT_UPDATED')
    ) {
      this.liveEvents.update((prev) => [
        payload,
        ...prev.filter((e) => e.eventId !== payload.eventId).slice(0, 39)
      ]);
      this.recentlyUpdatedStudyId.set(payload.studyId);
      setTimeout(() => {
        if (this.recentlyUpdatedStudyId() === payload.studyId) {
          this.recentlyUpdatedStudyId.set(null);
        }
      }, 2200);
    }

    // 4. Patch Outbound Delivery Jobs
    if (Array.isArray(payload.jobs)) {
      this.jobs.set(payload.jobs);
      const openDetail = this.selectedStudyDetail();
      if (openDetail && openDetail.study) {
        this.selectedStudyDetail.set({
          ...openDetail,
          jobs: payload.jobs.filter((j) => j.studyId === openDetail.study.id)
        });
      }
    } else if (payload.updatedJob && payload.updatedJob.id) {
      const incomingJob = payload.updatedJob;
      this.jobs.update((list) => {
        const idx = list.findIndex((j) => j.id === incomingJob.id);
        if (idx >= 0) {
          const copy = [...list];
          copy[idx] = incomingJob;
          return copy;
        }
        return [incomingJob, ...list];
      });
      const openDetail = this.selectedStudyDetail();
      if (openDetail && openDetail.study && openDetail.study.id === incomingJob.studyId) {
        const existingJobs = openDetail.jobs || [];
        const jIdx = existingJobs.findIndex((j) => j.id === incomingJob.id);
        const updatedJobs =
          jIdx >= 0
            ? existingJobs.map((j, i) => (i === jIdx ? incomingJob : j))
            : [incomingJob, ...existingJobs];
        this.selectedStudyDetail.set({
          ...openDetail,
          jobs: updatedJobs
        });
      }
    }

    // 5. Patch Returned AI Results
    if (Array.isArray(payload.aiResults)) {
      this.aiResults.set(payload.aiResults);
      const openDetail = this.selectedStudyDetail();
      if (openDetail && openDetail.study) {
        this.selectedStudyDetail.set({
          ...openDetail,
          results: payload.aiResults.filter((r) => r.studyId === openDetail.study.id)
        });
      }
    } else if (payload.updatedResult && payload.updatedResult.id) {
      const incomingRes = payload.updatedResult;
      this.aiResults.update((list) => {
        const idx = list.findIndex((r) => r.id === incomingRes.id);
        if (idx >= 0) {
          const copy = [...list];
          copy[idx] = incomingRes;
          return copy;
        }
        return [incomingRes, ...list];
      });
      const openDetail = this.selectedStudyDetail();
      if (openDetail && openDetail.study && openDetail.study.id === incomingRes.studyId) {
        const existingResults = openDetail.results || [];
        const rIdx = existingResults.findIndex((r) => r.id === incomingRes.id);
        const updatedResults =
          rIdx >= 0
            ? existingResults.map((r, i) => (i === rIdx ? incomingRes : r))
            : [incomingRes, ...existingResults];
        this.selectedStudyDetail.set({
          ...openDetail,
          results: updatedResults
        });
      }
    }

    // 6. Patch Forwarded Patient Transfers Ledger
    if (Array.isArray(payload.forwardedPatients)) {
      this.forwardedPatients.set(payload.forwardedPatients);
    } else if (payload.updatedStudy && payload.updatedStudy.id) {
      const st = payload.updatedStudy;
      this.forwardedPatients.update((rows) => {
        const hasStudy = rows.some((r) => r.studyId === st.id);
        if (hasStudy) {
          return rows.map((r) =>
            r.studyId === st.id
              ? {
                  ...r,
                  patientName: st.patientName
                    ? st.patientName.replace(/\^/g, ' ').trim()
                    : r.patientName,
                  studyDescription: st.studyDescription || r.studyDescription,
                  canonicalRegion: st.canonicalRegion || r.canonicalRegion,
                  bodyPartExamined: st.bodyPartExamined || r.bodyPartExamined,
                  vendorName: r.jobId ? r.vendorName : `Not Forwarded (${st.lifecycleState})`,
                  totalStudyInstances: st.instanceCount,
                  transferStatus: r.jobId ? r.transferStatus : st.lifecycleState
                }
              : r
          );
        }
        const unroutedRow: ForwardedPatientTransfer = {
          studyId: st.id,
          siteId: st.siteId,
          patientId: st.patientId || 'UNKNOWN',
          patientName: (st.patientName || 'KAUVERY PATIENT').replace(/\^/g, ' ').trim(),
          patientAge: st.patientAge || '',
          patientSex: st.patientSex || '',
          accessionNumber: st.accessionNumber || '',
          pseudoPatientId: '—',
          modality: st.modality,
          studyDescription: st.studyDescription,
          canonicalRegion: st.canonicalRegion || `${st.modality} Study`,
          bodyPartExamined: st.bodyPartExamined,
          sourceType: st.sourceType,
          callingAeTitle: st.callingAeTitle,
          vendorCode: '—',
          vendorName: `Not Forwarded (${st.lifecycleState})`,
          destinationType: 'NOT_FORWARDED',
          destinationAeTitle: '—',
          destinationHost: '—',
          destinationPort: 0,
          connectorType: '—',
          totalStudyInstances: st.instanceCount,
          selectedInstances: 0,
          transferredInstances: 0,
          uploadedBytes: 0,
          transferStatus: st.lifecycleState,
          createdAt: st.createdAt
        };
        return [unroutedRow, ...rows];
      });
    }

    // 7. Patch Vendor Delivery Matrix Metrics (respecting RBAC config visibility)
    if (Array.isArray(payload.vendorMetrics)) {
      const canView = this.canViewDicomConfig();
      this.vendorMetrics.set(
        canView
          ? payload.vendorMetrics
          : payload.vendorMetrics.map((m) => ({
              ...m,
              destinationAeTitle: 'RESTRICTED',
              destinationHost: 'RESTRICTED',
              destinationPort: 0
            }))
      );
    }

    // 8. Patch RIS Uploaded PDF Reports
    if (Array.isArray(payload.risUploadedReports)) {
      this.risUploadedReports.set(payload.risUploadedReports);
    }

    // 9. Patch Active Operational & Privacy Alerts
    if (Array.isArray(payload.alerts)) {
      this.alerts.set(payload.alerts);
    }

    // 10. Patch Audit Trail Events
    if (payload.latestAuditEvent && payload.latestAuditEvent.id) {
      const audit = payload.latestAuditEvent;
      this.auditEvents.update((list) => [
        audit,
        ...list.filter((a) => a.id !== audit.id)
      ]);
    }
  }

  onSiteChange(newSiteId: string): void {
    this.selectedSiteId.set(newSiteId);
    this.selectedStudyDetail.set(null);
    this.activeModal.set(null);
    this.refreshAll();
    this.connectLiveSseStream();
    this.pushToast('info', 'Hospital Site Switched', `Switched tenant context to site ${newSiteId}`);
  }

  navigateToPage(page: MainPage): void {
    this.activePage.set(page);
    this.universalSearchOpen.set(false);
    this.refreshPageData(page);
  }

  refreshPageData(page: MainPage): void {
    const sid = this.selectedSiteId();
    const q = `?siteId=${sid}`;
    const hdrs = this.authHeaders();

    switch (page) {
      case 'dashboard':
        this.refreshLiveTelemetrySilent();
        this.http.get<VendorMetricRow[]>(`${this.apiBase}/dashboard/vendor-metrics${q}`, hdrs).subscribe({
          next: (data) => this.vendorMetrics.set(data),
          error: () => {}
        });
        this.http.get<ForwardedPatientTransfer[]>(`${this.apiBase}/forwarded-patients${q}`).subscribe({
          next: (data) => this.forwardedPatients.set(data),
          error: () => {}
        });
        this.http.get<OperationalAlert[]>(`${this.apiBase}/alerts${q}`).subscribe({
          next: (data) => this.alerts.set(data),
          error: () => {}
        });
        break;

      case 'studies':
        this.http.get<StudyRecord[]>(`${this.apiBase}/studies${q}`).subscribe({
          next: (data) => this.studies.set(data),
          error: () => {}
        });
        this.http.get<ForwardedPatientTransfer[]>(`${this.apiBase}/forwarded-patients${q}`).subscribe({
          next: (data) => this.forwardedPatients.set(data),
          error: () => {}
        });
        break;

      case 'queue-results':
        this.http.get<VendorDeliveryJob[]>(`${this.apiBase}/jobs${q}`).subscribe({
          next: (data) => this.jobs.set(data),
          error: () => {}
        });
        this.http.get<AiResultRecord[]>(`${this.apiBase}/results${q}`).subscribe({
          next: (data) => this.aiResults.set(data),
          error: () => {}
        });
        this.http.get<RisUploadedReport[]>(`${this.apiBase}/ris-reports${q}`).subscribe({
          next: (data) => this.risUploadedReports.set(data),
          error: () => {}
        });
        this.http.get<CleanupPolicy[]>(`${this.apiBase}/cleanup-policies${q}`).subscribe({
          next: (data) => this.cleanupPolicies.set(data),
          error: () => {}
        });
        this.http.get<LlmApprovedModel[]>(`${this.apiBase}/llm/models${q}`).subscribe({
          next: (data) => this.llmModels.set(data),
          error: () => {}
        });
        this.http.get<LlmPromptTemplate[]>(`${this.apiBase}/llm/prompt-templates${q}`).subscribe({
          next: (data) => this.llmTemplates.set(data),
          error: () => {}
        });
        this.loadRetentionSummary();
        break;

      case 'retention':
        this.loadRetentionSummary();
        this.http.get<StudyRecord[]>(`${this.apiBase}/studies${q}`).subscribe({
          next: (data) => this.studies.set(data),
          error: () => {}
        });
        this.http.get<CleanupPolicy[]>(`${this.apiBase}/cleanup-policies${q}`).subscribe({
          next: (data) => this.cleanupPolicies.set(data),
          error: () => {}
        });
        break;

      case 'dicom-config':
        if (this.canViewDicomConfig()) {
          this.http.get<DicomNodeSettings>(`${this.apiBase}/dicom-settings${q}`, hdrs).subscribe({
            next: (data) => this.dicomSettings.set(data),
            error: () => {}
          });
          this.http.get<DicomSource[]>(`${this.apiBase}/sources${q}`, hdrs).subscribe({
            next: (data) => this.sources.set(data),
            error: () => {}
          });
          this.http.get<PacsDestination[]>(`${this.apiBase}/pacs-destinations${q}`, hdrs).subscribe({
            next: (data) => this.pacsDestinations.set(data),
            error: () => {}
          });
          this.http.get<VendorConfig[]>(`${this.apiBase}/vendors${q}`, hdrs).subscribe({
            next: (data) => {
              this.vendors.set(data);
              const aiVendor = data.find((v) => v.destinationType !== 'DICOM_ROUTER') || data[0];
              if (aiVendor) {
                this.quickAgentNameInput = aiVendor.agentName || aiVendor.vendorName || 'Grok AI Vision Agent';
              }
            },
            error: () => {}
          });
          this.http.get<RoutingRule[]>(`${this.apiBase}/routing-rules${q}`, hdrs).subscribe({
            next: (data) => this.routingRules.set(data),
            error: () => {}
          });
          this.http.get<StudyRegionMapping[]>(`${this.apiBase}/region-mappings${q}`, hdrs).subscribe({
            next: (data) => this.regionMappings.set(data),
            error: () => {}
          });
          this.http.get<DeidProfile[]>(`${this.apiBase}/deid-profiles${q}`, hdrs).subscribe({
            next: (data) => this.deidProfiles.set(data),
            error: () => {}
          });
          this.http.get<RisReportEndpoint>(`${this.apiBase}/ris-endpoints${q}`, hdrs).subscribe({
            next: (data) => {
              this.risEndpoint.set(data);
              this.risEndpointForm = { ...data };
            },
            error: () => {}
          });
        }
        this.http.get<RisUploadedReport[]>(`${this.apiBase}/ris-reports${q}`).subscribe({
          next: (data) => this.risUploadedReports.set(data),
          error: () => {}
        });
        break;

      case 'users':
        this.http.get<HubUser[]>(`${this.apiBase}/users${q}`).subscribe({
          next: (data) => this.users.set(data),
          error: () => {}
        });
        break;

      case 'audit':
        this.http.get<OperationalAlert[]>(`${this.apiBase}/alerts${q}`).subscribe({
          next: (data) => this.alerts.set(data),
          error: () => {}
        });
        this.http.get<AuditEvent[]>(`${this.apiBase}/audit-events${q}`).subscribe({
          next: (data) => this.auditEvents.set(data),
          error: () => {}
        });
        break;
    }
  }

  refreshLiveTelemetrySilent(): void {
    const sid = this.selectedSiteId();
    const q = `?siteId=${sid}`;
    this.http.get<DashboardOverview>(`${this.apiBase}/dashboard/overview${q}`).subscribe({
      next: (data) => this.overview.set(data),
      error: () => {}
    });
    this.http.get<StudyRecord[]>(`${this.apiBase}/studies${q}`).subscribe({
      next: (data) => {
        this.studies.set(data);
        if (data.length > 0) {
          const currId = this.selectedAnonymizationStudyId();
          const exists = currId && data.some((s) => s.id === currId);
          if (!exists) {
            this.selectedAnonymizationStudyId.set(data[0].id);
          }
        }
      },
      error: () => {}
    });
    this.http.get<LiveIncomingStudyEvent[]>(`${this.apiBase}/live/recent-events${q}`).subscribe({
      next: (data) => {
        if (data && data.length > 0) {
          this.liveEvents.set(data);
        }
      },
      error: () => {}
    });
  }

  refreshAll(): void {
    const sid = this.selectedSiteId();
    const q = `?siteId=${sid}`;

    const hdrs = this.authHeaders();
    this.refreshLiveTelemetrySilent();
    this.http.get<VendorMetricRow[]>(`${this.apiBase}/dashboard/vendor-metrics${q}`, hdrs).subscribe({
      next: (data) => this.vendorMetrics.set(data),
      error: () => {}
    });
    this.http.get<ForwardedPatientTransfer[]>(`${this.apiBase}/forwarded-patients${q}`).subscribe({
      next: (data) => this.forwardedPatients.set(data),
      error: () => {}
    });
    this.http.get<VendorDeliveryJob[]>(`${this.apiBase}/jobs${q}`).subscribe({
      next: (data) => this.jobs.set(data),
      error: () => {}
    });
    this.http.get<AiResultRecord[]>(`${this.apiBase}/results${q}`).subscribe({
      next: (data) => this.aiResults.set(data),
      error: () => {}
    });
    if (this.canViewDicomConfig()) {
      const hdrs = this.authHeaders();
      this.http.get<RoutingRule[]>(`${this.apiBase}/routing-rules${q}`, hdrs).subscribe({
        next: (data) => this.routingRules.set(data),
        error: () => {}
      });
      this.http.get<StudyRegionMapping[]>(`${this.apiBase}/region-mappings${q}`, hdrs).subscribe({
        next: (data) => this.regionMappings.set(data),
        error: () => {}
      });
      this.http.get<VendorConfig[]>(`${this.apiBase}/vendors${q}`, hdrs).subscribe({
        next: (data) => {
          this.vendors.set(data);
          if (data.length > 0) {
            this.quickAgentNameInput = data[0].agentName || data[0].vendorName || 'Grok AI Vision Agent';
          }
        },
        error: () => {}
      });
      this.http.get<DicomSource[]>(`${this.apiBase}/sources${q}`, hdrs).subscribe({
        next: (data) => this.sources.set(data),
        error: () => {}
      });
      this.http.get<PacsDestination[]>(`${this.apiBase}/pacs-destinations${q}`, hdrs).subscribe({
        next: (data) => this.pacsDestinations.set(data),
        error: () => {}
      });
      this.http.get<DicomNodeSettings>(`${this.apiBase}/dicom-settings${q}`, hdrs).subscribe({
        next: (data) => this.dicomSettings.set(data),
        error: () => {}
      });
      this.http.get<DeidProfile[]>(`${this.apiBase}/deid-profiles${q}`, hdrs).subscribe({
        next: (data) => this.deidProfiles.set(data),
        error: () => {}
      });
      this.http.get<RisReportEndpoint>(`${this.apiBase}/ris-endpoints${q}`, hdrs).subscribe({
        next: (data) => {
          this.risEndpoint.set(data);
          this.risEndpointForm = { ...data };
        },
        error: () => {}
      });
    } else {
      this.routingRules.set([]);
      this.regionMappings.set([]);
      this.vendors.set([]);
      this.sources.set([]);
      this.pacsDestinations.set([]);
      this.deidProfiles.set([]);
    }
    this.http.get<RisUploadedReport[]>(`${this.apiBase}/ris-reports${q}`).subscribe({
      next: (data) => this.risUploadedReports.set(data),
      error: () => {}
    });
    this.http.get<HubUser[]>(`${this.apiBase}/users${q}`).subscribe({
      next: (data) => {
        this.users.set(data);
        const curr = this.currentUser();
        const matched = data.find((u) => u.id === curr.id || u.username === curr.username);
        if (matched) {
          this.currentUser.set(matched);
        } else if (data.length > 0 && curr.siteId && curr.siteId !== sid) {
          this.currentUser.set(data[0]);
        }
      },
      error: () => {}
    });
    this.http.get<CleanupPolicy[]>(`${this.apiBase}/cleanup-policies${q}`).subscribe({
      next: (data) => this.cleanupPolicies.set(data),
      error: () => {}
    });
    this.http.get<OperationalAlert[]>(`${this.apiBase}/alerts${q}`).subscribe({
      next: (data) => this.alerts.set(data),
      error: () => {}
    });
    this.http.get<LlmApprovedModel[]>(`${this.apiBase}/llm/models${q}`).subscribe({
      next: (data) => this.llmModels.set(data),
      error: () => {}
    });
    this.http.get<LlmPromptTemplate[]>(`${this.apiBase}/llm/prompt-templates${q}`).subscribe({
      next: (data) => this.llmTemplates.set(data),
      error: () => {}
    });
    this.http.get<AuditEvent[]>(`${this.apiBase}/audit-events${q}`).subscribe({
      next: (data) => this.auditEvents.set(data),
      error: () => {}
    });
    this.loadRetentionSummary();
  }

  // =========================================================================
  // 1. Universal Search with Fuzzy Matching Option
  // =========================================================================

  onUniversalSearchInput(query: string): void {
    this.universalQuery.set(query);
    if (!query || query.trim().length === 0) {
      this.universalSearchHits.set([]);
      this.universalSearchOpen.set(false);
      return;
    }
    this.universalSearchOpen.set(true);
    this.executeUniversalSearch();
  }

  toggleUniversalFuzzy(): void {
    this.universalFuzzyEnabled.set(!this.universalFuzzyEnabled());
    if (this.universalQuery().trim().length > 0) {
      this.executeUniversalSearch();
    }
    this.pushToast(
      'info',
      `Fuzzy Matching ${this.universalFuzzyEnabled() ? 'Enabled' : 'Disabled'}`,
      this.universalFuzzyEnabled()
        ? 'Universal search now matches typos & phonetic scanner variations (e.g., Strock, Brai, AMUTA)'
        : 'Universal search set to strict exact/substring matching only'
    );
  }

  runQuickUniversalSearch(sampleQuery: string): void {
    this.universalQuery.set(sampleQuery);
    this.universalSearchOpen.set(true);
    this.executeUniversalSearch();
  }

  executeUniversalSearch(): void {
    const q = this.universalQuery().trim();
    if (!q) {
      this.universalSearchHits.set([]);
      return;
    }
    const sid = this.selectedSiteId();
    const fuzzy = this.universalFuzzyEnabled();
    this.universalSearchLoading.set(true);
    this.http
      .get<any>(`${this.apiBase}/search/universal?siteId=${sid}&q=${encodeURIComponent(q)}&fuzzy=${fuzzy}`, this.authHeaders())
      .subscribe({
        next: (res) => {
          this.universalSearchHits.set(res.hits || []);
          this.universalSearchLoading.set(false);
        },
        error: () => {
          this.universalSearchLoading.set(false);
        }
      });
  }

  selectUniversalSearchHit(hit: UniversalSearchHit): void {
    this.universalSearchOpen.set(false);
    const target = (hit.targetSubpage || 'dashboard') as MainPage;
    this.navigateToPage(target);
    if (hit.category === 'STUDY') {
      this.inspectStudyDetails(hit.entityId);
    } else if (hit.category === 'DICOM_SOURCE') {
      this.dicomConfigTab.set('sources');
    } else if (hit.category === 'DESTINATION_VENDOR') {
      this.dicomConfigTab.set('vendors');
    } else if (hit.category === 'ROUTING_RULE') {
      this.dicomConfigTab.set('rules');
    }
    this.pushToast(
      'info',
      `Navigated to ${hit.category}`,
      `${hit.title} (${hit.matchType} · ${Math.round(hit.similarityScore * 100)}% match on ${hit.matchedField})`
    );
  }

  private clientFuzzyMatch(query: string, haystack: string): boolean {
    const qTokens = query.split(/\s+/).filter((t) => t.length > 0);
    const hTokens = haystack.split(/[\s,._\-/:]+/).filter((t) => t.length > 0);
    return qTokens.every((qt) =>
      hTokens.some((ht) => {
        if (ht.includes(qt) || (ht.length >= 3 && qt.includes(ht))) return true;
        if (qt.length < 3 || ht.length < 3) return false;
        const dist = this.levenshtein(qt, ht);
        const maxLen = Math.max(qt.length, ht.length);
        return 1 - dist / maxLen >= 0.6;
      })
    );
  }

  private levenshtein(a: string, b: string): number {
    const dp: number[][] = Array.from({ length: a.length + 1 }, () => Array(b.length + 1).fill(0));
    for (let i = 0; i <= a.length; i++) dp[i][0] = i;
    for (let j = 0; j <= b.length; j++) dp[0][j] = j;
    for (let i = 1; i <= a.length; i++) {
      for (let j = 1; j <= b.length; j++) {
        const cost = a[i - 1] === b[j - 1] ? 0 : 1;
        dp[i][j] = Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost);
      }
    }
    return dp[a.length][b.length];
  }

  // =========================================================================
  // 2. Live Incoming Study & Image-Count Streaming Simulator (Req 2.1 / FR-46)
  // =========================================================================

  openLiveStreamModal(): void {
    this.activeModal.set('live-stream-sim');
  }

  /**
   * Progressive slice-by-slice incoming RIS-PACS study stream so the administrator can
   * watch the study in 'Receiving' status while the per-study image count, Images Received Today,
   * and Total Images Received increment automatically in real time without manual refresh.
   */
  runProgressiveLiveRisPacsStream(
    sourceType: 'RIS_PACS' | 'MODALITY' = 'RIS_PACS',
    studyDesc = 'X-RAY CHEST PA VIEW',
    totalSlices = 3,
    finalStatus: 'RECEIVING' | 'COMPLETED' | 'INCOMPLETE' | 'FAILED' = 'COMPLETED'
  ): void {
    if (this.streamingInProgress()) return;
    this.streamingInProgress.set(true);
    const sid = this.selectedSiteId();
    const callingAe = sourceType === 'RIS_PACS' ? 'TEAMPACS' : 'KAUVERY_XR01';
    const patientId = 'MR' + Math.floor(100000 + Math.random() * 899999);
    const patientName = sourceType === 'RIS_PACS' ? 'BALAKRISHNAN^K (LIVE PACS)' : 'MUTHUKUMAR^S (LIVE XRAY)';
    const sharedStudyUid = `2.25.${Date.now()}${Math.floor(Math.random() * 9000 + 1000)}`;

    this.pushToast(
      'info',
      `Live Incoming Study Started (${sourceType})`,
      `Receiving ${totalSlices} DICOM instances from ${callingAe} for ${patientName} (${studyDesc})...`
    );

    let currentSlice = 0;
    const sendNextSlice = () => {
      currentSlice++;
      const isLast = currentSlice >= totalSlices;
      const stepStatus = isLast ? finalStatus : 'RECEIVING';
      const payload = {
        sourceType,
        callingAeTitle: callingAe,
        patientId,
        patientName,
        modality: 'DX',
        studyDescription: studyDesc,
        bodyPartExamined: 'CHEST',
        sliceCount: 1,
        includeDuplicateRetransmission: false,
        finalReceivingStatus: stepStatus,
        studyInstanceUid: sharedStudyUid
      };
      this.http.post<any>(`${this.apiBase}/simulator/stream-incoming-study?siteId=${sid}`, payload).subscribe({
        next: (res) => {
          this.recentlyUpdatedStudyId.set(res.studyId);
          if (!this.liveStreamConnected()) {
            this.refreshLiveTelemetrySilent();
          }
          if (!isLast) {
            setTimeout(sendNextSlice, 550);
          } else {
            this.streamingInProgress.set(false);
            if (!this.liveStreamConnected()) {
              this.refreshAll();
            }
            this.pushToast(
              finalStatus === 'FAILED' ? 'error' : finalStatus === 'INCOMPLETE' ? 'warning' : 'success',
              `Study Reception ${res.receivingStatus} (${res.sourceType})`,
              `Committed ${res.uniqueStoredInstances} unique DICOM instances from ${res.callingAeTitle} to Original Repository.`
            );
          }
        },
        error: () => {
          this.streamingInProgress.set(false);
        }
      });
    };
    sendNextSlice();
  }

  submitCustomLiveStreamModal(): void {
    const sid = this.selectedSiteId();
    this.activeModal.set(null);
    this.http
      .post<any>(`${this.apiBase}/simulator/stream-incoming-study?siteId=${sid}`, this.liveStreamSimForm)
      .subscribe({
        next: (res) => {
          this.recentlyUpdatedStudyId.set(res.studyId);
          this.pushToast(
            res.receivingStatus === 'FAILED' ? 'error' : res.receivingStatus === 'INCOMPLETE' ? 'warning' : 'success',
            `Live Incoming Study (${res.sourceType} · ${res.receivingStatus})`,
            `Patient ${res.patientName}: ${res.uniqueStoredInstances} unique stored instances, ${res.receivedTransferEvents} transfer events (${res.duplicateInstancesSkipped} duplicate skipped).`
          );
          this.refreshAll();
        },
        error: (err) => {
          this.pushToast('error', 'Live Stream Simulation Error', err?.error?.message || 'Failed to stream incoming study');
        }
      });
  }

  simulateDuplicateInstanceRetransmission(study: StudyRecord): void {
    const sid = this.selectedSiteId();
    this.http
      .post<any>(`${this.apiBase}/studies/${study.id}/simulate-retransmission?siteId=${sid}`, {})
      .subscribe({
        next: (res) => {
          this.recentlyUpdatedStudyId.set(study.id);
          this.pushToast(
            'warning',
            'Duplicate Instance Retransmission Blocked from Double-Counting',
            res.message
          );
          this.refreshAll();
        },
        error: (err) => {
          this.pushToast('error', 'Retransmission Test Failed', err?.error?.message || 'Could not retransmit instance');
        }
      });
  }

  openStudyReceivingStatusModal(study: StudyRecord): void {
    this.studyStatusForm = {
      studyId: study.id,
      patientName: study.patientName || study.patientId || study.studyInstanceUid,
      receivingStatus: this.resolveReceivingStatus(study),
      reason: study.validationError || ''
    };
    this.activeModal.set('study-receiving-status');
  }

  saveStudyReceivingStatusModal(): void {
    const sid = this.selectedSiteId();
    const { studyId, receivingStatus, reason } = this.studyStatusForm;
    this.http
      .post<StudyRecord>(
        `${this.apiBase}/studies/${studyId}/receiving-status?siteId=${sid}&receivingStatus=${receivingStatus}&reason=${encodeURIComponent(reason || '')}`,
        {}
      )
      .subscribe({
        next: (updated) => {
          this.activeModal.set(null);
          this.pushToast(
            'success',
            `Study Receiving Status → ${updated.receivingStatus}`,
            `Updated study ${updated.patientName || updated.id} to ${updated.receivingStatus}`
          );
          this.refreshAll();
        },
        error: (err) => {
          this.pushToast('error', 'Status Update Failed', err?.error?.message || 'Could not update receiving status');
        }
      });
  }

  resolveReceivingStatus(study: StudyRecord): string {
    if (study.receivingStatus === 'ON_HOLD') return 'ON_HOLD';
    if (
      study.lifecycleState === 'UNMATCHED_NO_RULE' &&
      study.receivingStatus !== 'RECEIVING' &&
      study.receivingStatus !== 'FAILED' &&
      study.receivingStatus !== 'INCOMPLETE'
    ) {
      return 'ON_HOLD';
    }
    if (study.receivingStatus) return study.receivingStatus;
    if (study.lifecycleState === 'WAITING_FOR_COMPLETION' || study.lifecycleState === 'RECEIVED') return 'RECEIVING';
    if (study.lifecycleState === 'VALIDATION_FAILED' || study.lifecycleState === 'FAILED') return 'FAILED';
    if (study.lifecycleState === 'INCOMPLETE') return 'INCOMPLETE';
    return 'COMPLETED';
  }

  isStudyOnHoldOrUnmatched(study?: StudyRecord | null): boolean {
    if (!study) return false;
    return this.resolveReceivingStatus(study) === 'ON_HOLD' || study.lifecycleState === 'UNMATCHED_NO_RULE';
  }

  formatPatientName(name?: string | null): string {
    if (!name || !name.trim()) return 'UNKNOWN PATIENT';
    return name.replace(/\^+/g, ' ').replace(/\s+/g, ' ').trim();
  }

  formatPatientAge(age?: string | null): string {
    if (!age || !age.trim()) return '--';
    const trimmed = age.trim().toUpperCase();
    const match = trimmed.match(/^0*(\d+)([YMWD]?)$/);
    if (match) {
      return `${match[1]}${match[2] || 'Y'}`;
    }
    return trimmed;
  }

  formatPatientGender(sex?: string | null): string {
    if (!sex || !sex.trim()) return '--';
    const s = sex.trim().toUpperCase();
    if (s === 'M' || s === 'MALE') return 'M';
    if (s === 'F' || s === 'FEMALE') return 'F';
    if (s === 'O' || s === 'OTHER') return 'O';
    return s;
  }

  getSelectedAgentForStudy(studyId: string): string {
    return this.selectedAgentByStudyId()[studyId] || '';
  }

  setSelectedAgentForStudy(studyId: string, vendorId: string): void {
    this.selectedAgentByStudyId.update((map) => ({
      ...map,
      [studyId]: vendorId
    }));
  }

  sendStudyToSelectedAgent(study: StudyRecord, explicitVendorId?: string): void {
    if (!study || !study.id) return;
    const chosenId = (explicitVendorId ?? this.getSelectedAgentForStudy(study.id) ?? '').trim();
    const agents = this.availableAgentsForDropdown();
    const targetVendorId = chosenId || (agents.length === 1 ? agents[0].id || '' : '');
    if (!targetVendorId) {
      this.pushToast(
        'warning',
        'Select AI Agent / Destination',
        `Please select an AI Agent or DICOM Router from the dropdown for patient ${this.formatPatientName(study.patientName)}.`
      );
      return;
    }
    const sid = this.selectedSiteId();
    const targetAgent = agents.find((a) => a.id === targetVendorId);
    const agentLabel = targetAgent ? targetAgent.agentName || targetAgent.vendorName || targetAgent.vendorCode : targetVendorId;
    this.sendingToAgentStudyId.set(study.id);
    this.http
      .post<any>(
        `${this.apiBase}/studies/${study.id}/route?siteId=${sid}&forceResend=true&vendorId=${encodeURIComponent(targetVendorId)}`,
        {},
        this.authHeaders()
      )
      .subscribe({
        next: () => {
          this.sendingToAgentStudyId.set(null);
          this.pushToast(
            'success',
            `Study Sent to ${agentLabel}`,
            `Validated, PS3.15 de-identified, and forwarded ${this.formatPatientName(study.patientName)} (${study.modality} · ${study.studyDescription || 'Study'}) to ${agentLabel}.`
          );
          this.refreshAll();
        },
        error: (err) => {
          this.sendingToAgentStudyId.set(null);
          this.handleApiError('Manual Send to AI Agent Failed', err);
        }
      });
  }

  triggerSimulation(
    scenario:
      | 'ct-kub'
      | 'ct-brain'
      | 'ct-brain-head-protocol'
      | 'ct-brain-brai-injury'
      | 'ct-brain-strock-protocol'
      | 'mri-brain-strock-protocol'
      | 'mri-brain-shadow'
      | 'burned-in'
      | 'transient-outage'
      | 'invalid-study'
  ): void {
    const sid = this.selectedSiteId();
    this.http.post<any>(`${this.apiBase}/simulator/run-scenario?siteId=${sid}&scenario=${scenario}`, {}).subscribe({
      next: (res) => {
        this.recentlyUpdatedStudyId.set(res.studyId);
        this.pushToast(
          scenario === 'burned-in' || scenario === 'invalid-study' ? 'warning' : 'success',
          `Executed Scenario '${scenario}'`,
          `Committed Study UID ${res.studyInstanceUid} (${res.jobsCreated} forwarding job(s) processed)`
        );
        this.refreshAll();
      },
      error: () => this.refreshAll()
    });
  }

  // =========================================================================
  // 3. AE Title Echo & Test Connections (Req 5 & Req 6)
  // =========================================================================

  openAdHocCEchoModal(preset?: { callingAe?: string; calledAe: string; host: string; port: number; name: string }): void {
    if (preset) {
      this.adHocEchoForm = {
        callingAeTitle: preset.callingAe || this.dicomSettings().localAeTitle || 'DICOMHUB_AE',
        calledAeTitle: preset.calledAe,
        hostAddress: preset.host,
        dicomPort: preset.port,
        nodeName: preset.name
      };
    }
    this.activeModal.set('cecho-diagnostic');
    this.runAdHocCEchoTest();
  }

  runAdHocCEchoTest(): void {
    const sid = this.selectedSiteId();
    this.testingNodeId.set('ADHOC');
    this.http
      .post<CEchoDiagnosticResult>(`${this.apiBase}/dicom/c-echo?siteId=${sid}`, this.adHocEchoForm, this.authHeaders())
      .subscribe({
        next: (res) => {
          this.testingNodeId.set(null);
          this.lastCEchoDiagnostic.set(res);
          this.pushToast(
            res.success ? 'success' : 'error',
            res.success ? `DICOM C-ECHO Verified (${res.latencyMs} ms)` : 'DICOM C-ECHO Failed',
            res.message
          );
          this.refreshAll();
        },
        error: (err) => {
          this.testingNodeId.set(null);
          this.pushToast('error', 'DICOM C-ECHO Error', err?.error?.message || 'C-ECHO verification request failed');
        }
      });
  }

  testLocalScpEcho(): void {
    const sid = this.selectedSiteId();
    this.testingNodeId.set('LOCAL_SCP');
    this.http
      .post<CEchoDiagnosticResult>(`${this.apiBase}/dicom-settings/test-local-echo?siteId=${sid}`, {}, this.authHeaders())
      .subscribe({
        next: (res) => {
          this.testingNodeId.set(null);
          this.lastCEchoDiagnostic.set(res);
          this.nodeTestResults.update((m) => ({
            ...m,
            LOCAL_SCP: { ok: res.success, latencyMs: res.latencyMs, status: res.dimseStatus, message: res.message }
          }));
          this.pushToast(
            res.success ? 'success' : 'error',
            `Local SCP C-ECHO (${res.calledAeTitle}:${res.port})`,
            res.message
          );
          this.refreshAll();
        },
        error: (err) => {
          this.testingNodeId.set(null);
          this.pushToast('error', 'Local SCP C-ECHO Failed', err?.error?.message || 'Failed to echo local SCP');
        }
      });
  }

  testSourceAssociation(sourceId?: string): void {
    if (!sourceId) return;
    const sid = this.selectedSiteId();
    this.testingNodeId.set(sourceId);
    this.http
      .post<any>(`${this.apiBase}/sources/${sourceId}/test-connection?siteId=${sid}`, {}, this.authHeaders())
      .subscribe({
        next: (res) => {
          this.testingNodeId.set(null);
          this.nodeTestResults.update((m) => ({
            ...m,
            [sourceId]: {
              ok: !!res.cEchoSuccess,
              latencyMs: res.latencyMs || 2,
              status: res.dimseStatus || '0x0000',
              message: res.message
            }
          }));
          this.pushToast(
            res.cEchoSuccess ? 'success' : 'error',
            `Source C-ECHO: ${res.callingAeTitle}`,
            res.message
          );
          this.refreshAll();
        },
        error: (err) => {
          this.testingNodeId.set(null);
          this.pushToast('error', 'Source C-ECHO Error', err?.error?.message || 'Association test failed');
        }
      });
  }

  testPacsDestinationConnection(destId?: string): void {
    if (!destId) return;
    const sid = this.selectedSiteId();
    this.testingNodeId.set(destId);
    this.http
      .post<any>(`${this.apiBase}/pacs-destinations/${destId}/test-connection?siteId=${sid}`, {}, this.authHeaders())
      .subscribe({
        next: (res) => {
          this.testingNodeId.set(null);
          this.nodeTestResults.update((m) => ({
            ...m,
            [destId]: {
              ok: !!res.cEchoSuccess,
              latencyMs: res.latencyMs || 2,
              status: res.dimseStatus || '0x0000',
              message: res.message
            }
          }));
          this.pushToast(
            res.cEchoSuccess ? 'success' : 'error',
            `PACS Destination C-ECHO: ${res.aeTitle}`,
            res.message
          );
          this.refreshAll();
        },
        error: (err) => {
          this.testingNodeId.set(null);
          this.pushToast('error', 'PACS Connection Test Error', err?.error?.message || 'Connection test failed');
        }
      });
  }

  testVendorConnection(vendorId?: string): void {
    if (!vendorId) return;
    const sid = this.selectedSiteId();
    this.testingNodeId.set(vendorId);
    this.http
      .post<any>(`${this.apiBase}/vendors/${vendorId}/test-connection?siteId=${sid}`, {}, this.authHeaders())
      .subscribe({
        next: (res) => {
          this.testingNodeId.set(null);
          this.nodeTestResults.update((m) => ({
            ...m,
            [vendorId]: {
              ok: !!res.connected,
              latencyMs: res.latencyMs || 3,
              status: res.dimseStatus || '0x0000',
              message: res.message
            }
          }));
          this.pushToast(
            res.connected ? 'success' : 'error',
            `Destination Test: ${res.destinationAeTitle || res.vendorCode}`,
            res.message
          );
          this.refreshAll();
        },
        error: (err) => {
          this.testingNodeId.set(null);
          this.pushToast('error', 'Vendor Connection Error', err?.error?.message || 'Connection test failed');
        }
      });
  }

  // =========================================================================
  // 4. DICOM Configuration Center CRUD via Popup Modals & RBAC (Req 2.2, 4, 7)
  // =========================================================================

  private checkPermissionOrToast(perm: 'VIEW' | 'CREATE' | 'EDIT' | 'DELETE', settingLabel: string): boolean {
    const allowed =
      perm === 'VIEW'
        ? this.canViewDicomConfig()
        : perm === 'CREATE'
          ? this.canCreateDicomConfig()
          : perm === 'EDIT'
            ? this.canEditDicomConfig()
            : this.canDeleteDicomConfig();
    if (!allowed) {
      const u = this.currentUser();
      this.pushToast(
        'error',
        `Access Denied (${u.userRole})`,
        `User '${u.fullName}' (@${u.username}) is not authorised to ${perm} DICOM configuration (${settingLabel}).`
      );
      return false;
    }
    return true;
  }

  // 4a. Local AE & Association Settings Modal
  openEditLocalAeModal(): void {
    if (!this.checkPermissionOrToast('EDIT', 'Local AE & Connectivity Settings')) return;
    this.localAeForm = { ...this.dicomSettings() };
    this.activeModal.set('local-ae');
  }

  saveLocalAeModal(): void {
    const sid = this.selectedSiteId();
    this.http
      .put<DicomNodeSettings>(`${this.apiBase}/dicom-settings?siteId=${sid}`, this.localAeForm, this.authHeaders())
      .subscribe({
        next: (saved) => {
          this.dicomSettings.set(saved);
          this.activeModal.set(null);
          this.pushToast(
            'success',
            'Local AE & Connectivity Updated',
            `Saved Local AE '${saved.localAeTitle}' @ ${saved.bindIpAddress}:${saved.localPort} (Audit logged by ${this.currentUser().username})`
          );
          this.refreshAll();
        },
        error: (err) => this.handleApiError('Local AE Update Blocked', err)
      });
  }

  // 4b. Modality & RIS-PACS Source Modal
  openCreateSourceModal(): void {
    if (!this.checkPermissionOrToast('CREATE', 'Modality / RIS-PACS Source')) return;
    this.modalEditMode.set(false);
    this.sourceForm = this.emptySource();
    this.activeModal.set('source');
  }

  openEditSourceModal(src: DicomSource): void {
    if (!this.checkPermissionOrToast('EDIT', `DicomSource ${src.callingAeTitle}`)) return;
    this.modalEditMode.set(true);
    this.sourceForm = { ...src };
    this.activeModal.set('source');
  }

  onSourceTypeChange(sourceType: string): void {
    this.sourceForm.sourceType = sourceType;
    if (!this.modalEditMode()) {
      this.sourceForm.quietPeriodSeconds = sourceType === 'RIS_PACS' ? 60 : 120;
    }
  }

  saveSourceModal(): void {
    const sid = this.selectedSiteId();
    const isEdit = this.modalEditMode() && this.sourceForm.id;
    const payload: DicomSource = {
      ...this.sourceForm,
      calledAeTitle: (this.sourceForm.calledAeTitle || this.dicomSettings().localAeTitle || 'DICOMHUB_AE').trim(),
      dicomPort: Number(this.sourceForm.dicomPort) || 11112,
      quietPeriodSeconds: Number(this.sourceForm.quietPeriodSeconds) || 60
    };
    const req$ = isEdit
      ? this.http.put<DicomSource>(`${this.apiBase}/sources/${this.sourceForm.id}?siteId=${sid}`, payload, this.authHeaders())
      : this.http.post<DicomSource>(`${this.apiBase}/sources?siteId=${sid}`, payload, this.authHeaders());
    req$.subscribe({
      next: (saved) => {
        this.activeModal.set(null);
        this.pushToast(
          'success',
          isEdit ? 'DICOM Source Updated' : 'DICOM Source Added',
          `${saved.sourceName} (${saved.sourceType} · AE: ${saved.callingAeTitle} @ ${saved.hostAddress}:${saved.dicomPort})`
        );
        this.refreshAll();
      },
      error: (err) => this.handleApiError('DICOM Source Save Failed', err)
    });
  }

  toggleSourceActive(src: DicomSource): void {
    if (!src.id || !this.checkPermissionOrToast('EDIT', `DicomSource ${src.callingAeTitle}`)) return;
    const sid = this.selectedSiteId();
    this.http
      .put<DicomSource>(`${this.apiBase}/sources/${src.id}/toggle-active?siteId=${sid}`, {}, this.authHeaders())
      .subscribe({
        next: (saved) => {
          this.pushToast('info', 'Source Status Updated', `${saved.callingAeTitle} is now ${saved.active ? 'ACTIVE' : 'PAUSED'}`);
          this.refreshAll();
        },
        error: (err) => this.handleApiError('Source Status Toggle Failed', err)
      });
  }

  // 4c. PACS Destination Modal
  openCreatePacsModal(): void {
    if (!this.checkPermissionOrToast('CREATE', 'PACS Destination')) return;
    this.modalEditMode.set(false);
    this.pacsForm = this.emptyPacs();
    this.activeModal.set('pacs-dest');
  }

  openEditPacsModal(pacs: PacsDestination): void {
    if (!this.checkPermissionOrToast('EDIT', `PACS Destination ${pacs.aeTitle}`)) return;
    this.modalEditMode.set(true);
    this.pacsForm = { ...pacs };
    this.activeModal.set('pacs-dest');
  }

  savePacsModal(): void {
    const sid = this.selectedSiteId();
    const isEdit = this.modalEditMode() && this.pacsForm.id;
    const payload: PacsDestination = {
      ...this.pacsForm,
      dicomPort: Number(this.pacsForm.dicomPort) || 11112
    };
    const req$ = isEdit
      ? this.http.put<PacsDestination>(`${this.apiBase}/pacs-destinations/${this.pacsForm.id}?siteId=${sid}`, payload, this.authHeaders())
      : this.http.post<PacsDestination>(`${this.apiBase}/pacs-destinations?siteId=${sid}`, payload, this.authHeaders());
    req$.subscribe({
      next: (saved) => {
        this.activeModal.set(null);
        this.pushToast(
          'success',
          isEdit ? 'PACS Destination Updated' : 'PACS Destination Added',
          `${saved.destinationName} (AE: ${saved.aeTitle} @ ${saved.hostAddress}:${saved.dicomPort} · ${saved.reportIntegrationStrategy})`
        );
        this.refreshAll();
      },
      error: (err) => this.handleApiError('PACS Destination Save Failed', err)
    });
  }

  updatePacsStrategy(destId: string | undefined, strategy: string): void {
    if (!destId || !this.checkPermissionOrToast('EDIT', 'PACS Report Pre-Fill Strategy')) return;
    const sid = this.selectedSiteId();
    this.http
      .put(`${this.apiBase}/pacs-destinations/${destId}/report-strategy?siteId=${sid}&strategy=${strategy}`, {}, this.authHeaders())
      .subscribe({
        next: () => {
          this.pushToast('success', 'Report Pre-Fill Strategy Updated', `Updated PACS ReportIntegrationStrategy to '${strategy}'`);
          this.refreshAll();
        },
        error: (err) => this.handleApiError('Strategy Update Failed', err)
      });
  }

  // 4d. Vendor DICOM Connector / Router / Uploader Modal
  openCreateVendorModal(): void {
    if (!this.checkPermissionOrToast('CREATE', 'Vendor DICOM Connector / AI Vendor API')) return;
    this.modalEditMode.set(false);
    this.showVendorApiKey.set(false);
    this.vendorForm = this.emptyVendor();
    this.activeModal.set('vendor');
  }

  openEditVendorModal(vendor: VendorConfig): void {
    if (!this.checkPermissionOrToast('EDIT', `Vendor ${vendor.vendorCode}`)) return;
    this.modalEditMode.set(true);
    this.showVendorApiKey.set(false);
    const isRouter = vendor.destinationType === 'DICOM_ROUTER';
    this.vendorForm = {
      ...vendor,
      agentName: vendor.agentName || vendor.vendorName || '',
      modality: vendor.modality || (isRouter ? 'CT,MR' : 'CR,DX,RF'),
      studyDescriptions: vendor.studyDescriptions || '*',
      connectorType: isRouter ? 'DICOM_CSTORE' : vendor.connectorType || 'GROK_AI_VISION',
      aiModelName: vendor.aiModelName || (isRouter ? '' : 'grok-2-vision-1212'),
      executionMode: vendor.executionMode || 'MOCK_OR_LIVE_FALLBACK',
      outputReportFormat: isRouter ? 'DICOM_NATIVE' : vendor.outputReportFormat || 'ENCAPSULATED_PDF',
      outboundImageFormat: isRouter ? 'DICOM_NATIVE' : vendor.outboundImageFormat || 'BASE64_PNG_LOSSLESS',
      returnDeliveryMode: vendor.returnDeliveryMode || 'PDF_VIA_RIS_API',
      authType: isRouter ? 'NONE' : vendor.authType || 'BEARER_API_KEY'
    };
    this.activeModal.set('vendor');
  }

  onVendorDestinationTypeChange(destType: string): void {
    this.vendorForm.destinationType = destType;
    if (destType === 'DICOM_ROUTER') {
      this.vendorForm.connectorType = 'DICOM_CSTORE';
      this.vendorForm.outboundImageFormat = 'DICOM_NATIVE';
      this.vendorForm.outputReportFormat = 'DICOM_NATIVE';
      this.vendorForm.authType = 'NONE';
      if (this.vendorForm.destinationHost === 'api.x.ai') {
        this.vendorForm.destinationHost = '';
      }
      if (this.vendorForm.destinationPort === 443) {
        this.vendorForm.destinationPort = undefined as unknown as number;
      }
    } else if (destType === 'DICOM_UPLOADER') {
      if (this.vendorForm.connectorType === 'GROK_AI_VISION' || this.vendorForm.connectorType === 'LLM_MULTIMODAL') {
        this.vendorForm.connectorType = 'DICOM_CSTORE';
      }
      this.vendorForm.outboundImageFormat = 'DICOM_NATIVE';
      this.vendorForm.returnDeliveryMode = this.vendorForm.returnDeliveryMode || 'BASE64_PNG_VIA_DICOM_AE';
      if (this.vendorForm.destinationHost === 'api.x.ai') {
        this.vendorForm.destinationHost = '';
      }
      if (this.vendorForm.destinationPort === 443) {
        this.vendorForm.destinationPort = undefined as unknown as number;
      }
    } else if (destType === 'AI_REST_API') {
      if (this.vendorForm.connectorType === 'DICOM_CSTORE') {
        this.vendorForm.connectorType = 'GROK_AI_VISION';
      }
      this.vendorForm.outboundImageFormat =
        this.vendorForm.outboundImageFormat === 'DICOM_NATIVE'
          ? 'BASE64_PNG_LOSSLESS'
          : this.vendorForm.outboundImageFormat || 'BASE64_PNG_LOSSLESS';
      this.vendorForm.returnDeliveryMode = this.vendorForm.returnDeliveryMode || 'PDF_VIA_RIS_API';
      this.vendorForm.authType = 'BEARER_API_KEY';
    }
  }

  onVendorConnectorTypeChange(): void {
    if (this.vendorForm.connectorType === 'GROK_AI_VISION' || this.vendorForm.connectorType === 'LLM_MULTIMODAL') {
      this.vendorForm.destinationType = 'AI_REST_API';
      this.vendorForm.authType = 'BEARER_API_KEY';
      this.vendorForm.executionMode = this.vendorForm.executionMode || 'MOCK_OR_LIVE_FALLBACK';
      this.vendorForm.outputReportFormat = this.vendorForm.outputReportFormat || 'ENCAPSULATED_PDF';
      this.vendorForm.outboundImageFormat =
        this.vendorForm.outboundImageFormat === 'DICOM_NATIVE'
          ? 'BASE64_PNG_LOSSLESS'
          : this.vendorForm.outboundImageFormat || 'BASE64_PNG_LOSSLESS';
      this.vendorForm.returnDeliveryMode = this.vendorForm.returnDeliveryMode || 'PDF_VIA_RIS_API';
    } else if (this.vendorForm.connectorType === 'DICOM_CSTORE') {
      this.vendorForm.outboundImageFormat = 'DICOM_NATIVE';
      this.vendorForm.returnDeliveryMode = this.vendorForm.returnDeliveryMode || 'BASE64_PNG_VIA_DICOM_AE';
    }
  }

  private appendTokenToCommaList(current: string | undefined, token: string): string {
    const cleanToken = (token || '').trim();
    if (!cleanToken) return current || '';
    const existing = this.splitDescriptions(current || '');
    if (existing.some((e) => e.toUpperCase() === cleanToken.toUpperCase())) {
      return existing.join(', ');
    }
    return [...existing, cleanToken].join(', ');
  }

  appendStudyDescriptionToVendor(token: string): void {
    this.vendorForm.studyDescriptions = this.appendTokenToCommaList(this.vendorForm.studyDescriptions, token);
  }

  appendModalityToVendor(token: string): void {
    if (token === '*') {
      this.vendorForm.modality = '*';
      return;
    }
    const existing = this.splitDescriptions(this.vendorForm.modality || '').filter((m) => m !== '*');
    if (!existing.some((e) => e.toUpperCase() === token.toUpperCase())) {
      existing.push(token.toUpperCase());
    }
    this.vendorForm.modality = existing.join(',');
  }

  appendStudyDescriptionToRegion(token: string): void {
    this.regionMappingForm.studyDescriptions = this.appendTokenToCommaList(this.regionMappingForm.studyDescriptions, token);
  }

  appendStudyDescriptionToRule(token: string): void {
    this.ruleForm.studyDescriptionPattern = this.appendTokenToCommaList(this.ruleForm.studyDescriptionPattern, token);
  }

  onRuleTargetVendorChange(vendorId: string): void {
    this.ruleForm.vendorId = vendorId;
  }

  onRegionTargetVendorChange(vendorId: string): void {
    this.regionMappingForm.targetVendorId = vendorId;
  }

  isSelectedRuleVendorRouter(): boolean {
    if (!this.ruleForm.vendorId) return false;
    const v = this.vendors().find((item) => item.id === this.ruleForm.vendorId);
    return Boolean(v && v.destinationType === 'DICOM_ROUTER');
  }

  isSelectedRegionVendorRouter(): boolean {
    if (!this.regionMappingForm.targetVendorId) return false;
    const v = this.vendors().find((item) => item.id === this.regionMappingForm.targetVendorId);
    return Boolean(v && v.destinationType === 'DICOM_ROUTER');
  }

  saveVendorModal(): void {
    const sid = this.selectedSiteId();
    const isEdit = this.modalEditMode() && this.vendorForm.id;
    const isRouter = this.vendorForm.destinationType === 'DICOM_ROUTER';
    if (isRouter) {
      this.vendorForm.connectorType = 'DICOM_CSTORE';
      this.vendorForm.outboundImageFormat = 'DICOM_NATIVE';
      this.vendorForm.outputReportFormat = 'DICOM_NATIVE';
      this.vendorForm.authType = 'NONE';
      this.vendorForm.destinationPort = Number(this.vendorForm.destinationPort) || 11112;
      this.vendorForm.useCaseName =
        (this.vendorForm.useCaseName || '').trim() ||
        `On-Premise DICOM Router (${this.vendorForm.modality || '*'} → ${this.vendorForm.destinationAeTitle || 'DICOM AE'})`;
    }
    const isAiRest =
      !isRouter &&
      (this.vendorForm.connectorType === 'GROK_AI_VISION' ||
        this.vendorForm.connectorType === 'LLM_MULTIMODAL' ||
        this.vendorForm.destinationType === 'AI_REST_API');
    if (!isAiRest && (this.vendorForm.connectorType === 'DICOM_CSTORE' || !this.vendorForm.apiBaseUrl)) {
      const host = this.vendorForm.destinationHost || '127.0.0.1';
      const port = Number(this.vendorForm.destinationPort) || 11112;
      this.vendorForm.destinationPort = port;
      this.vendorForm.apiBaseUrl = `dicom://${this.vendorForm.destinationAeTitle || 'DICOM_ROUTER'}@${host}:${port}`;
    } else if (isAiRest && (!this.vendorForm.apiBaseUrl || this.vendorForm.apiBaseUrl.startsWith('dicom://'))) {
      this.vendorForm.apiBaseUrl = 'https://api.x.ai/v1/chat/completions';
    }

    const resolvedName = (
      this.vendorForm.agentName ||
      this.vendorForm.name ||
      this.vendorForm.vendorName ||
      this.vendorForm.destinationAeTitle ||
      'AI Agent / DICOM Connector'
    ).trim();
    this.vendorForm.vendorName = (this.vendorForm.vendorName || resolvedName).trim();
    this.vendorForm.agentName = resolvedName;
    this.vendorForm.name = resolvedName;
    this.vendorForm.modality = (this.vendorForm.modality || (isRouter ? 'CT,MR' : 'CR,DX,RF')).trim().toUpperCase();
    this.vendorForm.studyDescriptions = (this.vendorForm.studyDescriptions || '*').trim();

    if (isEdit) {
      const payload = {
        vendorName: this.vendorForm.vendorName,
        agentName: resolvedName,
        name: resolvedName,
        useCaseName: this.vendorForm.useCaseName,
        modality: this.vendorForm.modality,
        studyDescriptions: this.vendorForm.studyDescriptions,
        destinationType: this.vendorForm.destinationType,
        destinationAeTitle: this.vendorForm.destinationAeTitle,
        destinationHost: this.vendorForm.destinationHost,
        destinationPort: Number(this.vendorForm.destinationPort) || (isAiRest ? 443 : 11112),
        connectorType: this.vendorForm.connectorType,
        apiBaseUrl: this.vendorForm.apiBaseUrl,
        authType: this.vendorForm.authType,
        apiKeySecret: this.vendorForm.apiKeySecret,
        aiModelName: this.vendorForm.aiModelName,
        executionMode: this.vendorForm.executionMode,
        systemPromptTemplate: this.vendorForm.systemPromptTemplate,
        outputReportFormat: this.vendorForm.outputReportFormat,
        outboundImageFormat: this.vendorForm.outboundImageFormat || (isRouter ? 'DICOM_NATIVE' : 'BASE64_PNG_LOSSLESS'),
        returnDeliveryMode: this.vendorForm.returnDeliveryMode || 'PDF_VIA_RIS_API'
      };
      this.http
        .put<VendorConfig>(`${this.apiBase}/vendors/${this.vendorForm.id}/destination?siteId=${sid}`, payload, this.authHeaders())
        .subscribe({
          next: (saved) => {
            this.activeModal.set(null);
            this.quickAgentNameInput = saved.agentName || saved.vendorName || 'Grok AI Vision Agent';
            this.pushToast(
              'success',
              isRouter ? 'On-Premise DICOM Router Updated' : 'AI Agent & Connector Settings Updated',
              isRouter
                ? `Router '${saved.vendorName}' (AE: ${saved.destinationAeTitle} @ ${saved.destinationHost}:${saved.destinationPort}) · Modality: ${saved.modality || '*'} · Study Desc: ${saved.studyDescriptions || '*'}`
                : `AI Agent Name: '${saved.agentName || saved.vendorName}' · Modality: ${saved.modality || '*'} · Study Desc: ${saved.studyDescriptions || '*'}`
            );
            this.refreshAll();
          },
          error: (err) => this.handleApiError('Vendor Update Failed', err)
        });
    } else {
      this.http
        .post<VendorConfig>(`${this.apiBase}/vendors?siteId=${sid}`, this.vendorForm, this.authHeaders())
        .subscribe({
          next: (saved) => {
            this.activeModal.set(null);
            this.quickAgentNameInput = saved.agentName || saved.vendorName || 'Grok AI Vision Agent';
            this.pushToast(
              'success',
              isRouter ? 'On-Premise DICOM Router Onboarded' : 'AI Agent / Destination Onboarded',
              isRouter
                ? `Onboarded DICOM Router '${saved.vendorName}' (AE: ${saved.destinationAeTitle} @ ${saved.destinationHost}:${saved.destinationPort}) with rules for ${saved.modality || '*'} (${saved.studyDescriptions || '*'})`
                : `Onboarded '${saved.agentName || saved.vendorName}' (Modality: ${saved.modality || '*'} · Study Desc: ${saved.studyDescriptions || '*'})`
            );
            this.refreshAll();
          },
          error: (err) => this.handleApiError('Vendor Onboarding Failed', err)
        });
    }
  }

  quickSaveAiAgentName(vendor?: VendorConfig): void {
    const target = vendor || this.primaryAiAgentVendor() || this.vendors()[0];
    if (!target || !target.id) return;
    if (!this.checkPermissionOrToast('EDIT', `AI Agent Name (${target.vendorCode})`)) return;
    const sid = this.selectedSiteId();
    const newAgentName = (this.quickAgentNameInput || target.agentName || target.name || target.vendorName || 'Grok AI Vision Agent').trim();
    const payload = {
      vendorName: target.vendorName,
      agentName: newAgentName,
      name: newAgentName,
      useCaseName: target.useCaseName,
      modality: target.modality,
      studyDescriptions: target.studyDescriptions,
      destinationType: target.destinationType,
      destinationAeTitle: target.destinationAeTitle,
      destinationHost: target.destinationHost,
      destinationPort: target.destinationPort,
      connectorType: target.connectorType,
      apiBaseUrl: target.apiBaseUrl,
      authType: target.authType,
      apiKeySecret: target.apiKeySecret,
      aiModelName: target.aiModelName,
      executionMode: target.executionMode,
      systemPromptTemplate: target.systemPromptTemplate,
      outputReportFormat: target.outputReportFormat,
      outboundImageFormat: target.outboundImageFormat || 'BASE64_PNG_LOSSLESS',
      returnDeliveryMode: target.returnDeliveryMode || 'PDF_VIA_RIS_API'
    };
    this.http
      .put<VendorConfig>(`${this.apiBase}/vendors/${target.id}/destination?siteId=${sid}`, payload, this.authHeaders())
      .subscribe({
        next: (saved) => {
          this.quickAgentNameInput = saved.agentName || newAgentName;
          this.pushToast(
            'success',
            'AI Agent Name (generatedBy) Saved',
            `Updated AI Agent Name to '${saved.agentName}' — will be sent in reportData.generatedBy when delivering PDF reports to RIS.`
          );
          this.refreshAll();
        },
        error: (err) => this.handleApiError('Save AI Agent Name Failed', err)
      });
  }

  toggleVendorShadow(vendorId?: string): void {
    if (!vendorId || !this.checkPermissionOrToast('EDIT', 'Vendor Shadow Mode')) return;
    const sid = this.selectedSiteId();
    this.http
      .put<VendorConfig>(`${this.apiBase}/vendors/${vendorId}/toggle-shadow-mode?siteId=${sid}`, {}, this.authHeaders())
      .subscribe({
        next: (v) => {
          this.pushToast('info', 'Vendor Mode Toggled', `${v.vendorName} is now in ${v.shadowMode ? 'SHADOW MODE' : 'LIVE CLINICAL MODE'}`);
          this.refreshAll();
        },
        error: (err) => this.handleApiError('Shadow Mode Toggle Failed', err)
      });
  }

  toggleVendorActive(vendorId?: string): void {
    if (!vendorId || !this.checkPermissionOrToast('EDIT', 'Vendor Active Status')) return;
    const sid = this.selectedSiteId();
    this.http
      .put<VendorConfig>(`${this.apiBase}/vendors/${vendorId}/toggle-active?siteId=${sid}`, {}, this.authHeaders())
      .subscribe({
        next: (v) => {
          this.pushToast('info', 'Vendor Status Toggled', `${v.vendorName} is now ${v.active ? 'ACTIVE' : 'PAUSED'}`);
          this.refreshAll();
        },
        error: (err) => this.handleApiError('Vendor Toggle Failed', err)
      });
  }

  // 4e. Routing Rules & Multi-Description Region Mapping Modals
  openCreateRegionMappingModal(): void {
    if (!this.checkPermissionOrToast('CREATE', 'Study Region Synonym Mapping')) return;
    this.modalEditMode.set(false);
    this.regionMappingForm = this.emptyRegionMapping();
    this.activeModal.set('region-mapping');
  }

  openEditRegionMappingModal(rm: StudyRegionMapping): void {
    if (!this.checkPermissionOrToast('EDIT', `Region Mapping ${rm.canonicalRegion}`)) return;
    this.modalEditMode.set(true);
    this.regionMappingForm = { ...rm };
    this.activeModal.set('region-mapping');
  }

  saveRegionMappingModal(): void {
    const sid = this.selectedSiteId();
    const isEdit = this.modalEditMode() && this.regionMappingForm.id;
    const req$ = isEdit
      ? this.http.put<StudyRegionMapping>(
          `${this.apiBase}/region-mappings/${this.regionMappingForm.id}?siteId=${sid}`,
          this.regionMappingForm,
          this.authHeaders()
        )
      : this.http.post<StudyRegionMapping>(
          `${this.apiBase}/region-mappings?siteId=${sid}`,
          this.regionMappingForm,
          this.authHeaders()
        );
    req$.subscribe({
      next: (saved) => {
        this.activeModal.set(null);
        this.pushToast(
          'success',
          isEdit ? 'Region Study Descriptions Updated' : 'Region Study Descriptions Added',
          `${saved.canonicalRegion} (${saved.modality}) → ${saved.studyDescriptions}`
        );
        this.refreshAll();
      },
      error: (err) => this.handleApiError('Region Mapping Save Failed', err)
    });
  }

  openCreateRuleModal(): void {
    if (!this.checkPermissionOrToast('CREATE', 'Routing Rule')) return;
    this.modalEditMode.set(false);
    this.ruleForm = this.emptyRule();
    this.activeModal.set('rule');
  }

  openEditRuleModal(rule: RoutingRule): void {
    if (!this.checkPermissionOrToast('EDIT', `Routing Rule ${rule.ruleName}`)) return;
    this.modalEditMode.set(true);
    this.ruleForm = { ...rule };
    this.activeModal.set('rule');
  }

  saveRuleModal(): void {
    const sid = this.selectedSiteId();
    const isEdit = this.modalEditMode() && this.ruleForm.id;
    const req$ = isEdit
      ? this.http.put<RoutingRule>(`${this.apiBase}/routing-rules/${this.ruleForm.id}?siteId=${sid}`, this.ruleForm, this.authHeaders())
      : this.http.post<RoutingRule>(`${this.apiBase}/routing-rules?siteId=${sid}`, this.ruleForm, this.authHeaders());
    req$.subscribe({
      next: (saved) => {
        this.activeModal.set(null);
        this.pushToast(
          'success',
          isEdit ? 'Routing Rule Updated' : 'Routing Rule Created',
          `#${saved.priorityOrder} ${saved.ruleName} (${saved.modality} → ${saved.studyDescriptionPattern})`
        );
        this.refreshAll();
      },
      error: (err) => this.handleApiError('Routing Rule Save Failed', err)
    });
  }

  toggleRuleActive(ruleId?: string): void {
    if (!ruleId || !this.checkPermissionOrToast('EDIT', 'Routing Rule Active Status')) return;
    const sid = this.selectedSiteId();
    this.http
      .put<RoutingRule>(`${this.apiBase}/routing-rules/${ruleId}/toggle-active?siteId=${sid}`, {}, this.authHeaders())
      .subscribe({
        next: (r) => {
          this.pushToast('info', 'Routing Rule Status Toggled', `${r.ruleName} is now ${r.active ? 'ACTIVE' : 'PAUSED'}`);
          this.refreshAll();
        },
        error: (err) => this.handleApiError('Rule Status Toggle Failed', err)
      });
  }

  openRuleSandboxModal(): void {
    this.activeModal.set('rule-sandbox');
    this.runRuleDryRun();
  }

  quickTestProtocol(modality: string, studyDescription: string, bodyPart: string, seriesDesc: string): void {
    this.ruleTestSample = {
      modality,
      studyDescription,
      bodyPartExamined: bodyPart,
      seriesDescription: seriesDesc,
      sliceThicknessMm: 1.5
    };
    this.runRuleDryRun();
  }

  runRuleDryRun(): void {
    const sid = this.selectedSiteId();
    this.http.post<any>(`${this.apiBase}/routing-rules/test-match?siteId=${sid}`, this.ruleTestSample).subscribe({
      next: (res) => {
        const names = (res.matchedRules || [])
          .map(
            (m: any) =>
              `#${m.priorityOrder} ${m.ruleName} → ${m.vendorCode} (${m.destinationType || 'DICOM_UPLOADER'} · AE: ${m.destinationAeTitle || 'KAUVERY_AI_UPL'} @ ${m.destinationHost || '10.10.30.20'}:${m.destinationPort || 11115})`
          )
          .join(', ');
        this.ruleSandboxResult.set(
          res.matchedRulesCount > 0
            ? `Resolved Canonical Region: '${res.resolvedCanonicalRegion}' → Matched ${res.matchedRulesCount} rule(s): ${names}`
            : `Resolved Canonical Region: '${res.resolvedCanonicalRegion || 'Unknown'}' → No active routing rule matched sample metadata.`
        );
      }
    });
  }

  // =========================================================================
  // 5. User Management & Role Switcher (Req 2.2 & Req 7)
  // =========================================================================

  openSwitchUserModal(): void {
    this.activeModal.set('switch-user');
  }

  switchActiveUser(user: HubUser): void {
    this.currentUser.set(user);
    this.activeModal.set(null);
    this.refreshAll();
    this.connectLiveSseStream();
    const hasConfig =
      user.userRole === 'SUPER_USER' || (user.userRole === 'ADMINISTRATOR' && user.canViewDicomConfig);
    this.pushToast(
      hasConfig ? 'success' : 'warning',
      `Active User Switched → ${user.fullName}`,
      `Role: ${user.userRole} (@${user.username}) · DICOM Configuration Access: ${
        user.userRole === 'SUPER_USER'
          ? 'Full (View / Add / Edit / Delete)'
          : user.userRole === 'ADMINISTRATOR'
            ? `Granular (V:${user.canViewDicomConfig ? '✓' : '✕'} A:${user.canCreateDicomConfig ? '✓' : '✕'} E:${user.canEditDicomConfig ? '✓' : '✕'} D:${user.canDeleteDicomConfig ? '✓' : '✕'})`
            : 'NONE (Restricted)'
      }`
    );
  }

  openCreateUserModal(): void {
    if (!this.canManageUsers()) {
      this.pushToast('error', 'User Management Restricted', 'Only Super Users or authorised Administrators can create users.');
      return;
    }
    this.modalEditMode.set(false);
    this.userForm = this.emptyUser();
    this.activeModal.set('user');
  }

  openEditUserModal(user: HubUser): void {
    if (!this.canManageUsers()) {
      this.pushToast('error', 'User Management Restricted', 'Only Super Users or authorised Administrators can edit user permissions.');
      return;
    }
    this.modalEditMode.set(true);
    this.userForm = { ...user };
    this.activeModal.set('user');
  }

  onUserRoleFormChange(role: string): void {
    this.userForm.userRole = role;
    if (role === 'SUPER_USER') {
      this.userForm.canViewDicomConfig = true;
      this.userForm.canCreateDicomConfig = true;
      this.userForm.canEditDicomConfig = true;
      this.userForm.canDeleteDicomConfig = true;
      this.userForm.canManageUsers = true;
    } else if (role === 'ADMINISTRATOR') {
      this.userForm.canViewDicomConfig = true;
      this.userForm.canCreateDicomConfig = true;
      this.userForm.canEditDicomConfig = true;
      this.userForm.canDeleteDicomConfig = false;
      this.userForm.canManageUsers = false;
    } else {
      this.userForm.canViewDicomConfig = false;
      this.userForm.canCreateDicomConfig = false;
      this.userForm.canEditDicomConfig = false;
      this.userForm.canDeleteDicomConfig = false;
      this.userForm.canManageUsers = false;
    }
  }

  saveUserModal(): void {
    const sid = this.selectedSiteId();
    const isEdit = this.modalEditMode() && this.userForm.id;
    const req$ = isEdit
      ? this.http.put<HubUser>(`${this.apiBase}/users/${this.userForm.id}?siteId=${sid}`, this.userForm, this.authHeaders())
      : this.http.post<HubUser>(`${this.apiBase}/users?siteId=${sid}`, this.userForm, this.authHeaders());
    req$.subscribe({
      next: (saved) => {
        this.activeModal.set(null);
        if (this.currentUser().id === saved.id) {
          this.currentUser.set(saved);
        }
        this.pushToast(
          'success',
          isEdit ? 'User Account & Permissions Updated' : 'New User Account Created',
          `${saved.fullName} (@${saved.username}) · Role: ${saved.userRole}`
        );
        this.refreshAll();
      },
      error: (err) => this.handleApiError('User Save Failed', err)
    });
  }

  toggleUserActive(user: HubUser): void {
    if (!user.id) return;
    const sid = this.selectedSiteId();
    this.http
      .put<HubUser>(`${this.apiBase}/users/${user.id}/toggle-active?siteId=${sid}`, {}, this.authHeaders())
      .subscribe({
        next: (saved) => {
          this.pushToast('info', 'User Status Updated', `@${saved.username} is now ${saved.active ? 'ACTIVE' : 'DISABLED'}`);
          this.refreshAll();
        },
        error: (err) => this.handleApiError('User Status Update Failed', err)
      });
  }

  // =========================================================================
  // 6. Universal Delete Confirmation Modal (Sources, PACS, Vendors, Rules, Regions, Users)
  // =========================================================================

  openDeleteConfirmModal(
    entityType: 'source' | 'pacs-dest' | 'vendor' | 'rule' | 'region-mapping' | 'user',
    entityId: string | undefined,
    entityLabel: string
  ): void {
    if (!entityId) return;
    if (entityType === 'user') {
      if (!this.canManageUsers()) {
        this.pushToast('error', 'Access Denied', 'Current user does not have User Management delete permission.');
        return;
      }
    } else if (!this.canViewDicomConfig()) {
      this.checkPermissionOrToast('DELETE', `${entityType}: ${entityLabel}`);
      return;
    }
    this.deleteConfirmContext = { entityType, entityId, entityLabel };
    this.activeModal.set('confirm-delete');
  }

  confirmDeleteExecution(): void {
    const sid = this.selectedSiteId();
    const { entityType, entityId, entityLabel } = this.deleteConfirmContext;
    const pathMap: Record<string, string> = {
      source: 'sources',
      'pacs-dest': 'pacs-destinations',
      vendor: 'vendors',
      rule: 'routing-rules',
      'region-mapping': 'region-mappings',
      user: 'users'
    };
    const endpoint = pathMap[entityType];
    if (!endpoint) return;

    this.http.delete(`${this.apiBase}/${endpoint}/${entityId}?siteId=${sid}`, this.authHeaders()).subscribe({
      next: () => {
        this.activeModal.set(null);
        this.pushToast('success', 'Configuration Deleted', `Deleted ${entityLabel} and recorded action in audit trail.`);
        this.refreshAll();
      },
      error: (err) => {
        this.activeModal.set(null);
        this.handleApiError('Delete Blocked by RBAC Policy', err);
        this.refreshAll();
      }
    });
  }

  /**
   * Allows testing backend RBAC enforcement directly when an unauthorised user or restricted admin clicks
   * "Test Backend RBAC Guard" to verify 403 Forbidden + Audit Log recording!
   */
  testUnauthorizedBackendConfigCall(): void {
    const sid = this.selectedSiteId();
    this.http
      .put(`${this.apiBase}/dicom-settings?siteId=${sid}`, this.dicomSettings(), this.authHeaders())
      .subscribe({
        next: () => {
          this.pushToast('success', 'Authorized Configuration Action', 'Current role is authorised to update DICOM settings.');
          this.refreshAll();
        },
        error: (err) => {
          this.handleApiError('RBAC 403 Forbidden Enforced & Audited', err);
          this.refreshAll();
        }
      });
  }

  // =========================================================================
  // 7. Study Inspector, Queue Operations, Cleanup & Audit Inspector Modals
  // =========================================================================

  inspectStudyDetails(
    studyId: string,
    initialTab: 'pdf-and-ris' | 'processing-logs' | 'sha256-instances' = 'pdf-and-ris'
  ): void {
    const sid = this.selectedSiteId();
    this.studyInspectorTab.set(initialTab);
    this.http.get<StudyDetailTimeline>(`${this.apiBase}/studies/${studyId}/details?siteId=${sid}`).subscribe({
      next: (detail) => {
        this.selectedStudyDetail.set(detail);
        const isReceiving =
          detail.study && this.resolveReceivingStatus(detail.study) === 'RECEIVING';
        const hasAiReport =
          !isReceiving &&
          Boolean(detail.latestPdfUrl) &&
          ((detail.results && detail.results.length > 0) ||
            (detail.risUploadedReports && detail.risUploadedReports.length > 0));
        if (hasAiReport && detail.latestPdfUrl) {
          const rawUrl = detail.latestPdfUrl;
          const sep = rawUrl.includes('?') ? '&' : '?';
          const bustedUrl = `${rawUrl}${sep}t=${Date.now()}`;
          this.rawPdfViewerUrl.set(rawUrl);
          this.safePdfViewerUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(bustedUrl));
        } else {
          this.rawPdfViewerUrl.set('');
          this.safePdfViewerUrl.set(null);
          if (initialTab === 'pdf-and-ris') {
            this.studyInspectorTab.set('processing-logs');
          }
        }
        this.activeModal.set('study-inspector');
      },
      error: () => {}
    });
  }

  openRisReportInAppViewer(
    rep: RisUploadedReport,
    initialTab: 'pdf-and-ris' | 'processing-logs' = 'pdf-and-ris'
  ): void {
    const matchedStudy =
      (rep.studyId && this.studies().find((s) => s.id === rep.studyId)) ||
      this.studies().find((s) => s.studyInstanceUid === rep.studyIuid);
    if (matchedStudy) {
      this.inspectStudyDetails(matchedStudy.id, initialTab);
      return;
    }
    const rawUrl = this.getRisReportPdfUrl(rep.id);
    this.rawPdfViewerUrl.set(rawUrl);
    this.safePdfViewerUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(`${rawUrl}&t=${Date.now()}`));
    this.studyInspectorTab.set(initialTab);
    const syntheticStudy: StudyRecord = {
      id: rep.studyId || rep.id,
      siteId: rep.siteId,
      sourceType: 'MODALITY',
      callingAeTitle: 'KAUVERY_XR01',
      calledAeTitle: 'DICOMHUB_AE',
      studyInstanceUid: rep.studyIuid,
      patientId: 'KH-XR-REPORT',
      patientName: 'KAUVERY X-RAY PATIENT',
      modality: 'DX',
      studyDescription: 'X-RAY CHEST PA VIEW',
      bodyPartExamined: 'CHEST',
      seriesCount: 1,
      instanceCount: 1,
      totalBytes: rep.pdfFileSizeBytes,
      lifecycleState: 'ROUTED',
      receivingStatus: 'COMPLETED',
      createdAt: rep.uploadedAt
    };
    this.selectedStudyDetail.set({
      study: syntheticStudy,
      instances: [],
      jobs: [],
      attempts: [],
      results: [],
      risUploadedReports: [rep],
      processingLogs: [
        {
          stepNumber: 1,
          stage: 'RIS / TELERAD UploadReportServlet PDF & reportData DELIVERY',
          action: rep.replacedPrevious ? 'RIS_PDF_REPORT_REPLACED' : 'RIS_PDF_REPORT_UPLOADED',
          actor: rep.generatedBy || 'Grok AI Vision Agent',
          status: rep.httpStatus === 200 ? 'SUCCESS' : 'FAILURE',
          message:
            rep.processingLog ||
            `Sent PDF '${rep.pdfFileName}' (${rep.pdfFileSizeBytes} bytes) to ${rep.targetEndpointUrl} with generatedBy='${rep.generatedBy || 'Grok AI Vision Agent'}' -> HTTP ${rep.httpStatus}`,
          detailsJson: rep.reportData || rep.responseJson,
          timestamp: rep.uploadedAt
        }
      ],
      latestPdfUrl: rawUrl,
      latestReportDataJson: rep.reportData || '',
      latestGeneratedBy: rep.generatedBy || 'Grok AI Vision Agent',
      pdfTextPreview: rep.processingLog || ''
    });
    this.activeModal.set('study-inspector');
  }

  sendStudyPdfToRis(
    studyId: string,
    reportType?: 'Preliminary Report' | 'Study Report' | 'Addendum Report' | string
  ): void {
    const sid = this.selectedSiteId();
    const effectiveReportType =
      (reportType && reportType.trim()) ||
      this.risEndpointForm.defaultReportType ||
      this.risEndpoint().defaultReportType ||
      'Preliminary Report';
    this.sendingPdfToRis.set(true);
    this.http
      .post<StudyDetailTimeline>(
        `${this.apiBase}/studies/${studyId}/send-pdf-to-ris?siteId=${sid}&reportType=${encodeURIComponent(effectiveReportType)}`,
        {}
      )
      .subscribe({
        next: (detail) => {
          this.sendingPdfToRis.set(false);
          this.selectedStudyDetail.set(detail);
          if (detail.latestPdfUrl) {
            const rawUrl = detail.latestPdfUrl;
            const sep = rawUrl.includes('?') ? '&' : '?';
            this.rawPdfViewerUrl.set(rawUrl);
            this.safePdfViewerUrl.set(this.sanitizer.bypassSecurityTrustResourceUrl(`${rawUrl}${sep}t=${Date.now()}`));
          }
          const latestRis = detail.risUploadedReports?.[0] || null;
          if (latestRis && this.isRisReportFailed(latestRis)) {
            this.pushToast(
              'error',
              `RIS Server Error (HTTP ${latestRis.httpStatus})`,
              `${latestRis.responseMessage || latestRis.responseJson} · Target: ${latestRis.targetEndpointUrl}`
            );
          } else {
            this.pushToast(
              'success',
              `PDF & reportData Sent to RIS (${effectiveReportType})`,
              `generatedBy: '${detail.latestGeneratedBy}' · StudyIuid: ${detail.study.studyInstanceUid}`
            );
          }
          this.refreshAll();
        },
        error: (err) => {
          this.sendingPdfToRis.set(false);
          this.handleApiError('Send PDF to RIS Failed', err);
        }
      });
  }

  sendStudyDicomToRis(studyId: string): void {
    const sid = this.selectedSiteId();
    this.sendingDicomToRis.set(true);
    this.http
      .post<AiResultRecord>(
        `${this.apiBase}/studies/${studyId}/send-dicom-to-ris?siteId=${sid}`,
        {},
        this.authHeaders()
      )
      .subscribe({
        next: (res) => {
          this.sendingDicomToRis.set(false);
          this.pushToast(
            'success',
            'AI Vendor DICOM (.dcm) Received, Re-identified & Sent to RIS',
            `File: ${res.dicomFileName || 'AI-VENDOR-DICOM.dcm'} (${this.formatBytes(res.dicomFileSizeBytes || 0)}) · RIS API: ${res.risDicomUploadStatus || 'DICOM_UPLOADED_HTTP_200'} · DICOM AE: ${res.dicomAeReturnStatus || 'SENT_TO_AE'}`
          );
          if (this.activeModal() === 'study-inspector' && this.selectedStudyDetail()?.study?.id === studyId) {
            this.inspectStudyDetails(studyId, this.studyInspectorTab());
          }
          this.refreshAll();
        },
        error: (err) => {
          this.sendingDicomToRis.set(false);
          this.handleApiError('Receive AI DICOM & Send to RIS Failed', err);
        }
      });
  }

  getStudyPdfUrl(studyId: string): string {
    return `${this.apiBase}/studies/${studyId}/pdf?siteId=${this.selectedSiteId()}`;
  }

  getStudyAiDicomUrl(studyId: string): string {
    return `${this.apiBase}/studies/${studyId}/ai-dicom?siteId=${this.selectedSiteId()}`;
  }

  getRisReportForStudy(study?: StudyRecord | null): RisUploadedReport | null {
    if (!study) return null;
    return (
      this.risUploadedReports().find(
        (r) => r.studyIuid === study.studyInstanceUid || (r.studyId && r.studyId === study.id)
      ) || null
    );
  }

  getLatestAiResultForStudy(studyId?: string | null): AiResultRecord | null {
    if (!studyId) return null;
    return this.aiResults().find((r) => r.studyId === studyId) || null;
  }

  hasAiResultForStudy(studyId?: string | null): boolean {
    if (!studyId) return false;
    const study = this.studies().find((s) => s.id === studyId);
    if (study && this.resolveReceivingStatus(study) === 'RECEIVING') return false;
    const hasAi = this.aiResults().some((r) => r.studyId === studyId);
    if (hasAi) return true;
    if (!study) return false;
    return this.risUploadedReports().some(
      (r) => r.studyIuid === study.studyInstanceUid || (r.studyId && r.studyId === study.id)
    );
  }

  hasAiPdfForStudy(studyId?: string | null): boolean {
    return this.hasAiResultForStudy(studyId);
  }

  hasAiImageForStudy(studyId?: string | null): boolean {
    if (!studyId) return false;
    const study = this.studies().find((s) => s.id === studyId);
    if (study && this.resolveReceivingStatus(study) === 'RECEIVING') return false;
    const res = this.getLatestAiResultForStudy(studyId);
    return Boolean(res && res.imageStoragePath);
  }

  hasAiDicomForStudy(studyId?: string | null): boolean {
    if (!studyId) return false;
    const study = this.studies().find((s) => s.id === studyId);
    if (study && this.resolveReceivingStatus(study) === 'RECEIVING') return false;
    const res = this.getLatestAiResultForStudy(studyId);
    if (res && (res.dicomFileName || res.dicomStoragePath)) return true;
    if (!study) return false;
    return this.risUploadedReports().some(
      (r) =>
        (r.studyIuid === study.studyInstanceUid || (r.studyId && r.studyId === study.id)) &&
        Boolean(r.dicomFileName || r.dicomFilePath)
    );
  }

  isStudyRoutedToDicomRouter(studyId?: string | null): boolean {
    if (!studyId) return false;
    const studyJobs = this.jobs().filter((j) => j.studyId === studyId);
    if (studyJobs.length === 0) {
      const fpRows = this.forwardedPatients().filter((fp) => fp.studyId === studyId && fp.jobId);
      return fpRows.length > 0 && fpRows.every((fp) => fp.destinationType === 'DICOM_ROUTER');
    }
    return studyJobs.every((j) => {
      const v = this.vendors().find((item) => item.id === j.vendorId);
      if (v) return v.destinationType === 'DICOM_ROUTER';
      const vm = this.vendorMetrics().find((item) => item.vendorId === j.vendorId);
      return Boolean(vm && vm.destinationType === 'DICOM_ROUTER');
    });
  }

  getStudyRoutedRouterLabel(studyId?: string | null): string {
    if (!studyId) return 'DICOM Router';
    const fp = this.forwardedPatients().find((r) => r.studyId === studyId && r.destinationType === 'DICOM_ROUTER');
    if (fp) {
      return fp.destinationAeTitle && fp.destinationAeTitle !== '—' ? fp.destinationAeTitle : fp.vendorName;
    }
    const job = this.jobs().find((j) => j.studyId === studyId);
    if (job) {
      const v = this.vendors().find((item) => item.id === job.vendorId);
      if (v) return v.destinationAeTitle || v.vendorName || v.vendorCode;
      const vm = this.vendorMetrics().find((item) => item.vendorId === job.vendorId);
      if (vm) return vm.destinationAeTitle || vm.vendorName || vm.vendorCode;
    }
    return 'DICOM Router';
  }

  isRisReportFailed(rep?: RisUploadedReport | null): boolean {
    if (!rep) return false;
    if (rep.httpStatus < 200 || rep.httpStatus >= 300) return true;
    const raw = (rep.responseJson || '').toLowerCase();
    return raw.includes('"status":"failure"') || raw.includes('"status": "failure"');
  }

  // =========================================================================
  // 8. DICOM PS3.15 Anonymize Rules (10 Rules) & Live .dcm Metadata Inspector
  // =========================================================================

  ensureAnonymizationPreviewLoaded(): void {
    let targetId = this.selectedAnonymizationStudyId();
    if (!targetId && this.studies().length > 0) {
      const amutha188 = this.studies().find((s) => (s.patientId || '').includes('MR138970') || (s.instanceCount || 0) >= 100);
      targetId = amutha188 ? amutha188.id : this.studies()[0].id;
      this.selectedAnonymizationStudyId.set(targetId);
    }
    if (targetId) {
      this.loadStudyAnonymizationPreview(targetId, false);
    }
  }

  openAnonymizationInspectorModal(studyId?: string): void {
    let targetId = studyId || this.selectedAnonymizationStudyId();
    if (!targetId && this.studies().length > 0) {
      const amutha188 = this.studies().find((s) => (s.patientId || '').includes('MR138970') || (s.instanceCount || 0) >= 100);
      targetId = amutha188 ? amutha188.id : this.studies()[0].id;
    }
    if (!targetId) {
      this.pushToast('warning', 'No Study Available', 'Please ingest or select a study to inspect PS3.15 anonymization.');
      return;
    }
    this.selectedAnonymizationStudyId.set(targetId);
    this.activeModal.set('anonymization-inspector');
    this.loadStudyAnonymizationPreview(targetId, false);
  }

  onSelectAnonymizationStudy(studyId: string): void {
    if (!studyId) return;
    this.selectedAnonymizationStudyId.set(studyId);
    this.loadStudyAnonymizationPreview(studyId, false);
  }

  loadStudyAnonymizationPreview(studyId: string, refresh = false): void {
    if (!studyId) return;
    const sid = this.selectedSiteId();
    this.anonymizationLoading.set(true);
    this.http
      .get<StudyAnonymizationPreview>(
        `${this.apiBase}/studies/${studyId}/anonymization-preview?siteId=${sid}&refresh=${refresh}`,
        this.authHeaders()
      )
      .subscribe({
        next: (preview) => {
          this.anonymizationLoading.set(false);
          this.anonymizationPreview.set(preview);
        },
        error: (err) => {
          this.anonymizationLoading.set(false);
          this.handleApiError('Anonymization Inspector Error', err);
        }
      });
  }

  activateAndApplyAnonymizationOnStudy(studyId?: string): void {
    const targetId = studyId || this.selectedAnonymizationStudyId() || this.anonymizationPreview()?.studyId;
    if (!targetId) return;
    const sid = this.selectedSiteId();
    this.anonymizationLoading.set(true);
    this.http
      .post<StudyAnonymizationPreview>(
        `${this.apiBase}/studies/${targetId}/apply-anonymization?siteId=${sid}`,
        {},
        this.authHeaders()
      )
      .subscribe({
        next: (preview) => {
          this.anonymizationLoading.set(false);
          this.anonymizationPreview.set(preview);
          this.pushToast(
            preview.allTenRulesCompliant ? 'success' : 'warning',
            `PS3.15 Anonymize Rules Activated (${preview.profileCode} v${preview.profileVersion})`,
            `Verified all 10 PS3.15 rule groups on ${preview.patientName} (${preview.patientId}) — ${preview.anonymizedWorkingCopyInstances} working-copy .dcm file(s) updated on disk.`
          );
          this.refreshAll();
        },
        error: (err) => {
          this.anonymizationLoading.set(false);
          this.handleApiError('Apply Anonymize Rules Failed', err);
        }
      });
  }

  openEditDeidProfileModal(profile?: DeidProfile): void {
    if (!this.checkPermissionOrToast('EDIT', 'DICOM PS3.15 Anonymize Profile (FR-11)')) return;
    this.returnToAnonymizationInspectorModal = this.activeModal() === 'anonymization-inspector';
    const target = profile || this.activeDeidProfile();
    if (target) {
      this.modalEditMode.set(true);
      this.deidProfileForm = {
        ...target,
        dateTimePolicy: target.dateTimePolicy || 'SHIFT_DATES',
        privateTagAllowList: target.privateTagAllowList || '',
        burnedInTextAction: target.burnedInTextAction || (target.blockOnBurnedInText ? 'BLOCK_STUDY' : 'MASK_PIXEL_REGION')
      };
    } else {
      this.modalEditMode.set(false);
      this.deidProfileForm = this.emptyDeidProfile();
    }
    this.activeModal.set('deid-profile');
  }

  private returnToAnonymizationInspectorModal = false;

  saveDeidProfileModal(): void {
    const sid = this.selectedSiteId();
    this.deidProfileForm.blockOnBurnedInText = this.deidProfileForm.burnedInTextAction !== 'MASK_PIXEL_REGION';
    const isEdit = this.modalEditMode() && this.deidProfileForm.id;
    const req$ = isEdit
      ? this.http.put<DeidProfile>(
          `${this.apiBase}/deid-profiles/${this.deidProfileForm.id}?siteId=${sid}`,
          this.deidProfileForm,
          this.authHeaders()
        )
      : this.http.post<DeidProfile>(
          `${this.apiBase}/deid-profiles?siteId=${sid}`,
          this.deidProfileForm,
          this.authHeaders()
        );
    req$.subscribe({
      next: (saved) => {
        const shouldReturnToInspector = this.returnToAnonymizationInspectorModal;
        this.returnToAnonymizationInspectorModal = false;
        this.activeModal.set(shouldReturnToInspector ? 'anonymization-inspector' : null);
        this.pushToast(
          'success',
          `PS3.15 Anonymize Rules Saved (${saved.profileCode} v${saved.profileVersion})`,
          `Dates=${saved.dateTimePolicy} (${saved.dateShiftDays}d) · Age/Sex=${saved.retainPatientAgeSex ? 'Keep' : 'Remove'} · AcqParams=${saved.retainDeviceAcquisitionParams ? 'Keep' : 'Remove'} · Burned-In=${saved.burnedInTextAction}`
        );
        this.refreshAll();
        if (this.selectedAnonymizationStudyId()) {
          this.activateAndApplyAnonymizationOnStudy(this.selectedAnonymizationStudyId());
        }
      },
      error: (err) => this.handleApiError('Save PS3.15 Anonymize Profile Failed', err)
    });
  }

  openAuditInspectorModal(event: AuditEvent): void {
    this.selectedAuditEvent.set(event);
    this.activeModal.set('audit-inspector');
  }

  routeStudyOnDemand(studyId: string, vendorId?: string): void {
    const sid = this.selectedSiteId();
    const targetVendor = (vendorId ?? this.getSelectedAgentForStudy(studyId) ?? '').trim();
    const vendorParam = targetVendor ? `&vendorId=${encodeURIComponent(targetVendor)}` : '';
    this.http.post(`${this.apiBase}/studies/${studyId}/route?siteId=${sid}&forceResend=true${vendorParam}`, {}).subscribe({
      next: () => {
        this.pushToast('success', 'On-Demand Study Forwarding Triggered', `Validated, de-identified, and forwarded study ${studyId}`);
        this.refreshAll();
      },
      error: (err) => this.handleApiError('Study Forwarding Failed', err)
    });
  }

  retryJob(jobId: string): void {
    const sid = this.selectedSiteId();
    this.http.post(`${this.apiBase}/jobs/${jobId}/retry?siteId=${sid}`, {}).subscribe({
      next: () => {
        this.pushToast('success', 'Manual Job Retry Executed', `Triggered outbound transfer retry for job ${jobId}`);
        this.refreshAll();
      }
    });
  }

  cancelJob(jobId: string): void {
    const sid = this.selectedSiteId();
    this.http.post(`${this.apiBase}/jobs/${jobId}/cancel?siteId=${sid}`, {}).subscribe({
      next: () => {
        this.pushToast('warning', 'Delivery Job Cancelled', `Cancelled outbound job ${jobId}`);
        this.refreshAll();
      }
    });
  }

  quarantineJob(jobId: string): void {
    const sid = this.selectedSiteId();
    this.http.post(`${this.apiBase}/jobs/${jobId}/quarantine?siteId=${sid}`, {}).subscribe({
      next: () => {
        this.pushToast('warning', 'Job Quarantined for Manual Review', `Placed job ${jobId} in MANUAL_REVIEW`);
        this.refreshAll();
      }
    });
  }

  toggleJobLegalHold(jobId: string): void {
    const sid = this.selectedSiteId();
    this.http.post(`${this.apiBase}/jobs/${jobId}/legal-hold?siteId=${sid}`, {}).subscribe({
      next: () => {
        this.pushToast('info', 'Job Legal Hold Toggled', `Updated legal hold protection on job ${jobId}`);
        this.refreshAll();
      }
    });
  }

  setResultDisposition(resultId: string, disposition: 'ACCEPTED' | 'EDITED' | 'REJECTED'): void {
    const sid = this.selectedSiteId();
    const actor = this.currentUser().username || 'dr.sundar';
    this.http
      .post(`${this.apiBase}/results/${resultId}/disposition?siteId=${sid}&disposition=${disposition}&actor=${actor}`, {})
      .subscribe({
        next: () => {
          this.pushToast(
            'success',
            `Radiologist Disposition: ${disposition}`,
            `Recorded '${disposition}' by ${actor} (Editable clinical draft – not auto-signed)`
          );
          this.refreshAll();
        }
      });
  }

  executeCleanupPolicy(policyId: string, dryRun: boolean): void {
    const sid = this.selectedSiteId();
    this.http.post<any>(`${this.apiBase}/cleanup-policies/${policyId}/execute?siteId=${sid}&dryRun=${dryRun}`, {}).subscribe({
      next: (res) => {
        this.pushToast(dryRun ? 'info' : 'success', dryRun ? 'Cleanup Dry-Run Report' : 'Working Copy Cleanup Executed', res.summaryMessage);
        this.refreshAll();
      }
    });
  }

  toggleCleanupLegalHold(policyId: string): void {
    const sid = this.selectedSiteId();
    this.http.put(`${this.apiBase}/cleanup-policies/${policyId}/toggle-legal-hold?siteId=${sid}`, {}).subscribe({
      next: () => {
        this.pushToast('warning', 'Cleanup Legal Hold Toggled', 'Updated Legal/Incident Hold on vendor cleanup policy');
        this.refreshAll();
      }
    });
  }

  runLlmAc9Test(injectPhi: boolean): void {
    const sid = this.selectedSiteId();
    const payload = injectPhi
      ? {
          modality: 'CT',
          bodyPart: 'ABDOMEN KH-PAT-998877',
          patientAge: '045Y',
          patientSex: 'F',
          sliceThicknessMm: '1.5',
          injectedIdentifierToTest: 'KH-PAT-998877',
          sampleReplyJson: '{"findings":[],"patient_name":"RAMESH KUMAR"}'
        }
      : {
          modality: 'CT',
          bodyPart: 'ABDOMEN',
          patientAge: '045Y',
          patientSex: 'F',
          sliceThicknessMm: '1.5',
          injectedIdentifierToTest: '',
          sampleReplyJson: '{"findings":[{"type":"renal_calculus","size_mm":5.4,"confidence":0.94}],"impression_draft":"Editable LLM draft assist"}'
        };
    this.http.post<any>(`${this.apiBase}/llm/test-prompt-gate?siteId=${sid}`, payload).subscribe({
      next: (res) => {
        const summary = `Prompt Approved: ${res.promptApproved} | Reply Valid: ${res.replyValid} | Label: '${res.draftSeriesLabel || 'BLOCKED'}' | Reason: ${res.promptRejectionReason || res.replyValidationError || 'Passed AC-9 Zero-Identifier Gate'}`;
        this.llmGateResult.set(summary);
        this.pushToast(res.promptApproved && res.replyValid ? 'success' : 'warning', 'AC-9 LLM Privacy Gate Result', summary);
        this.refreshAll();
      }
    });
  }

  splitDescriptions(raw: string): string[] {
    if (!raw) return [];
    return raw
      .split(',')
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
  }

  formatBytes(bytes: number): string {
    if (!bytes || bytes <= 0) return '0 B';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
  }

  closeModal(): void {
    if (this.activeModal() === 'deid-profile' && this.returnToAnonymizationInspectorModal) {
      this.returnToAnonymizationInspectorModal = false;
      this.activeModal.set('anonymization-inspector');
      return;
    }
    this.returnToAnonymizationInspectorModal = false;
    this.activeModal.set(null);
  }

  private handleApiError(title: string, err: any): void {
    const msg =
      err?.error?.message ||
      err?.error?.detail ||
      err?.message ||
      'Operation blocked by access policy or validation failure.';
    this.pushToast('error', title, msg);
  }

  private emptySource(): DicomSource {
    return {
      sourceName: '',
      sourceType: 'RIS_PACS',
      callingAeTitle: '',
      calledAeTitle: '',
      hostAddress: '',
      dicomPort: undefined as unknown as number,
      quietPeriodSeconds: 60,
      active: true
    };
  }

  private emptyPacs(): PacsDestination {
    return {
      destinationName: '',
      aeTitle: '',
      hostAddress: '',
      dicomPort: undefined as unknown as number,
      reportIntegrationStrategy: 'TELERAD_UPLOAD_REPORT_SERVLET',
      reportEndpointUrl: '',
      defaultForSite: false,
      active: true
    };
  }

  private emptyVendor(): VendorConfig {
    return {
      vendorCode: '',
      vendorName: '',
      agentName: '',
      name: '',
      useCaseName: '',
      modality: '',
      studyDescriptions: '',
      destinationType: 'AI_REST_API',
      destinationAeTitle: '',
      destinationHost: '',
      destinationPort: undefined as unknown as number,
      connectorType: 'GROK_AI_VISION',
      apiBaseUrl: '',
      authType: 'BEARER_API_KEY',
      apiKeySecret: '',
      aiModelName: '',
      executionMode: 'MOCK_OR_LIVE_FALLBACK',
      outputReportFormat: 'ENCAPSULATED_PDF',
      outboundImageFormat: 'BASE64_PNG_LOSSLESS',
      returnDeliveryMode: 'PDF_VIA_RIS_API',
      systemPromptTemplate: '',
      slaTurnaroundMinutes: 5,
      shadowMode: false,
      active: true
    };
  }

  private emptyRule(): RoutingRule {
    return {
      priorityOrder: (this.routingRules()?.length || 0) + 1,
      ruleName: '',
      modality: '',
      canonicalRegion: '',
      studyDescriptionPattern: '',
      bodyPartPattern: '',
      matchOperator: 'CONTAINS',
      vendorId: '',
      requiredSeriesPattern: '',
      maxSliceThicknessMm: undefined as unknown as number,
      allowMultiVendor: false,
      active: true
    };
  }

  private emptyRegionMapping(): StudyRegionMapping {
    return {
      canonicalRegion: '',
      modality: '',
      bodyPart: '',
      studyDescriptions: '',
      targetVendorId: '',
      active: true
    };
  }

  private emptyUser(): HubUser {
    return {
      username: '',
      password: 'admin',
      fullName: '',
      email: '',
      department: '',
      userRole: 'ADMINISTRATOR',
      canViewDicomConfig: true,
      canCreateDicomConfig: true,
      canEditDicomConfig: true,
      canDeleteDicomConfig: false,
      canManageUsers: false,
      active: true
    };
  }

  private emptyDeidProfile(): DeidProfile {
    return {
      profileCode: '',
      profileName: '',
      profileVersion: 1,
      retainPatientAgeSex: true,
      retainDeviceAcquisitionParams: true,
      dateTimePolicy: 'SHIFT_DATES',
      dateShiftDays: -42,
      stripPrivateTags: true,
      privateTagAllowList: '',
      burnedInTextAction: 'BLOCK_STUDY',
      blockOnBurnedInText: true,
      active: true
    };
  }

  private emptyRisEndpoint(): RisReportEndpoint {
    return {
      endpointName: 'Kauvery Telerad / RIS UploadReportServlet',
      hostAddress: '172.17.1.200',
      port: 8080,
      contextPath: '/telerad/UploadReportServlet',
      targetUrl: 'http://172.17.1.200:8080/telerad/UploadReportServlet',
      authUsername: 'sonocare',
      authPassword: 'sonocare123',
      jsonPartName: 'reportData',
      filePartName: 'pdfFile',
      dicomPartName: 'dicomFile',
      supportDicomUpload: true,
      forwardDicomToPacsAe: true,
      defaultReportType: 'Preliminary Report',
      useBuiltinMock: false,
      active: true
    };
  }

  private emptyRetentionSettings(): RecordRetentionSettings {
    return {
      autoRemoveEnabled: true,
      retentionMaxAgeMinutes: 60,
      checkIntervalMinutes: 5,
      removeStudyLogsAndAudits: true,
      removeDiskFiles: true,
      protectActiveReceivingStudies: true
    };
  }

  onRisAddressFieldChange(): void {
    const host = (this.risEndpointForm.hostAddress || '172.17.1.200').trim();
    const port = this.risEndpointForm.port || 8080;
    let path = (this.risEndpointForm.contextPath || '/telerad/UploadReportServlet').trim();
    if (!path.startsWith('/')) {
      path = '/' + path;
    }
    this.risEndpointForm.contextPath = path;
    this.risEndpointForm.targetUrl = `http://${host}:${port}${path}`;
  }

  onRisTargetUrlChange(): void {
    const raw = (this.risEndpointForm.targetUrl || '').trim();
    try {
      const parsed = new URL(raw);
      if (parsed.hostname) {
        this.risEndpointForm.hostAddress = parsed.hostname;
      }
      if (parsed.port) {
        this.risEndpointForm.port = Number(parsed.port);
      }
      if (parsed.pathname) {
        this.risEndpointForm.contextPath = parsed.pathname;
      }
    } catch {
      // Keep user's typed URL while they are typing
    }
  }

  saveRisEndpointConfig(): void {
    if (!this.checkPermissionOrToast('EDIT', 'RIS / Telerad UploadReportServlet Endpoint')) return;
    const sid = this.selectedSiteId();
    this.risEndpointForm.useBuiltinMock = false;
    this.http
      .put<RisReportEndpoint>(`${this.apiBase}/ris-endpoints?siteId=${sid}`, this.risEndpointForm, this.authHeaders())
      .subscribe({
        next: (saved) => {
          this.risEndpoint.set(saved);
          this.risEndpointForm = { ...saved, useBuiltinMock: false };
          this.pushToast(
            'success',
            'RIS / Telerad UploadReportServlet Updated',
            `${saved.targetUrl} (User: ${saved.authUsername} · Mode: Live HTTP Multipart to RIS Server)`
          );
          this.refreshAll();
        },
        error: (err) => this.handleApiError('Save RIS Endpoint Failed', err)
      });
  }

  testRisReportUpload(reportType?: 'Preliminary Report' | 'Study Report' | 'Addendum Report' | string): void {
    const sid = this.selectedSiteId();
    const effectiveReportType =
      (reportType && reportType.trim()) ||
      this.risEndpointForm.defaultReportType ||
      this.risEndpoint().defaultReportType ||
      'Preliminary Report';
    this.risTesting.set(true);
    this.http
      .post<any>(
        `${this.apiBase}/ris-endpoints/test-upload?siteId=${sid}&reportType=${encodeURIComponent(effectiveReportType)}`,
        {},
        this.authHeaders()
      )
      .subscribe({
        next: (res) => {
          this.risTesting.set(false);
          this.risTestResult.set(res);
          const ok = res.httpStatus >= 200 && res.httpStatus < 300 && res.status !== 'failure';
          const reasonDetail = res.reason ? ` — Reason: ${res.reason}` : '';
          this.pushToast(
            ok ? 'success' : 'error',
            ok ? `RIS UploadReportServlet Verified (${res.reportType || effectiveReportType})` : `RIS Server Error (HTTP ${res.httpStatus})`,
            `${res.message}${reasonDetail} · Target: ${res.targetUrl} · StudyIuid: ${res.studyIuid}`
          );
          this.refreshAll();
        },
        error: (err) => {
          this.risTesting.set(false);
          this.handleApiError('RIS UploadReportServlet Test Failed', err);
        }
      });
  }

  testRisDicomUpload(includePdf = false): void {
    const sid = this.selectedSiteId();
    const effectiveReportType =
      this.risEndpointForm.defaultReportType ||
      this.risEndpoint().defaultReportType ||
      'Preliminary Report';
    this.risTesting.set(true);
    this.http
      .post<any>(
        `${this.apiBase}/ris-endpoints/test-dicom-upload?siteId=${sid}&reportType=${encodeURIComponent(effectiveReportType)}&includePdf=${includePdf}`,
        {},
        this.authHeaders()
      )
      .subscribe({
        next: (res) => {
          this.risTesting.set(false);
          this.risTestResult.set(res);
          const ok = res.httpStatus >= 200 && res.httpStatus < 300 && res.status !== 'failure';
          const reasonDetail = res.reason ? ` — Reason: ${res.reason}` : '';
          this.pushToast(
            ok ? 'success' : 'error',
            ok
              ? `RIS DICOM (.dcm) Upload Verified (${res.dicomFileName || 'AI-VENDOR-DICOM.dcm'})`
              : `RIS Server Error (HTTP ${res.httpStatus})`,
            `${res.message}${reasonDetail} · Format: ${res.fileFormat || 'DICOM'} · AE: ${res.dicomAeStatus || 'SENT_DICOM_TO_AE'}`
          );
          this.refreshAll();
        },
        error: (err) => {
          this.risTesting.set(false);
          this.handleApiError('RIS DICOM (.dcm) Upload Test Failed', err);
        }
      });
  }

  // =========================================================================
  // 9. Old Study Records & Logs Removal + Frequent Auto-Removal Screen
  // =========================================================================

  loadRetentionSummary(): void {
    const sid = this.selectedSiteId();
    this.http
      .get<RetentionDashboardSummary>(`${this.apiBase}/retention-settings?siteId=${sid}`, this.authHeaders())
      .subscribe({
        next: (summary) => {
          this.retentionSummary.set(summary);
          if (summary && summary.settings) {
            this.retentionForm = { ...summary.settings };
          }
        },
        error: () => {}
      });
  }

  saveRetentionSettings(): void {
    if (!this.checkPermissionOrToast('EDIT', 'Old Study Records & Logs Auto-Removal Settings')) return;
    const sid = this.selectedSiteId();
    this.retentionSaving.set(true);
    this.http
      .put<RetentionDashboardSummary>(
        `${this.apiBase}/retention-settings?siteId=${sid}`,
        this.retentionForm,
        this.authHeaders()
      )
      .subscribe({
        next: (summary) => {
          this.retentionSaving.set(false);
          this.retentionSummary.set(summary);
          this.retentionForm = { ...summary.settings };
          this.pushToast(
            'success',
            'Auto-Removal & Retention Settings Saved',
            summary.settings.autoRemoveEnabled
              ? `Frequent Auto-Removal ENABLED: purges study records & logs older than ${summary.settings.retentionMaxAgeMinutes} min (checks every ${summary.settings.checkIntervalMinutes} min).`
              : 'Frequent Auto-Removal paused (manual record & log cleanup remains available).'
          );
          this.refreshAll();
        },
        error: (err) => {
          this.retentionSaving.set(false);
          this.handleApiError('Save Retention Settings Failed', err);
        }
      });
  }

  runManualRecordPurge(olderThanMinutes: number | null, purgeAll = false): void {
    if (!this.checkPermissionOrToast('DELETE', 'Purge Old Study Records & Logs')) return;
    const sid = this.selectedSiteId();
    const mins = olderThanMinutes ?? this.customPurgeMinutesInput ?? this.retentionForm.retentionMaxAgeMinutes ?? 60;
    const removeLogs = this.retentionForm.removeStudyLogsAndAudits ?? true;
    const removeFiles = this.retentionForm.removeDiskFiles ?? true;
    this.retentionPurging.set(true);
    this.http
      .post<RecordPurgeResult>(
        `${this.apiBase}/retention-settings/purge?siteId=${sid}&olderThanMinutes=${mins}&purgeAll=${purgeAll}&removeLogs=${removeLogs}&removeFiles=${removeFiles}`,
        {},
        this.authHeaders()
      )
      .subscribe({
        next: (res) => {
          this.retentionPurging.set(false);
          this.lastPurgeResult.set(res);
          this.pushToast(
            'success',
            purgeAll ? 'All Old Study Records & Logs Removed' : `Removed Records Older Than ${mins} Min`,
            res.summaryMessage
          );
          this.refreshAll();
        },
        error: (err) => {
          this.retentionPurging.set(false);
          this.handleApiError('Purge Old Records Failed', err);
        }
      });
  }

  deleteSingleStudyWithLogs(study: StudyRecord): void {
    if (!study || !study.id) return;
    if (!this.checkPermissionOrToast('DELETE', `Study ${study.patientName} (${study.patientId})`)) return;
    const sid = this.selectedSiteId();
    this.http
      .delete<RecordPurgeResult>(`${this.apiBase}/studies/${study.id}?siteId=${sid}`, this.authHeaders())
      .subscribe({
        next: (res) => {
          this.lastPurgeResult.set(res);
          if (this.selectedStudyDetail()?.study?.id === study.id) {
            this.selectedStudyDetail.set(null);
            this.activeModal.set(null);
          }
          this.pushToast(
            'success',
            `Removed Study & Logs: ${study.patientName || study.patientId}`,
            res.summaryMessage
          );
          this.refreshAll();
        },
        error: (err) => this.handleApiError('Delete Study & Logs Failed', err)
      });
  }

  runGrokXrayEndToEndDemo(
    scenario:
      | 'grok-xray'
      | 'grok-xray-cr'
      | 'grok-xray-rf'
      | 'grok-xray-base64-png-ae'
      | 'grok-xray-base64-jpeg-ae'
      | 'grok-xray-dicom-ris'
      | 'grok-xray-dicom-ae'
      | 'grok-xray-both-pdf-and-dicom-ris' = 'grok-xray'
  ): void {
    const sid = this.selectedSiteId();
    this.http.post<any>(`${this.apiBase}/simulator/run-scenario?siteId=${sid}&scenario=${scenario}`, {}).subscribe({
      next: (res) => {
        if (res && res.studyId) {
          this.recentlyUpdatedStudyId.set(res.studyId);
        }
        const isDicomScenario = scenario.includes('dicom');
        const isImageAe = scenario.includes('base64');
        this.pushToast(
          'success',
          isDicomScenario
            ? 'Grok AI X-Ray → AI Vendor DICOM (.dcm) Received → Re-identified & Sent to RIS & AE'
            : isImageAe
              ? 'Grok AI X-Ray → Base64 PNG/JPEG → DICOM AE Return Completed'
              : 'Grok AI X-Ray → PDF → RIS UploadReportServlet Completed',
          isDicomScenario
            ? `Ingested X-Ray (${res.studyInstanceUid}), received AI Vendor DICOM (.dcm) file, re-identified PatientID/Accession/StudyInstanceUID on-premise, uploaded .dcm to RIS UploadReportServlet, and forwarded to PACS/RIS DICOM AE.`
            : isImageAe
              ? `Ingested X-Ray (${res.studyInstanceUid}), shared lossless image to Grok AI, converted returned Base64 image to re-identified DICOM, and sent to PACS/RIS Destination via AE Title, IP & Port.`
              : `Ingested ${scenario.toUpperCase()} study (${res.studyInstanceUid}), de-identified working copy, shared lossless image to Grok Vision, generated PDF report, and uploaded to RIS UploadReportServlet.`
        );
        this.refreshAll();
      },
      error: (err) => this.handleApiError('Grok X-Ray Pipeline Failed', err)
    });
  }

  purgeNonGrokDemoData(): void {
    const sid = this.selectedSiteId();
    this.http
      .post<any>(`${this.apiBase}/demo-data/purge-keep-grok-only?siteId=${sid}`, {}, this.authHeaders())
      .subscribe({
        next: (res) => {
          this.pushToast(
            'success',
            'Demo Data Cleared (Grok AI & Single Kauvery Hospital Only)',
            res.message || 'Purged all non-Grok demo data and initialized fresh Grok AI X-Ray test study.'
          );
          this.refreshAll();
        },
        error: (err) => this.handleApiError('Purge Demo Data Failed', err)
      });
  }

  getRisReportPdfUrl(reportId: string): string {
    return `${this.apiBase}/ris-reports/${reportId}/pdf?siteId=${this.selectedSiteId()}`;
  }

  getRisReportDicomUrl(reportId: string): string {
    return `${this.apiBase}/ris-reports/${reportId}/dicom?siteId=${this.selectedSiteId()}`;
  }

  getAiResultPdfUrl(resultId: string): string {
    return `${this.apiBase}/results/${resultId}/pdf?siteId=${this.selectedSiteId()}`;
  }

  getAiResultDicomUrl(resultId: string): string {
    return `${this.apiBase}/results/${resultId}/dicom?siteId=${this.selectedSiteId()}`;
  }

  getAiResultImageUrl(resultId: string): string {
    return `${this.apiBase}/results/${resultId}/image?siteId=${this.selectedSiteId()}`;
  }
}

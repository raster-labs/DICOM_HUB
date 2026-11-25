export interface Site {
  id: string;
  siteCode: string;
  siteName: string;
  city: string;
  active: boolean;
}

export interface DashboardOverview {
  siteId: string;
  totalStudiesReceived: number;
  modalityOriginStudies: number;
  pacsOriginStudies: number;
  studiesAwaitingCompletion: number;
  studiesRouted: number;
  vendorConfirmedUploads: number;
  vendorProcessingCount: number;
  resultsReceivedCount: number;
  resultsReturnedToPacsCount: number;
  failedOrQuarantinedJobs: number;
  validationFailedStudies?: number;
  originalRepoTotalBytes?: number;
  vendorTransferredTotalBytes?: number;
  activeAlertsCount?: number;
  totalImagesReceived?: number;
  imagesReceivedToday?: number;
  totalTransferEventsReceived?: number;
  duplicateInstancesSkipped?: number;
  risPacsImagesReceived?: number;
  modalityImagesReceived?: number;
  studiesCurrentlyReceiving?: number;
  studiesCompletedReceiving?: number;
  studiesOnHoldReceiving?: number;
  studiesFailedReceiving?: number;
  studiesIncompleteReceiving?: number;
}

export interface VendorMetricRow {
  vendorId: string;
  vendorCode: string;
  vendorName: string;
  useCaseName: string;
  destinationType?: string;
  destinationAeTitle?: string;
  destinationHost?: string;
  destinationPort?: number;
  shadowMode: boolean;
  studiesRoutedToVendor: number;
  modalityOriginRouted?: number;
  pacsOriginRouted?: number;
  uploadAttempts: number;
  vendorConfirmedUploads: number;
  totalImagesTransferred?: number;
  failedUploads: number;
  currentlyQueued: number;
  vendorProcessing: number;
  resultsReceived: number;
  resultsReturnedToPacs: number;
  slaMinutes: number;
  avgTurnaroundSeconds?: number;
  totalBytesTransferred: number;
}

export interface ForwardedPatientTransfer {
  jobId?: string;
  studyId: string;
  siteId: string;
  patientId: string;
  patientName: string;
  patientAge: string;
  patientSex: string;
  accessionNumber: string;
  pseudoPatientId: string;
  modality: string;
  studyDescription: string;
  canonicalRegion: string;
  bodyPartExamined: string;
  sourceType: string;
  callingAeTitle: string;
  vendorId?: string;
  vendorCode: string;
  vendorName: string;
  destinationType: string;
  destinationAeTitle: string;
  destinationHost: string;
  destinationPort: number;
  connectorType: string;
  totalStudyInstances: number;
  selectedInstances: number;
  transferredInstances: number;
  uploadedBytes: number;
  transferStatus: string;
  vendorJobId?: string;
  transferredAt?: string;
  createdAt: string;
}

export interface StudyRecord {
  id: string;
  siteId: string;
  sourceType: string;
  callingAeTitle: string;
  calledAeTitle: string;
  studyInstanceUid: string;
  patientId?: string;
  patientName?: string;
  patientAge?: string;
  patientSex?: string;
  accessionNumber?: string;
  canonicalRegion?: string;
  modality: string;
  studyDescription: string;
  bodyPartExamined: string;
  seriesCount: number;
  instanceCount: number;
  receivedTransferEvents?: number;
  duplicateInstancesCount?: number;
  receivingStatus?: string;
  totalBytes: number;
  lifecycleState: string;
  validationError?: string;
  lastInstanceReceivedAt?: string;
  createdAt: string;
}

export interface InstanceRecord {
  id: string;
  seriesInstanceUid: string;
  sopInstanceUid: string;
  sopClassUid: string;
  seriesDescription: string;
  sliceThicknessMm: number;
  burnedInAnnotation: string;
  originalRepoPath: string;
  sha256Checksum: string;
  fileSizeBytes: number;
  duplicateCount: number;
  lateArrival: boolean;
}

export interface VendorDeliveryJob {
  id: string;
  siteId: string;
  studyId: string;
  vendorId: string;
  routingRuleId?: string;
  deidProfileId: string;
  status: string;
  workingCopyDir?: string;
  vendorJobId?: string;
  attemptCount: number;
  maxAttempts: number;
  nextRetryAt?: string;
  lastError?: string;
  uploadedBytes: number;
  selectedInstances?: number;
  transferredInstances?: number;
  vendorAcceptedAt?: string;
  resultReceivedAt?: string;
  pacsReturnedAt?: string;
  workingCopyCleanedAt?: string;
  legalHold: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface VendorTransferAttempt {
  id: string;
  jobId: string;
  attemptNumber: number;
  idempotencyKey: string;
  outcome: string;
  statusCode: string;
  responseSummary: string;
  startedAt: string;
  finishedAt: string;
}

export interface AiResultRecord {
  id: string;
  jobId: string;
  studyId: string;
  vendorId: string;
  resultType: string;
  modelName: string;
  modelVersion: string;
  seriesDescriptionLabel: string;
  storagePath: string;
  pdfStoragePath?: string;
  imageStoragePath?: string;
  dicomStoragePath?: string;
  dicomFileName?: string;
  dicomFileSizeBytes?: number;
  risDicomUploadStatus?: string;
  findingsJson: string;
  reidentified: boolean;
  pacsForwarded: boolean;
  reportPrefillStrategy: string;
  reportPrefillStatus: string;
  risUploadStatus?: string;
  risUploadResponse?: string;
  generatedBy?: string;
  reportData?: string;
  dicomAeReturnStatus?: string;
  radiologistDisposition: string;
  receivedAt: string;
}

export interface StudyProcessingLogEntry {
  stepNumber: number;
  stage: string;
  action: string;
  actor: string;
  status: string;
  message: string;
  detailsJson: string;
  timestamp: string;
}

export interface StudyDetailTimeline {
  study: StudyRecord;
  instances: InstanceRecord[];
  jobs: VendorDeliveryJob[];
  attempts: VendorTransferAttempt[];
  results: AiResultRecord[];
  risUploadedReports?: RisUploadedReport[];
  processingLogs?: StudyProcessingLogEntry[];
  latestPdfUrl?: string | null;
  latestReportDataJson?: string | null;
  latestGeneratedBy?: string;
  pdfTextPreview?: string | null;
}

export interface DicomSource {
  id?: string;
  sourceName: string;
  sourceType: string;
  callingAeTitle: string;
  calledAeTitle: string;
  hostAddress: string;
  dicomPort: number;
  quietPeriodSeconds: number;
  active: boolean;
}

export interface PacsDestination {
  id?: string;
  destinationName: string;
  aeTitle: string;
  hostAddress: string;
  dicomPort: number;
  reportIntegrationStrategy: string;
  reportEndpointUrl: string;
  defaultForSite: boolean;
  active: boolean;
}

export interface VendorConfig {
  id?: string;
  vendorCode: string;
  vendorName: string;
  agentName?: string;
  name?: string;
  useCaseName: string;
  modality?: string;
  studyDescriptions?: string;
  connectorType: string;
  destinationType?: string;
  destinationAeTitle?: string;
  destinationHost?: string;
  destinationPort?: number;
  apiBaseUrl: string;
  authType: string;
  apiKeySecret?: string;
  aiModelName?: string;
  executionMode?: string;
  systemPromptTemplate?: string;
  outputReportFormat?: string;
  outboundImageFormat?: 'DICOM_NATIVE' | 'BASE64_PNG_LOSSLESS' | 'BASE64_JPEG_LOSSLESS_100' | string;
  returnDeliveryMode?:
    | 'PDF_VIA_RIS_API'
    | 'BASE64_PNG_VIA_DICOM_AE'
    | 'BASE64_JPEG_VIA_DICOM_AE'
    | 'BOTH_PDF_API_AND_IMAGE_AE'
    | 'DICOM_VIA_RIS_API'
    | 'DICOM_VIA_DICOM_AE'
    | 'BOTH_PDF_API_AND_DICOM_AE'
    | string;
  slaTurnaroundMinutes: number;
  maxRetryAttempts?: number;
  shadowMode: boolean;
  active: boolean;
  configVersion?: number;
}

export interface RisReportEndpoint {
  id?: string;
  siteId?: string;
  endpointName: string;
  hostAddress: string;
  port: number;
  contextPath: string;
  targetUrl: string;
  authUsername: string;
  authPassword: string;
  jsonPartName: string;
  filePartName: string;
  dicomPartName?: string;
  supportDicomUpload?: boolean;
  forwardDicomToPacsAe?: boolean;
  defaultReportType: 'Preliminary Report' | 'Study Report' | 'Addendum Report' | string;
  useBuiltinMock: boolean;
  active: boolean;
  updatedBy?: string;
  updatedAt?: string;
}

export interface RisUploadedReport {
  id: string;
  siteId: string;
  jobId?: string;
  studyId?: string;
  studyIuid: string;
  reportType: string;
  draftedTime: string;
  draftedBy: string;
  reviewedTime: string;
  reviewedBy: string;
  generatedBy?: string;
  reportData?: string;
  processingLog?: string;
  pdfFileName: string;
  pdfFilePath: string;
  pdfFileSizeBytes: number;
  fileFormat?: string;
  dicomFileName?: string;
  dicomFilePath?: string;
  dicomFileSizeBytes?: number;
  dicomAeStatus?: string;
  targetEndpointUrl: string;
  httpStatus: number;
  responseJson: string;
  responseMessage?: string;
  replacedPrevious: boolean;
  uploadedAt: string;
}

export interface RoutingRule {
  id?: string;
  ruleName: string;
  priorityOrder: number;
  modality: string;
  canonicalRegion?: string;
  studyDescriptionPattern: string;
  bodyPartPattern: string;
  matchOperator: string;
  vendorId: string;
  requiredSeriesPattern?: string;
  maxSliceThicknessMm: number;
  allowMultiVendor: boolean;
  active: boolean;
  ruleVersion?: number;
}

export interface StudyRegionMapping {
  id?: string;
  siteId?: string;
  canonicalRegion: string;
  modality: string;
  bodyPart: string;
  studyDescriptions: string;
  targetVendorId?: string;
  active: boolean;
  createdAt?: string;
}

export interface DicomNodeSettings {
  id?: string;
  siteId?: string;
  localAeTitle: string;
  bindIpAddress: string;
  localPort: number;
  maxPduLength: number;
  connectTimeoutMs: number;
  associationAcceptTimeoutMs: number;
  dimseResponseTimeoutMs: number;
  idleTimeoutSeconds: number;
  maxConcurrentAssociations: number;
  defaultQuietPeriodSeconds: number;
  incompleteStudyTimeoutSeconds: number;
  acceptUnknownCallingAe: boolean;
  requireStorageCommitment: boolean;
  tlsEnabled: boolean;
  transferSyntaxPolicy: string;
  updatedBy?: string;
  updatedAt?: string;
}

export interface HubUser {
  id?: string;
  siteId?: string;
  username: string;
  password?: string;
  fullName: string;
  email: string;
  department: string;
  userRole: 'SUPER_USER' | 'ADMINISTRATOR' | 'RADIOLOGIST' | 'TECHNOLOGIST' | 'OPERATIONS' | 'AUDITOR' | string;
  canViewDicomConfig: boolean;
  canCreateDicomConfig: boolean;
  canEditDicomConfig: boolean;
  canDeleteDicomConfig: boolean;
  canManageUsers: boolean;
  active: boolean;
  lastLoginAt?: string;
  createdAt?: string;
}

export interface AuthLoginResponse {
  authenticated: boolean;
  token: string;
  message: string;
  user: HubUser;
  siteId: string;
}

export interface CleanupPolicy {
  id: string;
  vendorId?: string;
  targetCategory: string;
  retentionHoursAfterUpload: number;
  retentionHoursAfterReturn: number;
  scheduleCron: string;
  dryRunOnly: boolean;
  legalHoldActive: boolean;
  active: boolean;
}

export interface OperationalAlert {
  alertId: string;
  severity: string;
  category: string;
  title: string;
  description: string;
  entityType: string;
  entityId: string;
  raisedAt: string;
}

export interface LlmApprovedModel {
  id: string;
  providerName: string;
  modelIdentifier: string;
  pinnedVersion: string;
  cdscoClearanceStatus: string;
  indiaRegionHosted: boolean;
  approvedByRadiology: boolean;
  approvedByLegal: boolean;
  active: boolean;
}

export interface LlmPromptTemplate {
  id: string;
  templateCode: string;
  useCaseName: string;
  templateVersion: number;
  windowPreset: string;
  maxSlices: number;
  promptBody: string;
  findingsJsonSchema: string;
  shadowMode: boolean;
  approvedBy: string;
}

export interface AuditEvent {
  id: string;
  actorUsername: string;
  actorRole: string;
  eventCategory: string;
  action: string;
  entityType: string;
  entityId?: string;
  outcome: string;
  changedSetting?: string;
  detailsJson: string;
  createdAt: string;
}

export interface UniversalSearchHit {
  category: string;
  entityId: string;
  title: string;
  subtitle: string;
  badge: string;
  status: string;
  matchType: string;
  similarityScore: number;
  matchedField: string;
  targetSubpage: string;
}

export interface LiveIncomingStudyEvent {
  eventId: string;
  siteId: string;
  eventType: string;
  studyId: string;
  studyInstanceUid: string;
  patientId: string;
  patientName: string;
  modality: string;
  studyDescription: string;
  sourceType: string;
  callingAeTitle: string;
  receivingStatus: string;
  uniqueStoredInstances: number;
  receivedTransferEvents: number;
  duplicateInstancesSkipped: number;
  sopInstanceUid: string;
  message: string;
  timestamp: string;
  updatedStudy?: StudyRecord;
  overview?: DashboardOverview;
  updatedJob?: VendorDeliveryJob;
  jobs?: VendorDeliveryJob[];
  updatedResult?: AiResultRecord;
  aiResults?: AiResultRecord[];
  forwardedPatients?: ForwardedPatientTransfer[];
  vendorMetrics?: VendorMetricRow[];
  risUploadedReports?: RisUploadedReport[];
  alerts?: OperationalAlert[];
  latestAuditEvent?: AuditEvent;
}

export interface CEchoDiagnosticResult {
  success: boolean;
  mode: string;
  callingAeTitle: string;
  calledAeTitle: string;
  negotiatedCalledAeTitle: string;
  host: string;
  port: number;
  latencyMs: number;
  dimseStatus: string;
  transferSyntax: string;
  tcpReachable: boolean;
  message: string;
}

export interface ToastMessage {
  id: string;
  type: 'success' | 'error' | 'warning' | 'info';
  title: string;
  message: string;
  timestamp: string;
}

export interface DeidProfile {
  id?: string;
  siteId?: string;
  profileCode: string;
  profileName: string;
  profileVersion: number;
  retainPatientAgeSex: boolean;
  retainDeviceAcquisitionParams: boolean;
  dateTimePolicy: 'SHIFT_DATES' | 'KEEP_DATES' | 'REMOVE_DATES' | string;
  dateShiftDays: number;
  stripPrivateTags: boolean;
  privateTagAllowList: string;
  burnedInTextAction: 'BLOCK_STUDY' | 'MASK_PIXEL_REGION' | string;
  blockOnBurnedInText: boolean;
  active: boolean;
  createdAt?: string;
}

export interface AnonymizeRuleComparisonRow {
  ruleNumber: number;
  ruleGroupTitle: string;
  configuredAction: string;
  dicomTagsInspected: string;
  originalValue: string;
  anonymizedValue: string;
  complianceStatus: string;
  compliant: boolean;
}

export interface StudyAnonymizationPreview {
  studyId: string;
  siteId: string;
  patientName: string;
  patientId: string;
  modality: string;
  studyDescription: string;
  totalStudyInstances: number;
  anonymizedWorkingCopyInstances: number;
  originalRepoPath: string;
  originalFileSha256: string;
  originalFileSizeBytes: number;
  originalPrivateTagCount: number;
  workingCopyPath: string;
  workingCopyFileSha256: string;
  workingCopyFileSizeBytes: number;
  workingCopyPrivateTagCount: number;
  profileId: string;
  profileCode: string;
  profileName: string;
  profileVersion: number;
  retainPatientAgeSex: boolean;
  retainDeviceAcquisitionParams: boolean;
  dateTimePolicy: string;
  dateShiftDays: number;
  stripPrivateTags: boolean;
  privateTagAllowList: string;
  burnedInTextAction: string;
  blockOnBurnedInText: boolean;
  patientIdentityRemovedTag: string;
  deidentificationMethodTag: string;
  pseudoPatientId: string;
  pseudoAccessionNumber: string;
  pseudoStudyUid: string;
  encryptedRealPatientIdPreview: string;
  encryptedRealAccessionPreview: string;
  vaultReversibleVerified: boolean;
  allTenRulesCompliant: boolean;
  ruleComparisons: AnonymizeRuleComparisonRow[];
}

export interface RecordRetentionSettings {
  id?: string;
  siteId?: string;
  autoRemoveEnabled: boolean;
  retentionMaxAgeMinutes: number;
  checkIntervalMinutes: number;
  removeStudyLogsAndAudits: boolean;
  removeDiskFiles: boolean;
  protectActiveReceivingStudies: boolean;
  lastAutoRunAt?: string;
  lastRemovedStudiesCount?: number;
  lastRemovedLogsCount?: number;
  totalRemovedStudiesCount?: number;
  totalRemovedLogsCount?: number;
  lastAutoPurgeAt?: string;
  lastPurgedStudiesCount?: number;
  lastPurgedLogsCount?: number;
  lastPurgeSummary?: string;
  updatedBy?: string;
  updatedAt?: string;
}

export interface RetentionDashboardSummary {
  settings: RecordRetentionSettings;
  activeStudiesCount: number;
  activeInstancesCount: number;
  activeJobsCount: number;
  activeTransferAttemptsCount: number;
  activeAiResultsCount: number;
  activeRisReportsCount: number;
  activeAuditLogsCount: number;
  totalOriginalRepoBytes: number;
  totalWorkingCopyBytes: number;
  totalRisPdfBytes: number;
}

export interface RecordPurgeResult {
  siteId: string;
  purgeAll: boolean;
  olderThanMinutes: number;
  removedStudiesCount: number;
  removedInstancesCount: number;
  removedJobsCount: number;
  removedAiResultsCount: number;
  removedRisReportsCount: number;
  removedAuditLogsCount: number;
  freedDiskBytes: number;
  triggerSource: string;
  executedAt: string;
  summaryMessage: string;
}


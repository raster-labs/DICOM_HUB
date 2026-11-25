package com.kauvery.dicomhub.report;

import com.kauvery.dicomhub.domain.entity.StudyRecord;
import com.kauvery.dicomhub.domain.entity.VendorConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pure-Java PDF 1.4 Clinical Report Generator.
 * Produces a standards-compliant, multi-line %PDF-1.4 document (<= 15 MB) containing:
 * - Mandatory FR-21 / FR-42 Clinical Draft Governance Banner ("AI-generated – not verified")
 * - On-premise Re-identified Patient & Study Metadata (Patient ID, Name, Age/Sex, Accession, StudyInstanceUID)
 * - Grok AI Structured X-Ray Findings, Anatomical Observations, and Impression Draft
 * - Telerad / RIS Sign-off Metadata (reportType, draftedTime, draftedBy, reviewedTime, reviewedBy)
 */
@Component
public class ClinicalPdfReportGenerator {

    public record GeneratedPdfReport(
            Path pdfFilePath,
            String pdfFileName,
            byte[] pdfBytes,
            String draftedTimeLocal,
            String reviewedTimeLocal,
            String commonUsername,
            String generatedBy,
            String reportTextPreview
    ) {
        public GeneratedPdfReport(
                Path pdfFilePath,
                String pdfFileName,
                byte[] pdfBytes,
                String draftedTimeLocal,
                String reviewedTimeLocal,
                String commonUsername) {
            this(pdfFilePath, pdfFileName, pdfBytes, draftedTimeLocal, reviewedTimeLocal, commonUsername, "Grok AI Vision Agent", "");
        }
    }

    public GeneratedPdfReport generateReidentifiedXrayPdfReport(
            Path outputDirectory,
            StudyRecord study,
            VendorConfig vendor,
            String restoredPatientId,
            String restoredPatientName,
            String restoredAccessionNumber,
            String restoredStudyInstanceUid,
            String modelName,
            String modelVersion,
            String seriesDraftLabel,
            String findingsJson,
            String reportType,
            String commonUsername,
            String draftedTimeLocal,
            String reviewedTimeLocal) throws IOException {
        return generateReidentifiedXrayPdfReport(
                outputDirectory,
                study,
                vendor,
                restoredPatientId,
                restoredPatientName,
                restoredAccessionNumber,
                restoredStudyInstanceUid,
                modelName,
                modelVersion,
                seriesDraftLabel,
                findingsJson,
                reportType,
                commonUsername,
                draftedTimeLocal,
                reviewedTimeLocal,
                vendor != null ? vendor.resolveEffectiveAgentName() : "Grok AI Vision Agent");
    }

    public GeneratedPdfReport generateReidentifiedXrayPdfReport(
            Path outputDirectory,
            StudyRecord study,
            VendorConfig vendor,
            String restoredPatientId,
            String restoredPatientName,
            String restoredAccessionNumber,
            String restoredStudyInstanceUid,
            String modelName,
            String modelVersion,
            String seriesDraftLabel,
            String findingsJson,
            String reportType,
            String commonUsername,
            String draftedTimeLocal,
            String reviewedTimeLocal,
            String generatedBy) throws IOException {

        Files.createDirectories(outputDirectory);
        String safeUidSuffix = restoredStudyInstanceUid.replaceAll("[^A-Za-z0-9.]", "_");
        if (safeUidSuffix.length() > 36) {
            safeUidSuffix = safeUidSuffix.substring(safeUidSuffix.length() - 36);
        }
        String fileName = "GROK-XRAY-REPORT-" + safeUidSuffix + ".pdf";
        Path pdfPath = outputDirectory.resolve(fileName);
        String effectiveGeneratedBy = safeText(
                generatedBy,
                vendor != null ? vendor.resolveEffectiveAgentName() : "Grok AI Vision Agent");

        List<String> lines = new ArrayList<>();
        lines.add("==========================================================================");
        lines.add("KAUVERY HOSPITAL - DICOM HUB AI RADIOLOGY REPORT (DRAFT ASSIST)");
        lines.add("==========================================================================");
        lines.add("GOVERNANCE LABEL: " + seriesDraftLabel);
        lines.add("CLINICAL NOTICE : AI-generated - not verified. Preliminary draft for review.");
        lines.add("--------------------------------------------------------------------------");
        lines.add("PATIENT & STUDY DEMOGRAPHICS (RE-IDENTIFIED ON-PREMISE)");
        lines.add("--------------------------------------------------------------------------");
        lines.add("Patient Name       : " + safeText(restoredPatientName, "UNKNOWN"));
        lines.add("Patient ID         : " + safeText(restoredPatientId, "UNKNOWN"));
        lines.add("Patient Age / Sex  : " + safeText(study.getPatientAge(), "N/A") + " / " + safeText(study.getPatientSex(), "N/A"));
        lines.add("Accession Number   : " + safeText(restoredAccessionNumber, "N/A"));
        lines.add("Study Instance UID : " + safeText(restoredStudyInstanceUid, study.getStudyInstanceUid()));
        lines.add("Modality & Region  : " + safeText(study.getModality(), "DX") + " | " + safeText(study.getCanonicalRegion(), "X-Ray Study"));
        lines.add("Study Description  : " + safeText(study.getStudyDescription(), "X-Ray Diagnostic Study"));
        lines.add("Body Part Examined : " + safeText(study.getBodyPartExamined(), "CHEST / ANATOMICAL REGION"));
        lines.add("--------------------------------------------------------------------------");
        lines.add("GROK AI VISION MODEL & TELERAD RIS METADATA");
        lines.add("--------------------------------------------------------------------------");
        lines.add("Generated By (AI)  : " + effectiveGeneratedBy);
        lines.add("AI Vendor          : " + safeText(vendor != null ? vendor.getVendorName() : null, "Grok AI Vision") + " (" + safeText(vendor != null ? vendor.getVendorCode() : null, "VENDOR-GROK-XRAY") + ")");
        lines.add("AI Model & Version : " + safeText(modelName, "grok-2-vision-1212") + " [" + safeText(modelVersion, "2026.11") + "]");
        lines.add("Report Type        : " + safeText(reportType, "Preliminary Report"));
        lines.add("Drafted By / Time  : " + safeText(commonUsername, "sonocare") + " @ " + safeText(draftedTimeLocal, ""));
        lines.add("Reviewed By / Time : " + safeText(commonUsername, "sonocare") + " @ " + safeText(reviewedTimeLocal, ""));
        lines.add("--------------------------------------------------------------------------");
        lines.add("STRUCTURED X-RAY AI FINDINGS & OBSERVATIONS");
        lines.add("--------------------------------------------------------------------------");

        for (String findingLine : formatFindingsLines(findingsJson)) {
            lines.addAll(wrapLine(findingLine, 78));
        }

        lines.add("--------------------------------------------------------------------------");
        lines.add("END OF AI-GENERATED REPORT - GENERATED BY KAUVERY DICOM HUB ON-PREMISE");
        lines.add("==========================================================================");

        byte[] pdfBytes = buildValidPdf14Document(lines);
        Files.write(pdfPath, pdfBytes);
        return new GeneratedPdfReport(
                pdfPath,
                fileName,
                pdfBytes,
                draftedTimeLocal,
                reviewedTimeLocal,
                commonUsername,
                effectiveGeneratedBy,
                String.join("\n", lines));
    }

    private List<String> formatFindingsLines(String findingsJson) {
        List<String> out = new ArrayList<>();
        if (findingsJson == null || findingsJson.isBlank()) {
            out.add("No structured findings returned.");
            return out;
        }
        String clean = findingsJson.trim();
        String urgency = extractJsonStringValue(clean, "urgency_level");
        if (!urgency.isBlank()) {
            out.add("Urgency Triage Level : " + urgency);
        }
        String summary = extractJsonStringValue(clean, "anatomical_summary");
        if (!summary.isBlank()) {
            out.add("Anatomical Summary   : " + summary);
        }
        String impression = extractJsonStringValue(clean, "impression_draft");
        if (!impression.isBlank()) {
            out.add("Impression Draft     : " + impression);
        }
        out.add("");
        out.add("Raw Structured AI Findings Payload:");
        out.add(clean);
        return out;
    }

    private String extractJsonStringValue(String json, String key) {
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return "";
        }
        int colon = json.indexOf(':', idx + marker.length());
        if (colon < 0) {
            return "";
        }
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return "";
        }
        StringBuilder unescaped = new StringBuilder();
        boolean escaping = false;
        int pos = firstQuote + 1;
        boolean closed = false;
        while (pos < json.length()) {
            char c = json.charAt(pos);
            if (escaping) {
                switch (c) {
                    case '"', '\\', '/' -> unescaped.append(c);
                    case 'n' -> unescaped.append('\n');
                    case 'r' -> unescaped.append('\r');
                    case 't' -> unescaped.append('\t');
                    default -> unescaped.append(c);
                }
                escaping = false;
            } else if (c == '\\') {
                escaping = true;
            } else if (c == '"') {
                closed = true;
                break;
            } else {
                unescaped.append(c);
            }
            pos++;
        }
        return closed ? unescaped.toString() : "";
    }

    private List<String> wrapLine(String text, int maxWidth) {
        List<String> wrapped = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            wrapped.add("");
            return wrapped;
        }
        String remaining = text;
        while (remaining.length() > maxWidth) {
            int splitAt = remaining.lastIndexOf(' ', maxWidth);
            if (splitAt <= 10) {
                splitAt = maxWidth;
            }
            wrapped.add(remaining.substring(0, splitAt).trim());
            remaining = "  " + remaining.substring(splitAt).trim();
        }
        wrapped.add(remaining);
        return wrapped;
    }

    private byte[] buildValidPdf14Document(List<String> lines) {
        StringBuilder stream = new StringBuilder();
        stream.append("BT\n");
        stream.append("/F2 11 Tf\n");
        int y = 795;
        int lineCount = 0;
        for (String line : lines) {
            if (y < 40) {
                break;
            }
            if (lineCount == 3) {
                stream.append("/F1 9.5 Tf\n");
            }
            stream.append("1 0 0 1 42 ").append(y).append(" Tm\n");
            stream.append("(").append(escapePdfString(line)).append(") Tj\n");
            y -= 13;
            lineCount++;
        }
        stream.append("ET\n");
        byte[] contentBytes = stream.toString().getBytes(StandardCharsets.US_ASCII);

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();

        writeAscii(pdf, "%PDF-1.4\n");

        // Object 1: Catalog
        offsets.add(pdf.size());
        writeAscii(pdf, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

        // Object 2: Pages
        offsets.add(pdf.size());
        writeAscii(pdf, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

        // Object 3: Page
        offsets.add(pdf.size());
        writeAscii(pdf, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                + "/Resources << /Font << /F1 4 0 R /F2 5 0 R >> >> /Contents 6 0 R >>\nendobj\n");

        // Object 4: Font Courier (monospace for crisp alignment)
        offsets.add(pdf.size());
        writeAscii(pdf, "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>\nendobj\n");

        // Object 5: Font Helvetica-Bold
        offsets.add(pdf.size());
        writeAscii(pdf, "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>\nendobj\n");

        // Object 6: Content stream
        offsets.add(pdf.size());
        writeAscii(pdf, "6 0 obj\n<< /Length " + contentBytes.length + " >>\nstream\n");
        pdf.writeBytes(contentBytes);
        writeAscii(pdf, "\nendstream\nendobj\n");

        int xrefOffset = pdf.size();
        writeAscii(pdf, "xref\n0 " + (offsets.size() + 1) + "\n");
        writeAscii(pdf, "0000000000 65535 f \n");
        for (int offset : offsets) {
            writeAscii(pdf, String.format("%010d 00000 n \n", offset));
        }
        writeAscii(pdf, "trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\n");
        writeAscii(pdf, "startxref\n" + xrefOffset + "\n%%EOF\n");

        return pdf.toByteArray();
    }

    private void writeAscii(ByteArrayOutputStream baos, String text) {
        baos.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    private String escapePdfString(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' || c == '(' || c == ')') {
                sb.append('\\').append(c);
            } else if (c >= 32 && c <= 126) {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        return sb.toString();
    }

    private String safeText(String val, String fallback) {
        if (val == null || val.isBlank()) {
            return fallback;
        }
        return val.trim();
    }
}

package eapdf;

/** Bridges existing console warnings and structured job evidence. */
final class ConversionDiagnostics {
  private ConversionDiagnostics() {}

  static void warn(EmailRecord record, ConversionDiagnostic.Code code, String message) {
    if (record != null) {
      if (record.conversionWarnings.contains(message)) return;
      record.conversionWarnings.add(message);
      record.diagnosticCodes.put(message, code);
    } else {
      Common.JobReport report = Common.ACTIVE_JOB.get();
      Common.PerFileLogCapture capture = Common.ACTIVE_LOG_CAPTURE.get();
      String source = capture == null ? "generated text" : capture.pdfOut.toString();
      String key = source + " | " + message;
      if (report != null && !report.addWarning(key, message,
          new ConversionDiagnostic(code, source, "", "", "", message), key)) return;
    }
    System.err.println("warning: " + redactIdentifiers(record, message));
    recordWarnings(record);
  }

  static String redactIdentifiers(EmailRecord record, String message) {
    String redacted = Common.PerFileLogCapture.redactUris(message);
    if (record == null) return redacted;
    for (String value : new String[]{record.sourcePath, record.sourceFilename, record.subject, record.messageId,
        record.from, record.to, record.cc, record.bcc, record.rawFrom, record.rawTo, record.rawCc, record.rawBcc}) {
      if (value != null && !value.isBlank() && value.length() >= 3) redacted = redacted.replace(value, "[redacted]");
    }
    return redacted;
  }

  static void recordWarnings(EmailRecord record) {
    Common.JobReport report = Common.ACTIVE_JOB.get();
    if (report == null || record == null) return;
    for (String warning : record.conversionWarnings) {
      var diagnostic = new ConversionDiagnostic(
          record.diagnosticCodes.getOrDefault(warning, ConversionDiagnostic.Code.REVIEW_REQUIRED),
          Common.nz(record.sourcePath), Common.nz(record.subject), Common.nz(record.messageId),
          Common.nz(record.mailGuid), warning);
      String identity = Common.nz(record.sourcePath).length() > 240 || Common.nz(record.subject).length() > 240
          ? Common.nz(record.mailGuid) + " | " : "";
      String key = Common.preview(record.sourcePath, 240) + " | " + Common.preview(record.subject, 240) + " | " + identity + warning;
      report.addWarning(key, warning, diagnostic, record.diagnosticIdentity + " | " + warning);
    }
  }
}

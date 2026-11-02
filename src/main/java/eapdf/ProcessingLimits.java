package eapdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class ProcessingLimits {
  static long setting(String property, String environment, long fallback) {
    String value = System.getProperty(property, System.getenv(environment));
    if (value == null || value.isBlank()) return fallback;
    try {
      long parsed = Long.parseLong(value);
      if (parsed > 0) return parsed;
    } catch (NumberFormatException ignored) {}
    throw Common.fail(environment + " must be a positive integer");
  }

  static long messageBytes() {
    return Math.min(setting("eapdf.maxMessageBytes", "EAPDF_MAX_MESSAGE_BYTES", 64L * 1024 * 1024),
        Math.max(1024, Runtime.getRuntime().maxMemory() / 16));
  }

  static int messages() {
    return (int)Math.min(Integer.MAX_VALUE, setting("eapdf.maxMessages", "EAPDF_MAX_MESSAGES", 10000));
  }

  static long metadataBytes() {
    return Math.min(setting("eapdf.maxMetadataBytes", "EAPDF_MAX_METADATA_BYTES", 32L * 1024 * 1024),
        Math.max(1024, Runtime.getRuntime().maxMemory() / 8));
  }

  static void checkMessage(long size, String context) {
    checkCancelled();
    if (size > messageBytes()) throw Common.fail("Message exceeds the processing limit of " + messageBytes() + " bytes: " + context);
  }

  static void checkCancelled() {
    if (Thread.currentThread().isInterrupted()) throw Common.fail("Email processing was cancelled");
  }

  static void checkMime(int depth, long parts) {
    checkCancelled();
    long maxDepth = Math.min(64, setting("eapdf.maxMimeDepth", "EAPDF_MAX_MIME_DEPTH", 32));
    long maxParts = setting("eapdf.maxMimeParts", "EAPDF_MAX_MIME_PARTS", 2000);
    if (depth > maxDepth || parts > maxParts) throw Common.fail("MIME structure exceeds the processing limit (depth "
        + maxDepth + ", parts " + maxParts + ")");
  }

  static void checkImage(int width, int height) {
    checkCancelled();
    long limit = Math.min(setting("eapdf.maxImagePixels", "EAPDF_MAX_IMAGE_PIXELS", 16000000),
        Math.max(1024, Runtime.getRuntime().maxMemory() / 32));
    if (width <= 0 || height <= 0 || (long) width * height > limit) {
      throw Common.fail("Image dimensions exceed the processing limit of " + limit + " pixels: " + width + "x" + height);
    }
  }

  static void checkCount(long size) {
    if (size > messages()) throw Common.fail("Export exceeds the processing limit of " + messages() + " messages/inputs");
  }

  static void checkOutput(long size) {
    checkCancelled();
    long limit = setting("eapdf.maxOutputBytes", "EAPDF_MAX_OUTPUT_BYTES", 1024L * 1024 * 1024);
    if (size > limit) throw Common.fail("Conversion exceeds the output limit of " + limit + " bytes");
  }

  static void checkPages(long pages) {
    long limit = setting("eapdf.maxPages", "EAPDF_MAX_PAGES", 10000);
    if (pages > limit) throw Common.fail("PDF exceeds the page limit of " + limit);
  }

  static void checkFont(java.nio.file.Path path) throws IOException {
    long limit = setting("eapdf.maxFontBytes", "EAPDF_MAX_FONT_BYTES", 16L * 1024 * 1024);
    if (java.nio.file.Files.size(path) > limit) throw Common.fail("Font exceeds the processing limit of " + limit + " bytes: " + path);
  }

  static void accountPages(long pages) {
    checkPages(pages);
    Common.JobReport job = Common.ACTIVE_JOB.get();
    if (job == null) return;
    long limit = setting("eapdf.maxTotalPages", "EAPDF_MAX_TOTAL_PAGES", 20000);
    if (job.renderedPages.addAndGet(pages) > limit) throw Common.fail("Conversion exceeds the aggregate page limit of " + limit);
  }

  static void accountImage(long pixels) {
    Common.JobReport job = Common.ACTIVE_JOB.get();
    if (job == null) return;
    long limit = setting("eapdf.maxTotalImagePixels", "EAPDF_MAX_TOTAL_IMAGE_PIXELS", 64000000);
    if (job.imagePixels.addAndGet(pixels) > limit) throw Common.fail("Conversion exceeds the aggregate image limit of " + limit + " pixels");
  }

  static void checkBody(String value, String context) {
    long limit = bodyCharacters();
    if (value != null && value.length() > limit) throw Common.fail("Message body exceeds the rendering limit of " + limit + " characters: " + context);
  }

  static long bodyCharacters() {
    return Math.min(setting("eapdf.maxBodyChars", "EAPDF_MAX_BODY_CHARS", 2L * 1024 * 1024),
        Math.max(1024, Runtime.getRuntime().maxMemory() / 64));
  }

  static long metadataSize(EmailRecord record) {
    long size = 2048;
    if ("mbox".equals(record.sourceType)) size += 2048; // Entry/container provenance is attached after payload staging.
    for (String value : new String[]{record.sourcePath, record.sourceFilename, record.subject, record.from, record.to,
        record.cc, record.bcc, record.rawFrom, record.rawTo, record.rawCc, record.rawBcc, record.rawReplyTo, record.folderPath,
        record.messageId, record.rawDateHeader, record.bodyMime}) size += Common.nz(value).length() * 2L;
    for (AssociatedFile file : record.associatedFiles) size += 1024
        + (Common.nz(file.filename).length() + Common.nz(file.rawContentType).length()) * 2L;
    for (String value : record.sourceProblems) size += Common.nz(value).length() * 2L + 64;
    for (String value : record.references) size += Common.nz(value).length() * 2L + 64;
    for (String value : record.inReplyTo) size += Common.nz(value).length() * 2L + 64;
    for (String value : record.folderMemberships) size += Common.nz(value).length() * 2L + 64;
    return size;
  }

  static byte[] read(InputStream input, String context) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      checkMessage((long)output.size() + count, context);
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }
}

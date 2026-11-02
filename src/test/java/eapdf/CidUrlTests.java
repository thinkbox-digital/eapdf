package eapdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CidUrlTests {
  @TempDir Path tempDir;
  static final String ID = "asset/part+tag%40@example.org";
  static final String URL = "cid:asset%2Fpart%2btag%2540%40example.org";

  static byte[] image() throws Exception {
    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
    image.setRGB(0, 0, 0xff0000); image.setRGB(1, 0, 0x00ff00);
    image.setRGB(0, 1, 0x0000ff); image.setRGB(1, 1, 0xffff00);
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    ImageIO.write(image, "png", bytes);
    return bytes.toByteArray();
  }

  static AssociatedFile inline(String id) throws Exception {
    AssociatedFile file = new AssociatedFile();
    file.filename = "inline.png"; file.mime = "image/png"; file.rawContentType = file.mime;
    file.contentId = id; file.inline = true; file.afRelationship = "Supplement"; file.bytes = image();
    return file;
  }

  static String rewrite(String url, AssociatedFile file, EmailRecord record, Path assets) throws Exception {
    Files.createDirectories(assets);
    return PdfMailWriter.rewriteCidToFileUris("<p>Inline image</p><img src=\"" + url + "\" alt=\"Four colors\" />",
        List.of(file), record, false, assets);
  }

  @Test
  void escapedAsciiIdentifiersResolveWithoutDecodingTheHeader() throws Exception {
    EmailRecord record = new EmailRecord();
    String html = rewrite(URL, inline(ID), record, tempDir);
    Assertions.assertFalse(html.contains("cid:"));
    Assertions.assertEquals(1, record.renderAssets.size());
    Assertions.assertArrayEquals(image(), record.renderAssets.values().iterator().next());
  }

  @Test
  void literalPercentSequencesSurviveExportedContentIdHeaders() throws Exception {
    EmailRecord record = new EmailRecord();
    record.textBody = "Body"; record.htmlBody = "<img src=\"" + URL + "\" />";
    record.associatedFiles.add(inline(ID));
    String eml = new String(Common.synthesizeEml(record), StandardCharsets.UTF_8);
    Assertions.assertTrue(eml.contains("Content-ID: <" + ID + ">\r\n"), eml);
  }

  @Test
  void unescapedPlusIsNotAFormSpace() throws Exception {
    EmailRecord record = new EmailRecord();
    Assertions.assertFalse(rewrite("cid:one+two@example.org", inline("one+two@example.org"), record, tempDir).contains("cid:"));
  }

  @Test
  void escapedBracketsAreNormalizedAfterUrlDecoding() throws Exception {
    EmailRecord record = new EmailRecord();
    Assertions.assertFalse(rewrite("cid:%3cone%40example.org%3e", inline("one@example.org"), record, tempDir).contains("cid:"));
  }

  @Test
  void malformedEscapesDoNotAbortOrMatchAnotherIdentifier() throws Exception {
    EmailRecord record = new EmailRecord();
    Assertions.assertTrue(rewrite("cid:one%GG@example.org", inline("other@example.org"), record, tempDir).contains("cid:"));
    Assertions.assertTrue(record.renderAssets.isEmpty());
  }

  @Test
  void actualContentIdTakesPrecedenceOverAnEarlierFilenameAlias() throws Exception {
    AssociatedFile alias = inline("unrelated@example.org");
    alias.filename = "wanted@example.org";
    alias.bytes = new byte[]{1, 2, 3};
    AssociatedFile actual = inline("wanted@example.org");
    EmailRecord record = new EmailRecord();
    PdfMailWriter.rewriteCidToFileUris("<img src=\"cid:wanted@example.org\" />",
        List.of(alias, actual), record, false, tempDir);
    Assertions.assertArrayEquals(actual.data(), record.renderAssets.values().iterator().next());
  }

  static byte[] message() throws Exception {
    String raw = "From: sender@example.org\r\nTo: recipient@example.org\r\nSubject: Escaped inline image\r\n"
        + "Date: Thu, 22 Oct 2026 12:00:00 +0000\r\nMessage-ID: <cid-url@example.org>\r\n"
        + "MIME-Version: 1.0\r\nContent-Type: multipart/related; boundary=cid-boundary\r\n\r\n"
        + "--cid-boundary\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<p>Inline image</p><img src=\"" + URL + "\" width=\"40\" height=\"40\" alt=\"Four colors\" />\r\n"
        + "--cid-boundary\r\nContent-Type: image/png\r\nContent-ID: <" + ID + ">\r\n"
        + "Content-Disposition: inline; filename=inline.png\r\nContent-Transfer-Encoding: base64\r\n\r\n"
        + Base64.getMimeEncoder().encodeToString(image()) + "\r\n--cid-boundary--\r\n";
    return raw.getBytes(StandardCharsets.UTF_8);
  }

  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]); Files.createDirectories(directory);
    Files.write(directory.resolve("inline.eml"), message());
    EmailRecord record = new EmailRecord();
    String html = rewrite(URL, inline(ID), record, directory.resolve("assets"));
    Files.writeString(directory.resolve("rewritten.html"), html);
    if (args.length > 2) {
      AssociatedFile alias = inline("unrelated@example.org");
      alias.filename = "wanted@example.org";
      alias.bytes = new byte[]{1, 2, 3};
      PdfMailWriter.rewriteCidToFileUris("<img src=\"cid:wanted@example.org\" />",
          List.of(alias, inline("wanted@example.org")), new EmailRecord(), false, directory.resolve("assets"));
    }
    if (args.length > 1) Thread.sleep(45000);
  }
}

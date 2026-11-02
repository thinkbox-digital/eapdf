package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

public class CreatorStructureProbe {
  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    var email = EmlReader.readRfc822Bytes(Files.readAllBytes(Path.of(
        "docs/improvement/creator-header-operands/input.eml")), "input.eml", "input.eml", "", List.of(), false);
    var result = PdfMailWriter.buildSingleMessagePdf(directory.resolve("header.pdf"), email,
        Common.resolveFontPath(null), Common.resolveIccPath(null), false, directory.resolve("scratch"), 1, "s");
    Files.write(directory.resolve("header.pdf"), result.pdfBytes);
    try (PDDocument document = new PDDocument()) {
      for (int index = 0; index < 3; index++) document.addPage(new PDPage());
      EmailRecord second = new EmailRecord();
      second.mailGuid = "second-owner";
      var segments = List.of(new PdfMailWriter.MessageSegment(email, 2, 2),
          new PdfMailWriter.MessageSegment(second, 3, 3));
      PdfMailWriter.addArchiveDPartStructure(document, segments, 1);
      PdfMailWriter.addArchiveLogicalStructure(document, segments, 1);
      document.save(directory.resolve("archive-tree.pdf").toFile());
    }
    Files.writeString(directory.resolve("completed.txt"), "Creator probe completed.\n");
  }
}

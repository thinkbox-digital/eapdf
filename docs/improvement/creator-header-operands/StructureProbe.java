package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class StructureProbe {
  public static void main(String[] args) throws Exception {
    Path input = Path.of(args[0]);
    Path output = Path.of(args[1]);
    Files.createDirectories(output.getParent());
    var email = EmlReader.readRfc822Bytes(Files.readAllBytes(input), input.getFileName().toString(),
        input.getFileName().toString(), "", List.of(), false);
    var result = PdfMailWriter.buildSingleMessagePdf(output, email, Common.resolveFontPath(null),
        Common.resolveIccPath(null), false, output.getParent(), 1, "s");
    Files.write(output, result.pdfBytes);
  }
}

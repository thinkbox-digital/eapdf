package eapdf;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CreationValidationOptionTests {
  @TempDir Path directory;

  @Test void validationIsDefaultAndExplicitSkippingDoesNotClaimChecksPassed() throws Exception {
    assertTrue(new EapdfConfig().validateOnCreation);
    var config = EapdfConfig.get();
    boolean previous = config.validateOnCreation;
    Path output = directory.resolve("output.pdf");
    Files.writeString(output, "existing output");
    try {
      config.validateOnCreation = true;
      assertThrows(Exception.class, () -> Common.writePdfAtomically(output, p -> Files.writeString(p, "not a PDF")));
      assertEquals("existing output", Files.readString(output));
      config.validateOnCreation = false;
      Common.writePdfAtomically(output, p -> Files.writeString(p, "not a PDF"));
      assertEquals("not a PDF", Files.readString(output));
      Path workspace = Files.createDirectory(directory.resolve("workspace"));
      ConversionProgress.start("eapdf.EmailToPDFA", new String[]{"--out", directory.toString(), "in/sample.eml"}, workspace);
      ConversionProgress.attach(workspace);
      ConversionProgress.creationChecked(output, output, null, false);
      var progress = com.google.gson.JsonParser.parseString(Files.readString(directory.resolve("progress.json"))).getAsJsonObject();
      var receipt = progress.getAsJsonArray("creation_receipts").get(0).getAsJsonObject();
      assertEquals("SKIPPED", receipt.get("creation_checks").getAsString());
      assertEquals("NOT_RUN", receipt.get("preservation_comparison").getAsString());
    } finally {
      ConversionProgress.detach();
      config.validateOnCreation = previous;
    }
  }
}

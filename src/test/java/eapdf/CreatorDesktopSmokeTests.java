package eapdf;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Real Swing actions; opt in on a graphical desktop with eapdf.creator.uiTest=true. */
@EnabledIfSystemProperty(named = "eapdf.creator.uiTest", matches = "true")
class CreatorDesktopSmokeTests {
  @TempDir Path temporary;

  @Test void choosesInputAndOutputReportsErrorsConvertsAndStops() throws Exception {
    String evidence = System.getProperty("eapdf.ui.evidence");
    CreatorDesktopSmoke.run(Path.of("in/sample.eml"), temporary, evidence == null ? null : Path.of(evidence));
  }
}

package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ComparisonRegressionTests {
  @org.junit.jupiter.api.BeforeEach void requireLocalCorpus() {
    org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(Path.of("../tests/fixtures/eapdf/upstream-issues/createdate-equal.pdf")), "Optional local regression corpus is not installed");
  }
  private static final Path FIXTURES = Path.of("..", "tests", "fixtures", "eapdf");

  @Test void standardOperandMustStillMatchItsCustomStructureOwner() {
    var result = JavaValidation.validate(FIXTURES.resolve("upstream-issues/createdate-equal.pdf"));
    assertTrue(JavaValidationTests.has(result, "CUSTOM_TAG_OPERAND_MATCH", "FAIL"));
  }

  @Test void upstreamFailuresKeepTheirObjectLocationsAndErrorText() {
    var result = JavaValidation.pdfa(FIXTURES.resolve("upstream-profile-controls/4-launch.pdf"), null);
    var failure = result.getAsJsonArray("findings").get(0).getAsJsonObject();
    assertTrue(failure.get("context").getAsString().startsWith("root/document[0]/OpenAction"));
    assertTrue(failure.get("error").getAsString().contains("Launch"));
  }

  @Test void invalidRootDoesNotSkipActualSelectedIsoValidation() {
    var result = JavaValidation.validate(FIXTURES.resolve("upstream-profile-controls/4-valid.pdf"));
    assertTrue(JavaValidationTests.has(result, "STRUCTTREE_ROOT_DOCUMENT", "FAIL"));
    assertTrue(JavaValidationTests.has(result, "ISO32005_UPSTREAM", "FAIL"));
    assertTrue(JavaValidationTests.has(result, "LOGICAL_TS32005_DECLARATION", "FAIL"));
  }
}

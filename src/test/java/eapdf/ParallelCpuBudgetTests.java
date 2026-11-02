package eapdf;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ParallelCpuBudgetTests {
  @Test void nativeCpuAllowanceAccountsForConcurrentWorkerThreads() {
    assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
    String previous = System.getProperty("eapdf.jobTimeoutSeconds");
    try {
      System.setProperty("eapdf.jobTimeoutSeconds", "7");
      var command = ConversionSupervisor.nativeLimits(List.of("java", "example"));
      assertTrue(command.contains("--cpu=" + 7L * Math.min(4, Runtime.getRuntime().availableProcessors())));
      assertEquals(List.of("java", "example"), command.subList(command.size() - 2, command.size()));
    } finally {
      if (previous == null) System.clearProperty("eapdf.jobTimeoutSeconds");
      else System.setProperty("eapdf.jobTimeoutSeconds", previous);
    }
  }
}

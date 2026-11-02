package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class PstHeaderProbe {
  public static void main(String[] args) throws Exception {
    var records = PstReader.readPst("in/email/test.pst", "", List.of(), false);
    Path directory = Path.of(args[0]);
    Files.createDirectories(directory);
    int nulCc = 0;
    int nulBcc = 0;
    for (EmailRecord record : records) {
      if (record.rawCc.indexOf(0) >= 0) nulCc++;
      if (record.rawBcc.indexOf(0) >= 0) nulBcc++;
    }
    Files.writeString(directory.resolve("summary.txt"), "messages=" + records.size()
        + " nulCc=" + nulCc + " nulBcc=" + nulBcc + "\n");
    Files.write(directory.resolve("first.eml"), Common.synthesizeEml(records.get(0)));
    // Keep the remote session alive long enough to drain its buffered logpoints.
    Thread.sleep(45000);
  }
}

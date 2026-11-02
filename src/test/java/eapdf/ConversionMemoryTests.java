package eapdf;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ConversionMemoryTests {
 @TempDir Path directory;
 @Test void availableMemoryLeavesHeadroomWithoutAnArbitraryFourGiBCeiling() {
  long gib=ConversionMemory.GIB;
  assertEquals(10*gib,ConversionMemory.automaticHeap(20*gib,512*ConversionMemory.MIB));
  assertEquals(gib,ConversionMemory.automaticHeap(2*gib,512*ConversionMemory.MIB));
  assertEquals(512*ConversionMemory.MIB,ConversionMemory.automaticHeap(-1,512*ConversionMemory.MIB));
  assertThrows(Common.UserFacingException.class,()->ConversionMemory.automaticHeap(100*ConversionMemory.MIB,512*ConversionMemory.MIB));
 }
 @Test void containerHeadroomAndExplicitLauncherLimitsAreRespected() throws Exception {
  Path limit=directory.resolve("limit"),used=directory.resolve("used");long gib=ConversionMemory.GIB;
  Files.writeString(limit,Long.toString(8*gib));Files.writeString(used,Long.toString(6*gib));
  assertEquals(2*gib,ConversionMemory.containerAvailable(20*gib,limit,used));
  Files.writeString(limit,"max");assertEquals(20*gib,ConversionMemory.containerAvailable(20*gib,limit,used));
  assertEquals(Long.MAX_VALUE,ConversionMemory.launcherLimit(List.of(),2*gib));
  assertEquals(2*gib,ConversionMemory.launcherLimit(List.of("-Xmx2g"),2*gib));
  assertEquals(2*gib,ConversionMemory.launcherLimit(List.of("-XX:MaxHeapSize=2147483648"),2*gib));
 }
}

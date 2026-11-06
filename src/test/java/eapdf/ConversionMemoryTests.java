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
 @Test void macUsesNativePageSizeAndIncludesInactiveMemoryWithoutDoubleCounting() {
  String counters = "Pages free: 1024.\nPages inactive: 131072.\nPages active: 999999.\nPages wired down: 999999.\nPages purgeable: 131072.\nPages occupied by compressor: 999999.\n";
  for (long size : new long[]{4096,16384}) {
   long expected = (1024L+131072)*size;
   long available = ConversionMemory.macAvailableBytes("Mach Virtual Memory Statistics: (page size of " + size + " bytes)\n" + counters);
   assertEquals(expected, available);
   assertTrue(ConversionMemory.automaticHeap(available,512*ConversionMemory.MIB)>=256*ConversionMemory.MIB);
  }
 }
 @Test void malformedMacStatisticsFallBackRatherThanInventMemory() {
  assertEquals(-1,ConversionMemory.macAvailableBytes("permission denied"));
  assertEquals(-1,ConversionMemory.macAvailableBytes("page size of 4096 bytes\nPages free: 10.\n"));
  assertEquals(-1,ConversionMemory.macAvailableBytes("page size of 4096 bytes\nPages free: 9223372036854775807.\nPages inactive: 1.\n"));
  assertEquals(0,ConversionMemory.macAvailableBytes("page size of 16384 bytes\nPages free: 0.\nPages inactive: 0.\n"));
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

package eapdf;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.List;

/** Choose a future worker heap without treating the launcher's automatic heap as a limit. */
final class ConversionMemory {
  static final long MIB = 1024L * 1024, GIB = 1024 * MIB;

  static long availableBytes() {
    long available = -1;
    try {
      for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
        if (line.startsWith("MemAvailable:")) {
          available = Math.multiplyExact(Long.parseLong(line.split("\\s+")[1]), 1024);
          break;
        }
      }
    } catch (Exception ignored) { }
    if (available < 0 && ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
      available = os.getFreeMemorySize();
    // A container may expose host /proc/meminfo; respect its remaining allowance.
    available = containerAvailable(available, Path.of("/sys/fs/cgroup/memory.max"), Path.of("/sys/fs/cgroup/memory.current"));
    available = containerAvailable(available, Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"), Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"));
    return available;
  }

  static long containerAvailable(long available, Path limitPath, Path usedPath) {
    try {
      String raw = Files.readString(limitPath).trim();
      if (raw.equals("max")) return available;
      long limit = Long.parseLong(raw), used = Long.parseLong(Files.readString(usedPath).trim());
      if (limit <= 0 || used < 0) return available;
      long remaining = Math.max(0, limit - used);
      return available < 0 ? remaining : Math.min(available, remaining);
    } catch (Exception ignored) { return available; }
  }

  static long automaticHeap(long available, long fallback) {
    if (available < 0) return fallback;
    // At least half remains for the OS, other programs, native allocations and
    // scratch files (which can themselves be on tmpfs). Round down to whole MiB.
    long heap = available / 2 / MIB * MIB;
    if (heap < 256 * MIB) throw Common.fail("Not enough available memory to start a conversion. Free some memory and try again.");
    return heap;
  }

  static long launcherLimit(List<String> arguments, long maximum) {
    return arguments.stream().anyMatch(a -> a.startsWith("-Xmx") || a.startsWith("-XX:MaxHeapSize=") || a.startsWith("-XX:MaxRAMPercentage=") || a.startsWith("-XX:MaxRAM="))
        ? maximum : Long.MAX_VALUE;
  }

  static long workerHeap(long suggested) {
    long requested = ProcessingLimits.setting("eapdf.workerHeapBytes", "EAPDF_WORKER_HEAP_BYTES", suggested);
    long launcher = launcherLimit(ManagementFactory.getRuntimeMXBean().getInputArguments(), Runtime.getRuntime().maxMemory());
    return Math.min(requested, launcher);
  }

  static long defaultHeap(long fallback) {
    // Do not second-guess an explicit worker setting, including on a busy host.
    if (System.getProperty("eapdf.workerHeapBytes") != null || System.getenv("EAPDF_WORKER_HEAP_BYTES") != null)
      return workerHeap(fallback);
    return workerHeap(automaticHeap(availableBytes(), fallback));
  }
}

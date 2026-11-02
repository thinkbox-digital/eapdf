package eapdf;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.Comparator;

/** Resource allowances and private disk scratch for a single validation worker. */
final class ValidationRuntime {
    static long setting(String name, long fallback) {
        String value = System.getProperty(name, System.getenv(name));
        return value == null || value.isBlank() ? fallback : Math.max(1, Long.parseLong(value));
    }
    static java.util.List<String> boundedCommand(java.util.List<String> command, long heapMiB) throws IOException {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux")) return command;
        Path limiter = Path.of("/usr/bin/prlimit");
        if (!Files.isExecutable(limiter)) throw new IOException("Linux validation requires /usr/bin/prlimit (util-linux)");
        var bounded = new java.util.ArrayList<String>();
        bounded.add(limiter.toString()); bounded.add("--as=" + Math.multiplyExact(heapMiB + 4096, 1024L * 1024));
        bounded.add("--fsize=" + setting("VALIDATOR_MAX_WORK_FILE_BYTES", 64L * 1024 * 1024));
        bounded.add("--nofile=256"); bounded.add("--core=0"); bounded.addAll(command); return bounded;
    }
    static boolean scratchExceeded(Path directory, Path report) throws IOException {
        long maximum = setting("VALIDATOR_MAX_SCRATCH_BYTES", 512L * 1024 * 1024);
        long[] totals = {0, 0};
        boolean[] exceeded = {false};
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && (++totals[1] > 10000 || attributes.size() > maximum - totals[0])) {
                    exceeded[0] = true; return FileVisitResult.TERMINATE;
                }
                if (attributes.isRegularFile()) totals[0] += attributes.size();
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                if (error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                throw error;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null && !(error instanceof NoSuchFileException)) throw error;
                return FileVisitResult.CONTINUE;
            }
        });
        if (exceeded[0]) return true;
        try { return Files.isRegularFile(report) && Files.size(report) > setting("VALIDATOR_MAX_WORK_FILE_BYTES", 64L * 1024 * 1024); }
        catch (NoSuchFileException ignored) { return false; }
    }
    static long workerHeapMiB() {
        long available = -1;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) if (line.startsWith("MemAvailable:")) {
                available = Long.parseLong(line.split("\\s+")[1]) * 1024; break;
            }
        } catch (Exception ignored) { }
        if (available < 0 && ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os)
            available = os.getFreeMemorySize();
        for (String[] pair : new String[][]{{"/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory.current"},
                {"/sys/fs/cgroup/memory/memory.limit_in_bytes", "/sys/fs/cgroup/memory/memory.usage_in_bytes"}}) try {
            String limit = Files.readString(Path.of(pair[0])).strip();
            if (!"max".equals(limit)) {
                long remaining = Math.max(0, Long.parseLong(limit) - Long.parseLong(Files.readString(Path.of(pair[1])).strip()));
                available = available < 0 ? remaining : Math.min(available, remaining);
            }
        } catch (Exception ignored) { }
        if (available < 0) return 512;
        long mib = (available / 3 * 2) / (1024 * 1024);
        if (mib < 256) throw new IllegalStateException("Not enough available memory to start validation.");
        return mib;
    }
    static long timeoutSeconds(long bytes) {
        String configured = System.getenv("VALIDATOR_JOB_TIMEOUT");
        if (configured != null && !configured.isBlank()) return Math.max(1, Long.parseLong(configured));
        return Math.min(86400, Math.max(600, 600 + bytes / (128 * 1024)));
    }
    static Path temporaryDirectory() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        String type = Files.getFileStore(root).type().toLowerCase(java.util.Locale.ROOT);
        if (type.equals("tmpfs") || type.equals("ramfs")) {
            Path disk = Path.of(System.getProperty("user.dir"), ".generator_web_tmp");
            String diskType = Files.getFileStore(disk.toAbsolutePath().getParent()).type().toLowerCase(java.util.Locale.ROOT);
            if (!diskType.equals("tmpfs") && !diskType.equals("ramfs")) { Files.createDirectories(disk); root = disk; }
        }
        return Files.getFileStore(root).supportsFileAttributeView("posix")
            ? Files.createTempDirectory(root, "eapdf-validator-", java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
            : Files.createTempDirectory(root, "eapdf-validator-");
    }
    static void removeDirectory(Path directory) throws IOException {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}

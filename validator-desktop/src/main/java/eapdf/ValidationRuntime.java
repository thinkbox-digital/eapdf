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
    // veraPDF can retain a scratch stream per decoded PDF stream. A small
    // fixed descriptor cap rejects otherwise valid multi-page mail archives.
    static long openFileLimit() {
        long inherited = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.UnixOperatingSystemMXBean os
            ? os.getMaxFileDescriptorCount() : 16384;
        long requested = setting("VALIDATOR_MAX_OPEN_FILES", Long.MAX_VALUE);
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/limits"))) if (line.startsWith("Max open files")) {
                String[] columns = line.substring("Max open files".length()).strip().split("\\s+");
                if (columns.length > 1 && !columns[1].equals("unlimited")) requested = Math.min(requested, Long.parseLong(columns[1]));
                break;
            }
        } catch (IOException | NumberFormatException ignored) { }
        return requested == Long.MAX_VALUE ? inherited : requested;
    }
    private static final class DiskBudget {
        static final long FILE = availableDiskShare();
    }
    private static long availableDiskShare() {
        try {
            Path root = Path.of(System.getProperty("java.io.tmpdir"));
            String type = Files.getFileStore(root).type().toLowerCase(java.util.Locale.ROOT);
            if (type.equals("tmpfs") || type.equals("ramfs")) {
                Path project = Path.of(System.getProperty("user.dir"));
                String projectType = Files.getFileStore(project).type().toLowerCase(java.util.Locale.ROOT);
                if (!projectType.equals("tmpfs") && !projectType.equals("ramfs")) root = project;
            }
            // Reserve room for other work and simultaneous validation jobs.
            return Math.max(1, Files.getFileStore(root).getUsableSpace() / 8);
        } catch (IOException error) { throw new IllegalStateException("Cannot determine available validation storage", error); }
    }
    static long workFileLimit() {
        return setting("VALIDATOR_MAX_WORK_FILE_BYTES", DiskBudget.FILE);
    }
    static long scratchLimit() {
        return setting("VALIDATOR_MAX_SCRATCH_BYTES", DiskBudget.FILE * 2);
    }
    static long addressLimit(long heapMiB) {
        // RLIMIT_AS counts mapped files even when their pages are not resident.
        // Leave address space for all admitted scratch as well as heap/native work.
        return Math.addExact(Math.multiplyExact(heapMiB + 4096, 1024L * 1024), scratchLimit());
    }
    static java.util.List<String> boundedCommand(java.util.List<String> command, long heapMiB) throws IOException {
        if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux")) return command;
        Path limiter = Path.of("/usr/bin/prlimit");
        if (!Files.isExecutable(limiter)) throw new IOException("Linux validation requires /usr/bin/prlimit (util-linux)");
        var bounded = new java.util.ArrayList<String>();
        bounded.add(limiter.toString()); bounded.add("--as=" + addressLimit(heapMiB));
        bounded.add("--fsize=" + workFileLimit());
        bounded.add("--nofile=" + openFileLimit()); bounded.add("--core=0");
        bounded.add(command.getFirst());
        if (Path.of(command.getFirst()).getFileName().toString().equals("java")) {
            // Freeze the controller's disk share for the worker and its children.
            bounded.add("-DVALIDATOR_MAX_WORK_FILE_BYTES=" + workFileLimit());
            bounded.add("-DVALIDATOR_MAX_SCRATCH_BYTES=" + scratchLimit());
        }
        bounded.addAll(command.subList(1, command.size())); return bounded;
    }
    static boolean scratchExceeded(Path directory, Path report) throws IOException {
        long maximum = scratchLimit();
        long[] totals = {0};
        boolean[] exceeded = {false};
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && (attributes.size() > maximum - totals[0])) {
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
        try { return Files.isRegularFile(report) && Files.size(report) > workFileLimit(); }
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

package eapdf;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** One validation owns its report and both derived sidecars for the lifetime of the controller. */
record ValidationOutputs(Path report, Path summary, Path progress) {
    private static final String LOCK_FILE = ".eapdf-validation.lock";

    static ValidationOutputs of(Path output) throws IOException {
        Path report = entry(output);
        return new ValidationOutputs(report, sidecar(report, ".summary.json"), sidecar(report, ".progress.json"));
    }

    static Path entry(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (absolute.getParent() == null || absolute.getFileName() == null)
            throw new IOException("Choose a report filename.");
        return absolute.getParent().toRealPath().resolve(absolute.getFileName());
    }

    private static Path sidecar(Path report, String suffix) {
        return report.resolveSibling(report.getFileName() + suffix);
    }

    static void protect(Path destination, List<Path> inputs) throws IOException {
        Path target = entry(destination);
        for (Path input : inputs) {
            // The lexical check also covers a selected file that was moved away before the run.
            Path parent = input.toAbsolutePath().getParent();
            if (target.equals(input.toAbsolutePath().normalize())
                || (parent != null && Files.exists(parent) && target.equals(entry(input)))
                || (Files.exists(target) && Files.exists(input) && Files.isSameFile(target, input)))
                throw new IOException("The report and sidecars must not overwrite an input file: " + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The report destination must be a regular file: " + target);
    }

    void protectInput(Path input) throws IOException {
        var inputs = protectedInputs(input);
        var paths = List.of(report, summary, progress, lockPath());
        for (int i = 0; i < paths.size(); i++) {
            protect(paths.get(i), inputs);
            for (int j = 0; j < i; j++) {
                Path first = paths.get(i), second = paths.get(j);
                if (first.equals(second) || (Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second)))
                    throw new IOException("Validation report paths collide: " + first);
            }
        }
    }

    static List<Path> protectedInputs(Path input) { return List.of(input); }

    private Path lockPath() { return report.resolveSibling(LOCK_FILE); }

    FileChannel claim(Path input) throws IOException {
        protectInput(input);
        // Keep a single empty lock file per report directory. Closing the channel releases
        // the byte-range leases, even after process termination. Removing the inode would
        // permit a second process to lock its replacement while the first still held it.
        var channel = FileChannel.open(lockPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean claimed = false;
        try {
            for (Path path : List.of(report, summary, progress)) {
                if (channel.tryLock(lockPosition(path), 1, false) == null)
                    throw new IOException("Another validation is using the report path: " + path);
            }
            protectInput(input);
            verifyPreviousSidecars();
            claimed = true;
            return channel;
        } catch (OverlappingFileLockException collision) {
            throw new IOException("Another validation is using the report paths: " + report, collision);
        } finally {
            if (!claimed) channel.close();
        }
    }

    private static long lockPosition(Path path) {
        String name = java.text.Normalizer.normalize(path.getFileName().toString(), java.text.Normalizer.Form.NFC)
            .toLowerCase(Locale.ROOT);
        try {
            // Reserve the high bits so a one-byte range never overflows.
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return java.nio.ByteBuffer.wrap(digest).getLong() & 0x3fff_ffff_ffff_ffffL;
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    void verifyPreviousSidecars() throws IOException {
        JsonObject prior = previousObject(summary);
        boolean ownedSummary = prior != null && prior.has("failure_codes") && prior.has("pdfa")
            && prior.get("summary") instanceof JsonObject metadata
            && hasText(metadata, "validation_target", "eapdf")
            && (!prior.has("report") || hasText(prior, "report", report.toString()));
        if (Files.exists(summary, LinkOption.NOFOLLOW_LINKS) && !ownedSummary)
            throw new IOException("Refusing to replace an unrecognized validation sidecar: " + summary);

        if (!Files.exists(progress, LinkOption.NOFOLLOW_LINKS)) return;
        JsonObject state = previousObject(progress);
        if (state != null && hasText(state, "validation_target", "eapdf")
            && hasText(state, "report", report.toString()) && state.has("run_id")) return;
        // Older releases wrote progress without an owner field. A matching report
        // summary and the original progress shape permit reusing that report name.
        if (ownedSummary && state != null && state.size() == 4
            && state.has("stage") && state.has("detail") && state.has("elapsed_seconds") && state.has("updated")) {
            try {
                Instant.parse(JavaValidation.text(state, "updated"));
                if (state.get("elapsed_seconds").isJsonPrimitive() && state.getAsJsonPrimitive("elapsed_seconds").isNumber()) return;
            } catch (RuntimeException malformed) { /* preserve an unrecognized file */ }
        }
        throw new IOException("Refusing to replace an unrecognized validation sidecar: " + progress);
    }

    private static boolean hasText(JsonObject object, String key, String expected) {
        return object.get(key) instanceof com.google.gson.JsonPrimitive value
            && value.isString() && expected.equals(value.getAsString());
    }

    private static JsonObject previousObject(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024) return null;
        try (var reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader) instanceof JsonObject object ? object : null;
        } catch (RuntimeException malformed) { return null; }
    }
}

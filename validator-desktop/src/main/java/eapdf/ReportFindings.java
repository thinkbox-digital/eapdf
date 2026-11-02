package eapdf;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Keeps full desktop findings on disk, with only row offsets and group names in memory. */
final class ReportFindings extends AbstractList<ValidatorGui.Finding> implements AutoCloseable {
    private final Path directory, original;
    private final RandomAccessFile data;
    private long[] offsets = new long[1024];
    private int count;
    private boolean contradictory;
    private record Key(String level, String code, String message) { }
    private final Map<Key, Positions> grouped = new LinkedHashMap<>();
    private final class Positions extends AbstractList<ValidatorGui.Finding> {
        private int[] rows = new int[16];
        private int length;
        void append(int row) { if (length == rows.length) rows = Arrays.copyOf(rows, Math.multiplyExact(length, 2)); rows[length++] = row; }
        @Override public int size() { return length; }
        @Override public ValidatorGui.Finding get(int index) { Objects.checkIndex(index, length); return ReportFindings.this.get(rows[index]); }
    }
    ReportFindings(Path report) throws IOException {
        directory = ValidationRuntime.temporaryDirectory();
        original = directory.resolve("report.json");
        try {
            Files.copy(report, original);
            data = new RandomAccessFile(directory.resolve("findings.bin").toFile(), "rw");
        } catch (IOException error) { ValidationRuntime.removeDirectory(directory); throw error; }
    }
    void append(JsonObject item) throws IOException {
        var finding = ValidatorGui.Result.finding(item);
        if (count == offsets.length) offsets = Arrays.copyOf(offsets, Math.multiplyExact(count, 2));
        byte[] bytes = item.toString().getBytes(StandardCharsets.UTF_8);
        offsets[count] = data.length(); data.seek(offsets[count]); data.writeInt(bytes.length); data.write(bytes);
        grouped.computeIfAbsent(new Key(finding.level(), finding.code(), finding.message()), ignored -> new Positions()).append(count++);
        contradictory |= "FAIL".equals(finding.level()) || ("WARN".equals(finding.level()) && "VALIDATION_INCOMPLETE".equals(finding.code()));
    }
    boolean contradictsPass() { return contradictory; }
    List<ValidatorGui.FindingGroup> groups() {
        var result = new ArrayList<ValidatorGui.FindingGroup>();
        grouped.forEach((key, positions) -> result.add(new ValidatorGui.FindingGroup(key.level(), key.code(), key.message(), positions)));
        return ValidatorGui.sortGroups(result);
    }
    @Override public int size() { return count; }
    @Override public synchronized ValidatorGui.Finding get(int index) {
        Objects.checkIndex(index, count);
        try {
            data.seek(offsets[index]); byte[] bytes = new byte[data.readInt()]; data.readFully(bytes);
            return ValidatorGui.Result.finding(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    void save(Path destination) throws IOException { Files.copy(original, destination, StandardCopyOption.REPLACE_EXISTING); }
    @Override public void close() throws IOException { try { data.close(); } finally { ValidationRuntime.removeDirectory(directory); } }
}

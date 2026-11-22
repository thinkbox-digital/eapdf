package eapdf;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidationDestinationTests {
    @TempDir Path directory;

    @Test void everyReportPathIsCheckedBeforeWritingEitherSidecar() throws Exception {
        Path report = directory.resolve("report.json");
        ValidationOutputs paths = ValidationOutputs.of(report);
        for (Path input : java.util.List.of(paths.report(), paths.summary(), paths.progress())) {
            Files.writeString(input, "preserve this input");
            assertThrows(IOException.class, () -> ValidationWorker.run(input, report));
            assertEquals("preserve this input", Files.readString(input));
            try (var children = Files.list(directory)) {
                assertEquals(java.util.List.of(input), children.toList());
            }
            Files.delete(input);
        }
    }

    @Test void hardLinksAndSymlinkParentsCannotHideACollision() throws Exception {
        Path report = directory.resolve("report.json");
        var outputs = ValidationOutputs.of(report);
        Path input = Files.writeString(directory.resolve("input.pdf"), "preserve");
        Files.createLink(outputs.summary(), input);
        assertThrows(IOException.class, () -> ValidationWorker.run(input, report));
        Files.delete(outputs.summary());
        Path linkedDirectory = Files.createSymbolicLink(directory.resolve("alias"), directory);
        Path colliding = Files.writeString(outputs.progress(), "preserve progress input");
        assertThrows(IOException.class, () -> ValidationWorker.run(linkedDirectory.resolve(colliding.getFileName()), report));
        assertEquals("preserve progress input", Files.readString(colliding));
        assertFalse(Files.exists(report));
    }

    @Test void unrecognizedSidecarsArePreserved() throws Exception {
        Path report = directory.resolve("report.json");
        var outputs = ValidationOutputs.of(report);
        for (Path path : java.util.List.of(outputs.summary(), outputs.progress())) {
            Files.writeString(path, "unrelated operator notes");
            assertThrows(IOException.class, () -> ValidationWorker.run(directory.resolve("missing.pdf"), report));
            assertEquals("unrelated operator notes", Files.readString(path));
            assertFalse(Files.exists(report));
            Files.delete(path);
        }
    }

    @Test void missingInputReplacesAnEarlierOutcomeWithANewIncompleteRun() throws Exception {
        Path input = Files.writeString(directory.resolve("input.pdf"), "first input");
        var outputs = ValidationOutputs.of(directory.resolve("report.json"));
        try (var session = new ValidationSession(input, outputs.report(), "previous", false);
             var report = new JavaValidation.Report(input.toString())) {
            report.pdfa = JsonParser.parseString("{\"status\":\"PASS\"}").getAsJsonObject();
            report.write(outputs.report(), session);
            session.completed();
        }
        assertEquals("PASS", summary(outputs.summary()).get("status").getAsString());
        assertEquals("previous", summary(outputs.report()).get("run_id").getAsString());

        Files.delete(input);
        assertEquals(1, ValidationWorker.run(input, outputs.report()));
        var current = summary(outputs.report());
        assertEquals("INCOMPLETE", current.get("status").getAsString());
        assertEquals("INCOMPLETE", current.get("run_state").getAsString());
        assertNotEquals("previous", current.get("run_id").getAsString());
        assertEquals(current, summary(outputs.summary()));
        var progress = JsonParser.parseString(Files.readString(outputs.progress())).getAsJsonObject();
        assertEquals("Incomplete", progress.get("stage").getAsString());
        assertEquals(current.get("run_id"), progress.get("run_id"));
    }

    private static JsonObject summary(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject().getAsJsonObject("summary");
    }
}

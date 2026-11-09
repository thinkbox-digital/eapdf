package eapdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MetadataLimitTests {
    @TempDir Path directory;
    @Test void deepMetadataIsInspectedAndSourceRemainsUnchanged() throws Exception {
        Path original = Path.of("../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf");
        byte[] before = Files.readAllBytes(original);
        Path file = directory.resolve("deep.pdf");
        try (var pdf = Loader.loadPDF(before)) {
            pdf.getDocumentCatalog().setMetadata(new PDMetadata(pdf,
                new java.io.ByteArrayInputStream(("<x>".repeat(65)+"</x>".repeat(65)).getBytes(StandardCharsets.UTF_8))));
            pdf.save(file.toFile());
        }
        var result = JavaValidation.validate(file);
        var items = result.getAsJsonArray("items");
        assertFalse(items.asList().stream().map(v->v.getAsJsonObject())
            .anyMatch(v->"EAPDF_XMP_UNCHECKED".equals(v.get("code").getAsString()) && "WARN".equals(v.get("level").getAsString())));
        assertFalse(items.asList().stream().map(v->v.getAsJsonObject())
            .anyMatch(v->"EAPDF_XMP_UNCHECKED".equals(v.get("code").getAsString()) && "FAIL".equals(v.get("level").getAsString())));
        assertArrayEquals(before, Files.readAllBytes(original));
    }
    @Test void unreadableXmpDoesNotInventIdentityOrDeclarationMismatches() throws Exception {
        String old = System.getProperty("VALIDATOR_MAX_METADATA_BYTES");
        try {
            System.setProperty("VALIDATOR_MAX_METADATA_BYTES", "1");
            var result = JavaValidation.validate(Path.of("../examples/pst/pst_quality-attached-message_1m.pdf"));
            var dependent = java.util.Set.of("DPM_MAIL_GUID_MATCH", "DPM_MAIL_MESSAGEID_MATCH", "LOGICAL_TS32005_DECLARATION");
            assertFalse(result.getAsJsonArray("items").asList().stream().map(v -> v.getAsJsonObject())
                .anyMatch(v -> "FAIL".equals(v.get("level").getAsString()) && dependent.contains(v.get("code").getAsString())));
            assertTrue(result.getAsJsonArray("items").asList().stream().map(v -> v.getAsJsonObject())
                .anyMatch(v -> "VALIDATION_INCOMPLETE".equals(v.get("code").getAsString())));
        } finally {
            if (old == null) System.clearProperty("VALIDATOR_MAX_METADATA_BYTES");
            else System.setProperty("VALIDATOR_MAX_METADATA_BYTES", old);
        }
    }
    @Test void upstreamFailureReportsItsUnderlyingCause() {
        var failure = new RuntimeException("upstream", new java.io.IOException("File too large"));
        assertTrue(JavaValidation.failureReason(failure).contains("File too large"));
    }
    @Test void monitoringToleratesConcurrentCreationAndRemoval() throws Exception {
        var finished = new java.util.concurrent.atomic.AtomicBoolean();
        var writer = new Thread(() -> {
            try {
                for (int i=0;i<2000;i++) {
                    Path child = Files.createTempDirectory(directory, "stage-");
                    Files.write(child.resolve("data"), new byte[32]);
                    Files.delete(child.resolve("data")); Files.delete(child);
                }
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { finished.set(true); }
        });
        writer.start();
        try { while (!finished.get()) assertFalse(ValidationRuntime.scratchExceeded(directory, directory.resolve("report"))); }
        finally { writer.join(); }
    }
    @Test void scratchBudgetIncludesFastCompletedFiles() throws Exception {
        String old = System.getProperty("VALIDATOR_MAX_SCRATCH_BYTES");
        try {
            System.setProperty("VALIDATOR_MAX_SCRATCH_BYTES", "128");
            Files.write(directory.resolve("one"), new byte[80]);
            Files.write(directory.resolve("two"), new byte[80]);
            assertTrue(ValidationRuntime.scratchExceeded(directory, directory.resolve("report")));
        } finally {
            if(old==null) System.clearProperty("VALIDATOR_MAX_SCRATCH_BYTES");
            else System.setProperty("VALIDATOR_MAX_SCRATCH_BYTES",old);
        }
    }
}

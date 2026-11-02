package eapdf;

import org.apache.pdfbox.pdmodel.PDDocument;

/** Finite traversal allowance proportional to the actual saved PDF, not its mail count. */
final class InspectionBudget {
    static long instructions(PDDocument document) {
        return Math.min(2_000_000_000L, Math.max(10_000_000L,
            Math.max((long)nodes(document) * 32, (long)document.getNumberOfPages() * 20000)));
    }
    static int nodes(PDDocument document) {
        long objects = document.getDocument().getXrefTable().size();
        return (int)Math.min(50_000_000L, Math.max(100_000L,
            Math.max(objects * 4, (long)document.getNumberOfPages() * 128)));
    }
}

/*
 * SPDX-License-Identifier: Apache-2.0
 * From PDFViewFX 3.6.1 by DLSC Software & Consulting GmbH.
 * The EA-PDF Desktop Viewer builds the orientation cache in one page-tree traversal
 * and can search a separate, memory-bounded PDFBox document.
 */
package com.dlsc.pdfviewfx;

import com.dlsc.pdfviewfx.PDFView.Document;
import com.dlsc.pdfviewfx.PDFView.SearchableDocument;
import com.dlsc.pdfviewfx.PDFView.SelectableDocument;

import com.dlsc.pdfviewfx.impl.SelectionExtractor;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.printing.PDFPageable;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Pageable;
import java.awt.print.Printable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * An implementation of {@link Document} for the Apache PDFBox library.
 *
 * @see PDFView#setDocument(Document)
 */
public class PDFBoxDocument implements SearchableDocument, SelectableDocument {

    private final PDDocument document;

    private byte[] contentBytes;
    private File contentFile;
    private int numberOfPages;
    private BitSet landscapeCache;
    private SelectionExtractor textPositionExtractor = null;

    public PDFBoxDocument(InputStream pdfInputStream) {
        try {
            contentBytes = pdfInputStream.readAllBytes();
            document = createDocument();
            initCaches();
        } catch (IOException e) {
            throw new DocumentProcessingException(e);
        }
    }

    public PDFBoxDocument(File file) {
        contentFile = file;
        document = createDocument();
        initCaches();
    }

    private void initCaches() {
        numberOfPages = document.getNumberOfPages();
        landscapeCache = new BitSet(numberOfPages);
        // Indexed lookups repeatedly traverse the same page-tree branches.
        int index = 0;
        for (PDPage page : document.getPages()) {
            PDRectangle cropBox = page.getCropBox();
            landscapeCache.set(index++, cropBox.getHeight() < cropBox.getWidth());
        }
    }

    private PDDocument createDocument() {
        try {
            return contentFile == null ? Loader.loadPDF(contentBytes) : Loader.loadPDF(new RandomAccessReadBufferedFile(contentFile));
        } catch (IOException e) {
            throw new DocumentProcessingException(e);
        }
    }

    @Override
    public int getNumberOfPages() {
        return numberOfPages;
    }

    @Override
    public boolean isLandscape(int pageNumber) {
        return landscapeCache.get(pageNumber);
    }

    @Override
    @Deprecated
    public Pageable getPageable() {
        return new PDFPageable(createDocument());
    }

    @Override
    public ClosablePageable createPageable() {
        return new PrintPageable(createDocument());
    }

    /**
     * A pageable that owns the document that was loaded for a single print job.
     */
    static class PrintPageable implements ClosablePageable {

        private final PDDocument printDocument;
        private final PDFPageable pageable;

        private boolean closed;

        PrintPageable(PDDocument printDocument) {
            this.printDocument = printDocument;
            this.pageable = new PDFPageable(printDocument);
        }

        PDDocument getPrintDocument() {
            return printDocument;
        }

        @Override
        public int getNumberOfPages() {
            return pageable.getNumberOfPages();
        }

        @Override
        public PageFormat getPageFormat(int pageIndex) {
            return pageable.getPageFormat(pageIndex);
        }

        @Override
        public Printable getPrintable(int pageIndex) {
            return pageable.getPrintable(pageIndex);
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }

                closed = true;
            }

            try {
                printDocument.close();
            } catch (IOException e) {
                throw new DocumentProcessingException(e);
            }
        }
    }

    @Override
    public BufferedImage renderPage(int pageNumber, float scale) {
        PDFRenderer renderer = new PDFRenderer(document);
        BufferedImage bufferedImage;

        try {
            bufferedImage = renderer.renderImage(pageNumber, scale, ImageType.ARGB, RenderDestination.VIEW);
        } catch (IOException e) {
            throw new DocumentProcessingException(e);
        }

        return bufferedImage;
    }

    @Override
    public List<PDFView.SearchResult> getSearchResults(String searchText) {
        try {
            return searchOriginal(searchText, () -> false);
        } catch (IOException e) {
            throw new DocumentProcessingException(e);
        }
    }

    protected List<PDFView.SearchResult> searchOriginal(String searchText, BooleanSupplier canceled) throws IOException {
        try (PDDocument doc = createDocument()) {
            return searchText(doc, searchText, canceled);
        }
    }

    protected static List<PDFView.SearchResult> searchText(PDDocument doc, String searchText, BooleanSupplier canceled)
            throws IOException {
        List<PDFView.SearchResult> results = new ArrayList<>();

        PDFTextStripper stripper;

        stripper = new PDFTextStripper() {

            private int pageNumber = -1;

            @Override
            protected void startPage(PDPage page) throws IOException {
                if (canceled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Search canceled.");
                }
                pageNumber++;
            }

            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                if (StringUtils.containsIgnoreCase(text, searchText)) {
                    PDFView.SearchResult
                            result = new PDFView.SearchResult(searchText, text, pageNumber, calculateMarkerPosition(searchText, text, textPositions));
                    results.add(result);
                }
            }
        };

        stripper.writeText(doc, Writer.nullWriter());
        return results;
    }

    @Override
    public void close() {
        try {
            document.close();
        } catch (IOException e) {
            throw new DocumentProcessingException(e);
        }
    }

    private static Rectangle2D calculateMarkerPosition(String searchText, String snippetText, List<TextPosition> textPositions) {
        int textPositionStartIndex = calculateTextPositionStartIndex(searchText, snippetText, textPositions);

        float x1 = Float.MAX_VALUE;
        float x2 = 0;
        float y1 = Float.MAX_VALUE;
        float y2 = 0;

        for (int textPositionIndex = textPositionStartIndex; textPositionIndex < textPositionStartIndex + searchText.length(); textPositionIndex++) {
            TextPosition position = textPositions.get(textPositionIndex);

            x1 = Math.min(x1, position.getXDirAdj());
            x2 = Math.max(x2, position.getXDirAdj() + position.getWidth());
            y1 = Math.min(y1, position.getYDirAdj() - position.getHeight());
            y2 = Math.max(y2, position.getYDirAdj());
        }

        x1 -= 2;
        x2 += 2;
        y1 -= 2;
        y2 += 2;

        return new Rectangle2D(x1, y1, x2 - x1, y2 - y1);
    }

    /**
     * Note that the number of textPositions might not be equal to the length of the snippetText.
     * So we need to account for that.
     * <p>
     * See: org.apache.pdfbox.text.PDFTextStripper.WordWithTextPositions
     */
    private static int calculateTextPositionStartIndex(String searchText, String snippetText, List<TextPosition> textPositions) {

        int snippetTextStartIndex = snippetText.toLowerCase().indexOf(searchText.toLowerCase());

        int startIndexDecreaseDelta = 0;

        // If any TextPosition (up to the snippetTextStartIndex) contains more then one character, we have to account for that.
        for (int i = 0; i < snippetTextStartIndex; i++) {
            int numberOfCharactersInTextPosition = textPositions.get(i).getUnicode().length();
            if (numberOfCharactersInTextPosition > 1) {
                startIndexDecreaseDelta = startIndexDecreaseDelta + (numberOfCharactersInTextPosition - 1);
            }
        }

        return snippetTextStartIndex - startIndexDecreaseDelta;
    }

    // This method is synchronized to avoid multiple selection service calls at the same time
    @Override
    public synchronized Selection getSelection(int pageNumber, Point2D start, Point2D end, Selection.Mode mode) {
        if (textPositionExtractor == null || textPositionExtractor.getPageNumber() != pageNumber) {
            textPositionExtractor = new SelectionExtractor(pageNumber);
            try (PDDocument doc = createDocument()) { // TODO :: recreating document is expensive
                textPositionExtractor.writeText(doc, Writer.nullWriter());
            } catch (IOException e) { }
        }
        return textPositionExtractor.getSelection(pageNumber, start, end, mode);
    }
}

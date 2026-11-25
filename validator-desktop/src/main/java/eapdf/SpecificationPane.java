package eapdf;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import javax.swing.Icon;
import javax.swing.JTextPane;
import javax.swing.SwingWorker;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Selectable rule summaries followed by page images from the bundled specification. */
final class SpecificationPane extends JTextPane {
  private static final String SOURCE_PDF = "/validator/specification/EA-PDF-v1.pdf";
  private static final String LOADING = "Rendering specification pages…";
  private static final int MAX_PAGE_WIDTH = 960;

  private final List<Integer> pageImageOffsets = new ArrayList<>();
  private SwingWorker<BufferedImage[], Void> rendering;
  private String ruleCode;
  private int displayedWidth;

  SpecificationPane() {
    setEditable(false);
    setMargin(new Insets(12, 12, 12, 12));
    addComponentListener(new ComponentAdapter() {
      @Override public void componentResized(ComponentEvent event) {
        int width = pageWidth();
        if (width == displayedWidth) return;
        displayedWidth = width;
        // Notify the text layout that the existing page icons have changed size.
        for (int offset : pageImageOffsets)
          getStyledDocument().setCharacterAttributes(offset, 1, SimpleAttributeSet.EMPTY, false);
      }
    });
  }

  @Override public boolean getScrollableTracksViewportWidth() {
    return true;
  }

  void showRule(String code) {
    if (Objects.equals(ruleCode, code)) return;
    ruleCode = code;
    if (rendering != null) rendering.cancel(true);
    rendering = null;
    pageImageOffsets.clear();
    if (code == null) {
      setText("");
      return;
    }
    var reference = SpecificationReferences.reference(code);
    setSummary(reference.summary());
    if (reference.pages().isEmpty()) {
      append(reference.source(), SimpleAttributeSet.EMPTY);
    } else {
      int loadingOffset = getStyledDocument().getLength();
      append(LOADING, SimpleAttributeSet.EMPTY);
      rendering = new SwingWorker<>() {
        @Override protected BufferedImage[] doInBackground() throws IOException {
          List<SpecificationReferences.Page> pages = reference.pages();
          BufferedImage[] images = new BufferedImage[pages.size()];
          try (var stream = SpecificationPane.class.getResourceAsStream(SOURCE_PDF)) {
            if (stream == null) throw new IOException("Bundled specification PDF is missing");
            try (var document = Loader.loadPDF(stream.readAllBytes())) {
              PDFRenderer renderer = new PDFRenderer(document);
              for (int i = 0; i < pages.size() && !isCancelled(); i++) {
                var page = pages.get(i);
                try {
                  images[i] = renderer.renderImageWithDPI(page.physical() - 1, 144);
                } catch (IOException error) {
                  System.err.println("Unable to render specification PDF page " + page.physical() + ": " + error.getMessage());
                }
              }
            }
          }
          return images;
        }

        @Override protected void done() {
          if (rendering != this || isCancelled()) return;
          rendering = null;
          try {
            getStyledDocument().remove(loadingOffset, LOADING.length());
            appendPages(reference, get());
          } catch (ExecutionException error) {
            System.err.println("Unable to render specification pages: " + error.getCause());
            append(reference.text().substring(reference.summary().length()), SimpleAttributeSet.EMPTY);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          } catch (BadLocationException error) {
            throw new IllegalStateException(error);
          }
        }
      };
      rendering.execute();
    }
    setCaretPosition(0);
  }

  private void setSummary(String summary) {
    setText(summary);
    var document = getStyledDocument();
    // JTextPane can reuse the style at the previous caret position when replacing text.
    document.setCharacterAttributes(0, document.getLength(), SimpleAttributeSet.EMPTY, true);
    var bold = new SimpleAttributeSet();
    StyleConstants.setBold(bold, true);
    int offset = 0;
    for (String line : summary.split("\n", -1)) {
      if (offset == 0 || line.startsWith("Rule ") || line.startsWith("Section ")
          || line.equals(SpecificationReferences.SUMMARY_HEADING)
          || line.equals(SpecificationReferences.CHECK_HEADING)
          || line.equals(SpecificationReferences.CONTEXT_HEADING))
        document.setCharacterAttributes(offset, line.length(), bold, false);
      offset += line.length() + 1;
    }
  }

  private void appendPages(SpecificationReferences.Reference reference, BufferedImage[] images) {
    displayedWidth = pageWidth();
    for (int i = 0; i < images.length; i++) {
      var page = reference.pages().get(i);
      append(page.heading() + "\n\n", SimpleAttributeSet.EMPTY);
      BufferedImage image = images[i];
      if (image == null) {
        append("Page preview unavailable. Extracted text follows.\n\n" + page.text(), SimpleAttributeSet.EMPTY);
      } else {
        pageImageOffsets.add(getStyledDocument().getLength());
        var attributes = new SimpleAttributeSet();
        attributes.addAttribute(AbstractDocument.ElementNameAttribute, StyleConstants.IconElementName);
        StyleConstants.setIcon(attributes, new PageIcon(image));
        append(" ", attributes);
      }
      append("\n\n", SimpleAttributeSet.EMPTY);
    }
    append(reference.source(), SimpleAttributeSet.EMPTY);
  }

  private int pageWidth() {
    Insets insets = getInsets();
    return Math.clamp(getWidth() - insets.left - insets.right - 4, 1, MAX_PAGE_WIDTH);
  }

  private void append(String text, AttributeSet attributes) {
    var document = getStyledDocument();
    try {
      document.insertString(document.getLength(), text, attributes);
    } catch (BadLocationException error) {
      throw new IllegalStateException(error);
    }
  }

  private final class PageIcon implements Icon {
    private final BufferedImage image;

    PageIcon(BufferedImage image) { this.image = image; }

    @Override public int getIconWidth() { return displayedWidth; }
    @Override public int getIconHeight() { return Math.max(1, image.getHeight() * displayedWidth / image.getWidth()); }

    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
      var g = (Graphics2D) graphics.create();
      try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, x, y, getIconWidth(), getIconHeight(), component);
      } finally {
        g.dispose();
      }
    }
  }
}

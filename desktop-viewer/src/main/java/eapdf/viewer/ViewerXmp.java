package eapdf.viewer;

import com.google.gson.GsonBuilder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

final class ViewerXmp {
    private static final int MAX_TOTAL = 64 * 1024 * 1024;
    private static final int MAX_PACKET = 32 * 1024 * 1024;
    private static final int MAX_PREVIEW = 256 * 1024;

    record Packet(String name, String document, String locator, byte[] bytes) {
        @Override public String toString() {
            return name + " · " + document + " · " + locator;
        }
    }

    record Result(List<Packet> packets, List<String> issues) {}

    private final List<Packet> packets = new ArrayList<>();
    private final List<String> issues = new ArrayList<>();
    private int used;
    private int documents;

    private ViewerXmp() {}

    static Result collect(ViewerSession session) throws IOException {
        var scan = new ViewerXmp();
        try (var pdf = PdfNavigation.open(session.renderingPath)) {
            scan.visit(pdf, session.breadcrumb(), 0);
        }
        return new Result(List.copyOf(scan.packets), List.copyOf(scan.issues));
    }

    private void visit(PdfNavigation pdf, String name, int depth) throws IOException {
        if (++documents > 128 || depth > ViewerApplication.MAX_CHILD_DEPTH) {
            issues.add(name + " · Document traversal limit reached");
            return;
        }
        var seen = Collections.newSetFromMap(new IdentityHashMap<COSStream, Boolean>());
        var doc = pdf.document();
        var metadata = doc.getDocumentCatalog().getMetadata();
        if (metadata != null && seen.add(metadata.getCOSObject())) {
            read(name, "Catalog", metadata.getCOSObject());
        }
        for (var object : doc.getDocument().getObjectsByType(COSName.METADATA)) {
            if (object.getObject() instanceof COSStream stream && seen.add(stream)) {
                read(name, "Object " + object.getKey(), stream);
            }
        }
        var mail = MailNavigation.read(doc);
        if (!mail.collection()) return;
        for (var asset : pdf.assets(mail)) {
            if (!asset.isCollectionPdf()) continue;
            for (var stream : asset.variants()) {
                if (!stream.isPdf()) continue;
                String child = name + " / " + asset.name();
                if (documents >= 128 || used >= MAX_TOTAL) {
                    issues.add(name + " · Collection metadata budget exhausted");
                    return;
                }
                Path copy = null;
                try {
                    copy = pdf.extract(stream);
                    try (var opened = PdfNavigation.open(copy)) {
                        visit(opened, child, depth + 1);
                    }
                } catch (IOException | RuntimeException error) {
                    issues.add(child + " · " + error.getMessage());
                } finally {
                    if (copy != null) ViewerSession.deleteTemporaryFile(copy);
                }
            }
        }
    }

    private void read(String document, String locator, COSStream stream) {
        int allowed = Math.min(MAX_PACKET, MAX_TOTAL - used);
        try (var input = stream.createInputStream()) {
            if (allowed <= 0) throw new IOException("Metadata budget exhausted");
            byte[] bytes = input.readNBytes(allowed + 1);
            if (bytes.length > allowed) throw new IOException("Metadata stream exceeds the export limit");
            used += bytes.length;
            packets.add(new Packet(String.format(Locale.ROOT, "metadata-%04d.xmp", packets.size() + 1),
                    document, locator, bytes));
        } catch (IOException | RuntimeException unavailable) {
            issues.add(document + " · " + locator + " · " + unavailable.getMessage());
        }
    }

    static String preview(Packet packet) {
        byte[] bytes = packet.bytes();
        var formatted = bytes.length <= MAX_PREVIEW ? formatXml(bytes) : null;
        var text = formatted == null
                ? new String(bytes, 0, Math.min(bytes.length, MAX_PREVIEW), StandardCharsets.UTF_8)
                : formatted;
        if (bytes.length <= MAX_PREVIEW && text.length() <= MAX_PREVIEW) return text;
        return text.substring(0, Math.min(text.length(), MAX_PREVIEW))
                + "\n\nPreview limited. The complete stream is available in the ZIP export.";
    }

    private static String formatXml(byte[] bytes) {
        try {
            var parsers = SAXParserFactory.newDefaultNSInstance();
            parsers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            parsers.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var reader = parsers.newSAXParser().getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty("jdk.xml.maxElementDepth", 128);
            reader.setErrorHandler(new DefaultHandler());

            var transformer = TransformerFactory.newDefaultInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            var output = new StringWriter();
            transformer.transform(new SAXSource(reader, new InputSource(new ByteArrayInputStream(bytes))),
                    new StreamResult(output));
            return output.toString();
        } catch (ParserConfigurationException | SAXException | TransformerException unreadable) {
            return null;
        }
    }

    static void save(Path destination, Result result, List<Packet> selected) throws IOException {
        if (selected.isEmpty()) throw new IOException("Select at least one metadata stream.");
        var path = destination.toAbsolutePath().normalize();
        Path stage = Files.createTempFile(path.getParent(), ".viewer-xmp-", ".zip");
        try {
            try (var archive = new ZipOutputStream(Files.newOutputStream(stage))) {
                var streams = selected.stream().map(packet -> Map.of("file", packet.name(), "document",
                        packet.document(), "locator", packet.locator(), "size", packet.bytes().length)).toList();
                var manifest = Map.of("format", "EA-PDF metadata export", "complete",
                        result.issues().isEmpty() && selected.size() == result.packets().size(),
                        "issues", result.issues(), "streams", streams);
                byte[] json = new GsonBuilder().setPrettyPrinting().create().toJson(manifest)
                        .getBytes(StandardCharsets.UTF_8);
                add(archive, "manifest.json", json);
                for (var packet : selected) add(archive, packet.name(), packet.bytes());
            }
            try {
                Files.move(stage, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(stage, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private static void add(ZipOutputStream archive, String name, byte[] bytes) throws IOException {
        archive.putNextEntry(new ZipEntry(name));
        archive.write(bytes);
        archive.closeEntry();
    }
}

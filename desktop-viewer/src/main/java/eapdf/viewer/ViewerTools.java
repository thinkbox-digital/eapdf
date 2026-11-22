package eapdf.viewer;

import eapdf.viewer.MailNavigation.Message;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;

final class ViewerTools {
    static final List<String> CSV_FIELDS = List.of("PDF", "Folder paths", "From", "To", "Cc", "Bcc", "Subject",
            "Sent", "Attachment count", "Attachment names");
    private static final Set<COSName> PAGE_KEYS = Set.of(COSName.TYPE, COSName.PARENT, COSName.CONTENTS,
            COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.BLEED_BOX, COSName.TRIM_BOX,
            COSName.ART_BOX, COSName.ROTATE, COSName.GROUP, COSName.getPDFName("UserUnit"));
    private static final Pattern BRACKETED_ID = Pattern.compile("<([^<>\\r\\n]+)>");
    private static final Pattern BARE_ID = Pattern.compile("[^\\s<>(),;]+");
    private static final Pattern REPLY_PREFIX = Pattern.compile("(?i)^(?:(?:re|fw|fwd)\\s*:\\s*)+");
    private static final Pattern FORMULA = Pattern.compile("(?U)^[\\s\\uFEFF]*[=+@-]|^[\\t\\r\\n]");
    private static final Pattern NEWLINES = Pattern.compile("\\r\\n|[\\r\\n\\t\\u000B\\f\\u0085\\u2028\\u2029]");
    private static final int MAX_CSV_BYTES = 64 * 1024 * 1024;

    private ViewerTools() {}

    static List<List<Message>> conversations(List<Message> messages) {
        return conversations(messages, false);
    }

    /** Subject-based grouping is optional and never creates reply parents. */
    static List<List<Message>> conversations(List<Message> messages, boolean inferSubjects) {
        int[] groups = new int[messages.size()];
        Map<String, Integer> identifiers = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            groups[i] = i;
            var ids = identifiers(value(messages.get(i), "Message-ID"));
            if (!ids.isEmpty()) identifiers.merge(ids.getFirst(), i, (previous, duplicate) -> -1);
        }
        Map<String, Integer> missingParents = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            var ancestors = identifiers(value(message, "References"));
            if (ancestors.isEmpty()) {
                ancestors = identifiers(value(message, "In-Reply-To"));
                if (!ancestors.isEmpty()) ancestors = List.of(ancestors.getFirst());
            }
            for (String id : ancestors) {
                int parent = identifiers.getOrDefault(id, -2);
                if (parent == -2) {
                    Integer seen = missingParents.putIfAbsent(id, i);
                    parent = seen == null ? i : seen;
                }
                if (parent >= 0) join(groups, i, parent);
            }
        }
        if (inferSubjects) {
            Map<String, Integer> subjects = new HashMap<>();
            for (int i = 0; i < messages.size(); i++) {
                var subject = REPLY_PREFIX.matcher(value(messages.get(i), "Subject")).replaceFirst("").strip().toLowerCase(Locale.ROOT);
                if (!subject.isEmpty()) {
                    Integer previous = subjects.putIfAbsent(subject, i);
                    if (previous != null) join(groups, i, previous);
                }
            }
        }
        Map<Integer, List<Message>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            grouped.computeIfAbsent(group(groups, i), ignored -> new ArrayList<>()).add(messages.get(i));
        }
        Comparator<Message> chronology = Comparator.comparing(message -> date(message.sent()), Comparator.nullsLast(Comparator.naturalOrder()));
        List<List<Message>> output = new ArrayList<>();
        for (var thread : grouped.values()) {
            thread.sort(chronology);
            output.add(List.copyOf(thread));
        }
        output.sort((left, right) -> chronology.compare(left.getFirst(), right.getFirst()));
        return List.copyOf(output);
    }

    private static List<String> identifiers(String raw) {
        List<String> values = new ArrayList<>();
        var matches = BRACKETED_ID.matcher(raw);
        while (matches.find()) addIdentifier(values, matches.group(1).strip());
        if (values.isEmpty()) addIdentifier(values, raw.strip());
        return values;
    }

    private static void addIdentifier(List<String> ids, String value) {
        if (!value.isEmpty() && BARE_ID.matcher(value).matches()) ids.add(value);
    }

    private static int group(int[] groups, int member) {
        while (member != groups[member]) {
            groups[member] = groups[groups[member]];
            member = groups[member];
        }
        return member;
    }

    private static void join(int[] groups, int left, int right) {
        int a = group(groups, left), b = group(groups, right);
        if (a != b) groups[Math.max(a, b)] = Math.min(a, b);
    }

    private static Instant date(String value) {
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException missingOffset) {
            try { return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC); }
            catch (DateTimeParseException unavailable) { return null; }
        }
    }

    static void exportCsv(ViewerSession session, Path target) throws IOException {
        exportCsv(session, target, CSV_FIELDS, null);
    }

    static void exportCsv(ViewerSession session, Path target, List<String> fields, Message selected) throws IOException {
        if (fields.isEmpty() || !CSV_FIELDS.containsAll(fields) || new HashSet<>(fields).size() != fields.size()) {
            throw new IOException("Choose one or more distinct metadata fields.");
        }
        if (selected != null && !belongs(session, selected)) throw new IOException("Select a message in the open PDF.");
        withOutput(session, target, staging -> {
            try (var writer = Files.newBufferedWriter(staging, StandardCharsets.UTF_8)) {
                writer.write('\uFEFF');
                var csv = new Csv(writer, fields);
                csv.line(fields);
                csv.write(session, inheritedFolders(session), selected);
                if (csv.rows == 0) throw new IOException("No email metadata was found.");
            }
        });
    }

    private static final class Csv {
        private final BufferedWriter writer;
        private final List<String> fields;
        private int rows;
        private int bytes;

        Csv(BufferedWriter writer, List<String> fields) {
            this.writer = writer;
            this.fields = fields;
        }

        void write(ViewerSession session, List<String> folders, Message selected) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Metadata export cancelled.");
            if (session.mail.diagnostics().stream().anyMatch(warning -> warning.contains("metadata") || warning.contains("folder"))) {
                throw new IOException("Some metadata or folder memberships could not be read in " + session.name + ".");
            }
            if (!session.mail.collection()) {
                for (var message : session.mail.messages()) {
                    if (selected != null && selected != message) continue;
                    var row = new ArrayList<String>();
                    for (String field : fields) {
                        row.add(switch (field) {
                            case "PDF" -> session.breadcrumb();
                            case "Folder paths" -> String.join("; ", folders.stream().map(path -> "/" + path).toList());
                            default -> value(message, field);
                        });
                    }
                    line(row);
                    rows++;
                }
                return;
            }
            if (session.pdf.assetsIncomplete()) throw new IOException("Some collection files could not be read in " + session.name + ".");
            int members = 0;
            for (var asset : session.assets) {
                if (!asset.isCollectionPdf()) continue;
                var paths = combineFolders(folders, assetFolders(session, asset));
                for (var stream : asset.variants()) {
                    if (!stream.isPdf()) continue;
                    Path extracted = session.pdf.extract(stream);
                    try (var child = ViewerSession.open(extracted, asset.name(), session, asset)) {
                        write(child, paths, null);
                        members++;
                    }
                }
            }
            if (members == 0) throw new IOException("No readable collection PDFs were found in " + session.name + ".");
        }

        void line(List<String> values) throws IOException {
            var line = String.join(",", values.stream().map(ViewerTools::csvCell).toList()) + "\r\n";
            bytes += line.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_CSV_BYTES) throw new IOException("The metadata export exceeds 64 MiB.");
            writer.write(line);
        }
    }

    private static List<String> inheritedFolders(ViewerSession session) throws IOException {
        var ancestors = new ArrayDeque<ViewerSession>();
        for (var current = session; current.parent != null; current = current.parent) ancestors.push(current);
        List<String> paths = List.of("");
        while (!ancestors.isEmpty()) {
            var child = ancestors.pop();
            if (child.parent.mail.collection()) paths = combineFolders(paths, assetFolders(child.parent, child.parentAsset));
        }
        return paths;
    }

    private static List<String> assetFolders(ViewerSession session, PdfNavigation.Asset asset) throws IOException {
        Map<Integer, MailNavigation.Folder> folders = new HashMap<>();
        for (var folder : session.mail.folders()) folders.put(folder.id(), folder);
        Set<String> paths = new LinkedHashSet<>();
        for (String nameTreeKey : asset.nameTreeKeys()) {
            Integer id = session.mail.folderId(nameTreeKey);
            if (id == null) throw new IOException("A collection folder could not be established for " + asset.name() + ".");
            var components = new ArrayDeque<String>();
            while (id != null && id != 0) {
                var folder = folders.get(id);
                if (folder == null || components.size() >= 64) throw new IOException("A collection folder hierarchy is unreadable.");
                components.push(folder.name().replace("\\", "\\\\").replace("/", "\\/"));
                id = folder.parentId();
            }
            paths.add(String.join("/", components));
        }
        if (paths.isEmpty()) throw new IOException("A collection placement could not be established for " + asset.name() + ".");
        return List.copyOf(paths);
    }

    private static List<String> combineFolders(List<String> outer, List<String> inner) throws IOException {
        Set<String> paths = new LinkedHashSet<>();
        for (var parent : outer) for (var child : inner) {
            var path = parent.isEmpty() ? child : child.isEmpty() ? parent : parent + "/" + child;
            paths.add(path);
            if (paths.size() > 256 || path.length() > 16_384) throw new IOException("Collection folder paths exceed export limits.");
        }
        return List.copyOf(paths);
    }

    private static String csvCell(String value) {
        if (FORMULA.matcher(value).find()) value = "'" + value;
        return "\"" + NEWLINES.matcher(value).replaceAll(" ").replace("\"", "\"\"") + "\"";
    }

    static void exportXmp(ViewerSession session, Path target) throws IOException {
        exportXmp(session, target, null);
    }

    static void exportXmp(ViewerSession session, Path target, Message selected) throws IOException {
        if (selected != null && !belongs(session, selected)) throw new IOException("Select a message in the open PDF.");
        var metadata = session.pdf.document().getDocumentCatalog().getMetadata();
        if (metadata == null) throw new IOException("This PDF has no catalog XMP metadata.");
        withOutput(session, target, staging -> {
            try (var input = metadata.createInputStream()) {
                Files.copy(input, staging, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    static void savePdfCopy(ViewerSession session, Path target) throws IOException {
        withOutput(session, target, staging -> Files.copy(session.path, staging, StandardCopyOption.REPLACE_EXISTING));
    }

    static void saveMessage(ViewerSession session, Message message, Path target) throws IOException {
        if (!belongs(session, message) || session.mail.collection() || message.firstPage() < 0
                || session.mail.diagnostics().stream().anyMatch(warning -> warning.contains("document part")
                        || warning.contains("document-part"))) {
            throw new IOException("The selected message has no verified body pages.");
        }
        var source = session.pdf.document();
        List<Integer> bodyPages = new ArrayList<>();
        for (int page = message.firstPage(); page <= message.lastPage(); page++) {
            if (session.mail.messageAt(page) == message) bodyPages.add(page);
        }
        if (bodyPages.isEmpty()) throw new IOException("The selected message has no verified body pages.");
        boolean standalone = Set.of("s", "si").contains(session.mail.profile()) && session.mail.messages().size() == 1
                && bodyPages.size() == source.getNumberOfPages() && session.mail.diagnostics().isEmpty()
                && !session.pdf.assetsIncomplete();
        if (standalone) {
            savePdfCopy(session, target);
            return;
        }
        withOutput(session, target, staging -> {
            try (var output = new PDDocument()) {
                output.setVersion(source.getVersion());
                for (int page : bodyPages) {
                    var original = source.getPage(page);
                    var imported = output.importPage(original);
                    imported.setResources(original.getResources());
                    var dictionary = imported.getCOSObject();
                    for (var key : List.copyOf(dictionary.keySet())) {
                        if (!PAGE_KEYS.contains(key)) dictionary.removeItem(key);
                    }
                }
                output.save(staging.toFile());
            }
        });
    }

    private static boolean belongs(ViewerSession session, Message message) {
        return message != null && session.mail.messages().stream().anyMatch(candidate -> candidate == message);
    }

    private static String value(Message message, String field) {
        var fields = message.fields();
        return switch (field) {
            case "From", "To", "Cc", "Bcc" -> {
                String raw = fields.getOrDefault("Raw-" + field, "");
                yield raw.isBlank() ? fields.getOrDefault(field, "") : raw;
            }
            case "Attachment count" -> fields.getOrDefault("NumberAttachments", "");
            default -> fields.getOrDefault(field, "");
        };
    }

    @FunctionalInterface
    private interface StagedWrite { void write(Path staging) throws IOException; }

    private static void withOutput(ViewerSession session, Path destination, StagedWrite operation) throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        for (var current = session; current != null; current = current.parent) {
            if (Files.exists(target) && Files.isSameFile(target, current.path)) {
                throw new IOException("Choose a different path to preserve the open PDF.");
            }
        }
        Path staging = Files.createTempFile(target.getParent(), ".eapdf-viewer-", ".part");
        try {
            operation.write(staging);
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
    }
}

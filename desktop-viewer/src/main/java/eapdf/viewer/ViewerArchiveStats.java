package eapdf.viewer;

import java.text.NumberFormat;
import java.util.Set;
import java.util.regex.Pattern;

final class ViewerArchiveStats {
    private static final Set<String> ARCHIVE_PROFILES = Set.of("c", "ci", "m", "mi");
    private static final Pattern MESSAGES = Pattern.compile("; messages=([0-9]+)(?=;|$)");
    private static final Pattern FOLDERS = Pattern.compile("; folders=([0-9]+)(?=;|$)");
    private static final Pattern ATTACHMENTS = Pattern.compile("; attachments=([0-9]+)(?=;|$)");

    private ViewerArchiveStats() {}

    static String summary(ViewerSession root) {
        if (root == null || !ARCHIVE_PROFILES.contains(root.mail.profile())) return "";

        var description = root.mail.archiveDescription();
        Long messageCount = root.mail.catalogMessageCount() == 0
                ? declaration(description, MESSAGES) : Long.valueOf(root.mail.catalogMessageCount());
        Long folderCount = declaration(description, FOLDERS);
        if (folderCount == null && root.mail.collection()) {
            // MailNavigation omits the synthetic Collection root.
            folderCount = (long) root.mail.folders().size();
        }
        Long attachmentCount = root.mail.catalogAttachmentCount();
        if (attachmentCount == null) attachmentCount = declaration(description, ATTACHMENTS);

        var format = NumberFormat.getIntegerInstance();
        return display(format, messageCount) + " messages · " + display(format, folderCount)
                + " folders · " + display(format, attachmentCount) + " attachments";
    }

    private static String display(NumberFormat format, Long count) {
        return count == null ? "Unknown" : format.format(count);
    }

    private static Long declaration(String description, Pattern field) {
        var match = field.matcher(description);
        return match.find() ? MailNavigation.parseMetadataCount(match.group(1)) : null;
    }
}

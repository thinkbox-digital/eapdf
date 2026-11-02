package eapdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/** Stored transport and native property evidence. Unsupported MSG inventories remain unavailable. */
final class OutlookSource {
  record Attachment(List<String> names, String contentType, long size, String md5, java.util.Map<String, String> nestedContent) {
    Attachment(List<String> names, String contentType, long size, String md5) { this(names, contentType, size, md5, java.util.Map.of()); }
  }
  record Evidence(byte[] headers, List<Attachment> attachments, List<String> bodyTypes, String attachmentError) {}
  private final int limit;
  private int consumed;
  private Integer codepage;
  private String attachmentError = "";
  private final java.util.Map<String, RawAddress> rawAddresses = new java.util.HashMap<>();
  record RawAddress(List<String> names, String display) {}

  private OutlookSource(int limit) { this.limit = limit; }

  static Evidence inspect(byte[] bytes) {
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
      return new OutlookSource(bytes.length).read(storage.getRoot());
    } catch (IOException | IllegalArgumentException invalid) { return null; }
  }

  record Core(java.util.Map<String, String> text, java.time.Instant sent, long size,
      java.util.Map<String, List<String>> addresses, List<Attachment> attachments, List<String> bodyTypes, String attachmentError, java.util.Map<String, RawAddress> rawAddresses, String reply) {}

  static Core nativeCore(byte[] bytes) {
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
      return new OutlookSource(bytes.length).core(storage.getRoot());
    } catch (IOException | IllegalArgumentException invalid) { return null; }
  }

  private Core core(DirectoryNode root) throws IOException {
    if (entries(root, 0) > 100000) return null;
    byte[] properties = propertyTable(stream(root, "__properties_version1.0"), 32);
    codepage = integer(properties, 0x3ffd0003, 32);
    String messageClass = text(root, "001A").toLowerCase(java.util.Locale.ROOT);
    if (!text(root, "007D").isBlank() || !(messageClass.startsWith("ipm.note") || messageClass.equals("ipm.post"))) return null;
    List<Attachment> attachments = inspectAttachments(root, properties);
    var addresses = nativeAddresses(root, properties);
    var values = new java.util.HashMap<String, String>();
    for (var field : List.of(new String[]{"0037", "Subject"}, new String[]{"1035", "messageid"})) {
      String value = text(root, field[0]);
      values.put(field[1], value);
    }
    Long ticks = null;
    var buffer = ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
    for (int index = 32; index < properties.length; index += 16) if (buffer.getInt(index) == 0x00390040) {
      if (ticks != null) return null;
      ticks = buffer.getLong(index + 8);
    }
    java.time.Instant instant = null;
    if (ticks != null && ticks > 0) try {
      var candidate = java.time.Instant.ofEpochSecond(ticks / 10000000 - 11644473600L, (ticks % 10000000) * 100);
      int year = candidate.atOffset(java.time.ZoneOffset.UTC).getYear();
      if (year > 0 && year <= 9999) instant = candidate;
    } catch (java.time.DateTimeException invalid) { /* Unsupported calendar evidence stays unavailable. */ }
    return new Core(java.util.Map.copyOf(values), instant, limit, addresses, attachments, bodyTypes(root), attachmentError, java.util.Map.copyOf(rawAddresses), text(root, "1042"));
  }

  private Evidence read(DirectoryNode root) throws IOException {
    if (entries(root, 0) > 100000) return null;
    byte[] properties = propertyTable(stream(root, "__properties_version1.0"), 32);
    codepage = integer(properties, 0x3ffd0003, 32);
    String headers = text(root, "007D");
    if (headers.isBlank()) return null;
    List<Attachment> attachments = inspectAttachments(root, properties);
    return new Evidence(headers.getBytes(StandardCharsets.UTF_8), attachments, bodyTypes(root), attachmentError);
  }

  private List<String> bodyTypes(DirectoryNode root) {
    List<String> bodyTypes = new ArrayList<>();
    for (var pair : List.of(new String[]{"1000", "text/plain"}, new String[]{"1013", "text/html"}, new String[]{"1009", "text/rtf"})) {
      for (Entry entry : root) if (entry.getName().startsWith("__substg1.0_" + pair[0])) { bodyTypes.add(pair[1]); break; }
    }
    return List.copyOf(bodyTypes);
  }

  private List<Attachment> inspectAttachments(DirectoryNode root, byte[] properties) {
    try { return attachments(root, properties); }
    catch (IOException | IllegalArgumentException error) {
      attachmentError = error.getMessage();
      return null; // Unknown is deliberately distinct from a verified empty inventory.
    }
  }

  private List<Attachment> attachments(DirectoryNode root, byte[] properties) throws IOException {
    List<DirectoryNode> directories = new ArrayList<>();
    for (Entry entry : root) if (entry.getName().startsWith("__attach_version1.0_")) {
      if (!(entry instanceof DirectoryNode directory)) throw new IOException("MSG attachment entry is not a storage: " + entry.getName());
      directories.add(directory);
    }
    if (ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN).getInt(20) != directories.size()) throw new IOException("MSG attachment count differs from stored attachment entries");
    List<Attachment> attachments = new ArrayList<>();
    String renderedBody = null;
    directories.sort(java.util.Comparator.comparing(DirectoryNode::getName));
    for (DirectoryNode directory : directories) {
      byte[] attachmentProperties = stream(directory, "__properties_version1.0");
      Integer method = integer(attachmentProperties, 0x37050003, 8);
      if (Integer.valueOf(5).equals(method)) {
        if (!directory.hasEntry("__substg1.0_3701000D") || !(directory.getEntry("__substg1.0_3701000D") instanceof DirectoryNode nested))
          throw new IOException("Embedded Outlook message storage is missing: " + directory.getName());
        var content = new java.util.TreeMap<String, String>();
        storageContent(nested, "", content, false);
        if (!nested.hasEntry("__nameid_version1.0") && root.hasEntry("__nameid_version1.0")) {
          if (!(root.getEntry("__nameid_version1.0") instanceof DirectoryNode mapping)) throw new IOException("Invalid named property mapping");
          content.put("__nameid_version1.0/", ""); storageContent(mapping, "__nameid_version1.0/", content, false);
        }
        String name = text(directory, "3707"); if (name.isEmpty()) name = text(directory, "3704");
        if (!name.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).endsWith(".msg")) name += ".msg";
        attachments.add(new Attachment(name.isEmpty() ? List.of() : List.of(name), "application/vnd.ms-outlook", -1, "", java.util.Map.copyOf(content)));
        continue;
      }
      if (!Integer.valueOf(1).equals(method)) throw new IOException("MSG attachment " + directory.getName() + " uses unsupported storage method " + method);
      String cid = text(directory, "3712");
      byte[] payload = stream(directory, "__substg1.0_37010102");
      String media = text(directory, "370E");
      if (payload == null) throw new IOException("MSG attachment payload is missing: " + directory.getName());
      String filename = text(directory, "3707");
      if (filename.isEmpty()) filename = text(directory, "3704");
      if (!cid.isEmpty() || !filename.isEmpty()) {
        if (renderedBody == null) {
          byte[] html = stream(root, "__substg1.0_10130102");
          if (html != null) renderedBody = new String(html, StandardCharsets.ISO_8859_1);
          else {
            byte[] rtf = stream(root, "__substg1.0_10090102");
            renderedBody = rtf == null ? "" : new String(OutlookRtf.decode(rtf), StandardCharsets.ISO_8859_1);
          }
        }
        boolean inline = false;
        for (String reference : List.of(cid, filename)) if (!reference.isEmpty()) {
          if (reference.codePoints().anyMatch(c -> c > 127)) continue;
          inline |= java.util.regex.Pattern.compile("cid:['\"]?" + java.util.regex.Pattern.quote(reference) + "(?=['\"\\s>\\\\}]|$)").matcher(renderedBody).find();
        }
        if (inline) continue;
      }
      attachments.add(new Attachment(filename.isEmpty() ? List.of() : List.of(filename), media, payload.length,
          java.util.HexFormat.of().formatHex(digest(payload))));
    }
    return List.copyOf(attachments);
  }

  static java.util.Map<String, String> standaloneContent(byte[] bytes) throws IOException {
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
      var reader = new OutlookSource(bytes.length);
      if (reader.entries(storage.getRoot(), 0) > 100000) throw new IOException("MSG storage exceeds inspection limit");
      var content = new java.util.TreeMap<String, String>();
      reader.storageContent(storage.getRoot(), "", content, true);
      return content;
    }
  }

  private void storageContent(DirectoryNode directory, String prefix, java.util.Map<String, String> content, boolean standalone) throws IOException {
    for (Entry entry : directory) {
      String key = prefix + entry.getName();
      if (entry instanceof DirectoryNode child) {
        content.put(key + "/", ""); storageContent(child, key + "/", content, false);
      } else {
        byte[] bytes = stream(directory, entry.getName());
        // MS-OXMSG: a standalone message has eight additional reserved header bytes.
        if (standalone && entry.getName().equals("__properties_version1.0")) {
          propertyTable(bytes, 32);
          byte[] normalized = new byte[bytes.length - 8];
          System.arraycopy(bytes, 0, normalized, 0, 24); System.arraycopy(bytes, 32, normalized, 24, bytes.length - 32);
          bytes = normalized;
        }
        try { content.put(key, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
      }
    }
  }

  private java.util.Map<String, List<String>> nativeAddresses(DirectoryNode root, byte[] properties) {
    var result = new java.util.HashMap<String, List<String>>();
    try {
      var recipients = new ArrayList<DirectoryNode>();
      for (Entry entry : root) if (entry.getName().startsWith("__recip_version1.0_")) {
        if (!(entry instanceof DirectoryNode directory)) throw new IOException("Invalid MSG recipient storage");
        recipients.add(directory);
      }
      if (ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN).getInt(16) != recipients.size())
        throw new IOException("Incomplete MSG recipient inventory");
      recipients.sort(java.util.Comparator.comparing(DirectoryNode::getName));
      var addresses = new java.util.HashMap<String, List<String>>();
      for (String field : List.of("To", "Cc", "Bcc")) addresses.put(field, new ArrayList<>());
      var names = new java.util.HashMap<String, List<String>>();
      for (String field : List.of("To", "Cc", "Bcc")) names.put(field, new ArrayList<>());
      var unavailable = new java.util.HashSet<String>();
      for (DirectoryNode directory : recipients) {
        Integer type = integer(stream(directory, "__properties_version1.0"), 0x0c150003, 8);
        String field = type == null ? null : switch (type) { case 1 -> "To"; case 2 -> "Cc"; case 3 -> "Bcc"; default -> null; };
        if (field == null) throw new IOException("Unknown MSG recipient type");
        try {
          String address = text(directory, "39FE");
          String addressType = text(directory, "3002"), nativeAddress = text(directory, "3003");
          if (address.isEmpty() && "SMTP".equalsIgnoreCase(addressType)) address = nativeAddress;
          if (address.isEmpty() && addressType.isEmpty() && nativeAddress.isEmpty()) continue; // A display-only recipient has no stored mailbox.
          if (address.isEmpty() || address.regionMatches(true, 0, "mailto:", 0, 7) || !MailboxChecks.valid(address)) unavailable.add(field);
          else { addresses.get(field).add(address); names.get(field).add(text(directory, "3001")); }
        } catch (IOException | IllegalArgumentException invalid) { unavailable.add(field); }
      }
      for (var entry : addresses.entrySet()) {
        String field = entry.getKey();
        if (!unavailable.contains(field)) result.put(field, List.copyOf(entry.getValue()));
        String display = text(root, switch (field) { case "To" -> "0E04"; case "Cc" -> "0E03"; default -> "0E02"; });
        rawAddresses.put(field, new RawAddress(List.copyOf(names.get(field)), display));
      }
    } catch (IOException | IllegalArgumentException invalid) { /* Partial rows cannot prove absent recipients. */ }
    try {
      String address = text(root, "5D02"), represented = text(root, "0065");
      if (address.isEmpty() && "SMTP".equalsIgnoreCase(text(root, "0064"))) address = represented;
      String sender = text(root, "0C1F");
      if (address.isEmpty() && (represented.isEmpty() || represented.equals(sender))) {
        address = text(root, "5D01");
        if (address.isEmpty() && "SMTP".equalsIgnoreCase(text(root, "0C1E"))) address = sender;
      }
      String name = text(root, "0042");
      if (name.isEmpty() && (represented.isEmpty() || represented.equals(sender))) name = text(root, "0C1A");
      if (!address.isEmpty() && !address.regionMatches(true, 0, "mailto:", 0, 7) && MailboxChecks.valid(address)) {
        result.put("From", List.of(address)); rawAddresses.put("From", new RawAddress(List.of(name), name));
      } else if (address.isEmpty() && represented.isEmpty() && sender.isEmpty() && name.isEmpty()) {
        result.put("From", List.of()); rawAddresses.put("From", new RawAddress(List.of(), ""));
      }
    } catch (IOException | IllegalArgumentException invalid) { /* Do not borrow a delegate's identity or guess an encoding. */ }
    return java.util.Map.copyOf(result);
  }

  private int entries(DirectoryNode directory, int depth) throws IOException {
    if (depth > 256) throw new IOException("MSG storage depth exceeds inspection limit");
    int total = 0;
    for (Entry entry : directory) {
      total++;
      if (entry instanceof DirectoryNode child) total += entries(child, depth + 1);
      if (total > 100000) throw new IOException("MSG inventory exceeds inspection limit");
    }
    return total;
  }

  private byte[] stream(DirectoryNode directory, String name) throws IOException {
    if (!directory.hasEntry(name)) return null;
    if (!(directory.getEntry(name) instanceof DocumentEntry entry)) throw new IOException("MSG property is not a stream");
    int size = entry.getSize();
    if (size < 0 || size > limit - consumed) throw new IOException("MSG stream inventory exceeds source bytes");
    consumed += size;
    try (var input = directory.createDocumentInputStream(name)) {
      byte[] bytes = input.readNBytes(size);
      if (bytes.length != size) throw new IOException("Truncated MSG stream");
      return bytes;
    }
  }

  private String text(DirectoryNode directory, String tag) throws IOException {
    for (String suffix : List.of("001F", "001E")) {
      byte[] bytes = stream(directory, "__substg1.0_" + tag + suffix);
      if (bytes == null) continue;
      Charset encoding;
      if ("001F".equals(suffix)) encoding = StandardCharsets.UTF_16LE;
      else {
        if (codepage == null) throw new IOException("MSG ANSI codepage is unavailable");
        encoding = Charset.forName(codepage == 65001 ? "UTF-8" : "windows-" + codepage);
      }
      String value = encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().replaceFirst("\\x00+$", "");
      if (value.indexOf(0) >= 0) throw new IOException("Embedded NUL in MSG string");
      return value;
    }
    return "";
  }

  private static byte[] propertyTable(byte[] bytes, int offset) throws IOException {
    if (bytes == null || bytes.length < offset) throw new IOException("Incomplete MSG property inventory");
    int padding = (bytes.length - offset) % 16;
    if (padding != 0) {
      for (int index = bytes.length - padding; index < bytes.length; index++) if (bytes[index] != 0)
        throw new IOException("Incomplete nonzero MSG property entry");
      return java.util.Arrays.copyOf(bytes, bytes.length - padding);
    }
    return bytes;
  }

  private static Integer integer(byte[] properties, int tag, int offset) throws IOException {
    properties = propertyTable(properties, offset);
    var buffer = ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
    Integer result = null;
    for (int index = offset; index < properties.length; index += 16) if (buffer.getInt(index) == tag) {
      if (result != null) throw new IOException("Repeated MSG property");
      result = buffer.getInt(index + 8);
    }
    return result;
  }

  private static byte[] digest(byte[] bytes) {
    try { return java.security.MessageDigest.getInstance("MD5").digest(bytes); }
    catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
  }
}

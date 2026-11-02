package eapdf;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import jakarta.mail.internet.MimeUtility;

/** Decode source evidence strictly: repaired or unknown octets cannot prove fidelity. */
final class SourceHeaders {
  private static final Pattern WORD = Pattern.compile("=\\?([^?]*)\\?([^?]*)\\?([^?]*)\\?=");
  private static final String COMPLETE_WORD = "=\\?[^\\s?]+\\?[bBqQ]\\?[^?\\r\\n]*\\?=";
  private static final Pattern ADJACENT_WORDS = Pattern.compile("(" + COMPLETE_WORD + ")(?=" + COMPLETE_WORD + ")");
  private static final Pattern DATE = Pattern.compile("(?:(Mon|Tue|Wed|Thu|Fri|Sat|Sun),?\\s+)?([0-9]{1,2})\\s+"
      + "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+([0-9]{4}|[0-9]{2})\\s+"
      + "([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?\\s+([+-][0-9]{4}|UT|UTC|GMT|EST|EDT|CST|CDT|MST|MDT|PST|PDT)", Pattern.CASE_INSENSITIVE);
  private static final Map<String, Integer> ZONES = Map.ofEntries(Map.entry("UT", 0), Map.entry("UTC", 0), Map.entry("GMT", 0),
      Map.entry("EST", -300), Map.entry("EDT", -240), Map.entry("CST", -360), Map.entry("CDT", -300),
      Map.entry("MST", -420), Map.entry("MDT", -360), Map.entry("PST", -480), Map.entry("PDT", -420));

  private SourceHeaders() {}

  static boolean pdfDate(String value) {
    var date = Pattern.compile("D:([0-9]{4}(?:[0-9]{2}){0,5})(?:([Z+-])(?:([0-9]{2})(?:'(?:([0-9]{2})'?)?)?)?)?").matcher(value);
    if (!date.matches()) return false;
    String digits = date.group(1);
    int[] fields = {1, 1, 0, 0, 0};
    for (int index = 0; index < fields.length && digits.length() >= 6 + index * 2; index++)
      fields[index] = Integer.parseInt(digits.substring(4 + index * 2, 6 + index * 2));
    try {
      java.time.LocalDateTime.of(Integer.parseInt(digits.substring(0, 4)), fields[0], fields[1], fields[2], fields[3], fields[4]);
      int hours = date.group(3) == null ? 0 : Integer.parseInt(date.group(3));
      int minutes = date.group(4) == null ? 0 : Integer.parseInt(date.group(4));
      return hours <= 23 && minutes <= 59 && (!"Z".equals(date.group(2)) || hours == 0 && minutes == 0);
    } catch (java.time.DateTimeException invalid) { return false; }
  }

  static java.time.Instant pdfInstant(String value) {
    var date = Pattern.compile("D:([0-9]{4}(?:[0-9]{2}){0,5})(Z|[+-][0-9]{2}'?(?:[0-9]{2}'?)?)?").matcher(value);
    if (!date.matches()) throw new IllegalArgumentException("Not a PDF date");
    String digits = date.group(1), suffix = date.group(2);
    int[] fields = {1, 1, 0, 0, 0};
    for (int index = 0; index < fields.length && digits.length() >= 6 + index * 2; index++)
      fields[index] = Integer.parseInt(digits.substring(4 + index * 2, 6 + index * 2));
    int year = Integer.parseInt(digits.substring(0, 4));
    if (year == 0) throw new IllegalArgumentException("PDF date year must be positive");
    var local = java.time.LocalDateTime.of(year, fields[0], fields[1], fields[2], fields[3], fields[4]);
    int offset = 0;
    if (suffix != null && !"Z".equals(suffix)) {
      String zone = suffix.substring(1).replace("'", "");
      int hours = Integer.parseInt(zone.substring(0, 2)), minutes = zone.length() == 4 ? Integer.parseInt(zone.substring(2)) : 0;
      if (hours >= 24 || minutes >= 60) throw new IllegalArgumentException("Invalid PDF timezone offset");
      offset = (hours * 60 + minutes) * (suffix.charAt(0) == '-' ? -1 : 1);
    }
    return local.toInstant(java.time.ZoneOffset.UTC).minusSeconds(offset * 60L);
  }

  static java.time.Instant instant(String raw) {
    var result = unboundedInstant(raw);
    int year = result.atOffset(java.time.ZoneOffset.UTC).getYear();
    if (year < 1 || year > 9999) throw new java.time.DateTimeException("UTC date lies outside the source comparison calendar");
    return result;
  }

  private static java.time.Instant unboundedInstant(String raw) {
    String text = dateText(raw);
    if (text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[^\\s]+")) return java.time.OffsetDateTime.parse(text).toInstant();
    var date = DATE.matcher(text);
    if (!date.matches()) throw new IllegalArgumentException("Original date is not an available email date");
    return instant(date);
  }

  private static String dateText(String raw) {
    String text = EaPdfChecks.normal(originalText(raw));
    for (int depth = 0; depth < 32; depth++) {
      String reduced = text.replaceAll("\\([^()]*\\)", " ");
      if (reduced.equals(text)) break;
      text = reduced;
    }
    text = EaPdfChecks.normal(text);
    return text;
  }

  static boolean dateBeyondRange(String raw) {
    try {
      int year = unboundedInstant(raw).atOffset(java.time.ZoneOffset.UTC).getYear();
      if (year < 1 || year > 9999) return true;
    } catch (IllegalArgumentException | java.time.DateTimeException invalid) { /* Try the extended local year assessment below. */ }
    String text = dateText(raw);
    if (text.matches("-?[0-9]{5,}-.*") && MetadataChecks.dateTime(text)) return true;
    var year = Pattern.compile("\\b([0-9]{5,})(?= [0-9]{2}:[0-9]{2})").matcher(text);
    if (!year.find()) return false;
    String digits = year.group(1).replaceFirst("^0+(?!$)", "");
    if (digits.length() <= 4 && Integer.parseInt(digits) < 1900) return false;
    int cycle = 2000 + Integer.parseInt(digits.substring(Math.max(0, digits.length() - 4))) % 400;
    try { instant(text.substring(0, year.start()) + cycle + text.substring(year.end())); return true; }
    catch (IllegalArgumentException | java.time.DateTimeException invalid) { return false; }
  }

  private static java.time.Instant instant(java.util.regex.Matcher date) {
    int year = Integer.parseInt(date.group(4));
    if (date.group(4).length() == 2) year += year < 50 ? 2000 : 1900;
    if (year == 0) throw new IllegalArgumentException("Email dates require a positive year");
    String month = date.group(3).toLowerCase(Locale.ROOT);
    int monthNumber = java.util.List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(month) + 1;
    var local = java.time.LocalDateTime.of(year, monthNumber, Integer.parseInt(date.group(2)), Integer.parseInt(date.group(5)),
        Integer.parseInt(date.group(6)), date.group(7) == null ? 0 : Integer.parseInt(date.group(7)));
    if (date.group(1) != null && !date.group(1).equalsIgnoreCase(local.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)))
      throw new IllegalArgumentException("Email date weekday contradicts its calendar date");
    String zone = date.group(8).toUpperCase(Locale.ROOT);
    Integer offset = ZONES.get(zone);
    if (offset == null) {
      int hours = Integer.parseInt(zone.substring(1, 3)), minutes = Integer.parseInt(zone.substring(3));
      if (hours >= 24 || minutes >= 60) throw new IllegalArgumentException("Invalid original timezone offset");
      offset = (hours * 60 + minutes) * (zone.charAt(0) == '-' ? -1 : 1);
    }
    // RFC 5322 -0000 still identifies a UTC instant; the originating local zone is unknown.
    return local.toInstant(java.time.ZoneOffset.UTC).minusSeconds(offset * 60L);
  }

  // Jakarta Mail exposes original header octets through an ISO-8859-1 string.
  static String originalText(String transportHeader) {
    if (transportHeader.codePoints().noneMatch(value -> value > 127)) return transportHeader;
    return strict(transportHeader.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
  }

  static String decoded(String text) {
    var words = WORD.matcher(text);
    while (words.find()) {
      String charset = words.group(1), encoding = words.group(2), payload = words.group(3);
      if (charset.isEmpty() || charset.chars().anyMatch(Character::isWhitespace) || payload.isEmpty()
          || payload.chars().anyMatch(value -> value < 33 || value > 126))
        throw new IllegalArgumentException("Malformed encoded-word components");
      byte[] bytes;
      if ("B".equalsIgnoreCase(encoding)) {
        if (payload.length() % 4 != 0) throw new IllegalArgumentException("Incomplete base64 padding");
        bytes = Base64.getDecoder().decode(payload);
      } else if ("Q".equalsIgnoreCase(encoding)) {
        var out = new ByteArrayOutputStream();
        for (int index = 0; index < payload.length(); index++) {
          char value = payload.charAt(index);
          if (value == '=') {
            if (index + 2 >= payload.length()) throw new IllegalArgumentException("Incomplete Q escape");
            int high = Character.digit(payload.charAt(++index), 16), low = Character.digit(payload.charAt(++index), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Malformed Q escape");
            out.write(high * 16 + low);
          } else out.write(value == '_' ? ' ' : value);
        }
        bytes = out.toByteArray();
      } else throw new IllegalArgumentException("Unknown encoded-word encoding");
      strict(bytes, Charset.forName(MimeUtility.javaCharset(charset)));
    }
    try { return MimeUtility.decodeText(ADJACENT_WORDS.matcher(text).replaceAll("$1 ")); }
    catch (java.io.UnsupportedEncodingException error) { throw new IllegalArgumentException("Unknown header charset", error); }
  }

  private static String strict(byte[] bytes, Charset charset) {
    try {
      return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    } catch (java.nio.charset.CharacterCodingException error) {
      throw new IllegalArgumentException("Original header octets cannot be decoded", error);
    }
  }
}

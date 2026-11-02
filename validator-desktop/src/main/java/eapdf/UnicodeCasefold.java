package eapdf;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Full Unicode case folding, pinned to the reference engine's Unicode data. */
final class UnicodeCasefold {
  private static final JsonObject MAPPING = load();
  private static JsonObject load() {
    try (var stream = UnicodeCasefold.class.getResourceAsStream("/validator/unicode-casefold.json")) {
      if (stream == null) throw new IllegalStateException("Bundled Unicode case-fold data is missing");
      return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("mapping");
    } catch (Exception error) { throw new ExceptionInInitializerError(error); }
  }
  static String fold(String value) {
    StringBuilder folded = new StringBuilder();
    value.codePoints().forEach(point -> {
      String key = Integer.toString(point);
      if (MAPPING.has(key)) folded.append(MAPPING.get(key).getAsString()); else folded.appendCodePoint(point);
    });
    return folded.toString();
  }
  private UnicodeCasefold() {}
}

package eapdf;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/** Development comparison runner. It is not included in the application JAR. */
public final class ValidatorComparisonRunner {
  public static void main(String[] args) throws Exception {
    var inputs = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonArray();
    Path output = Path.of(args[1]); Files.createDirectories(output);
    int start = Integer.parseInt(args[2]), stride = Integer.parseInt(args[3]);
    for (int index = start; index < inputs.size(); index += stride) {
      var input = inputs.get(index).getAsJsonObject();
      Path target = output.resolve(input.get("id").getAsString() + ".java.json.gz");
      if (Files.isRegularFile(target)) continue;
      JsonObject result;
      try {
        var children = new JavaValidation.Children();
        result = JavaValidation.validate(Path.of(input.get("absolute_path").getAsString()), children, 0);
        result.add("comparison_children", JavaValidation.JSON.toJsonTree(children.cache));
      } catch (Throwable error) {
        result = new JsonObject(); result.addProperty("comparison_error", error.toString());
      }
      Path temporary = target.resolveSibling(target.getFileName() + ".part");
      try (var stream = new GZIPOutputStream(Files.newOutputStream(temporary))) {
        stream.write(JavaValidation.JSON.toJson(result).getBytes(StandardCharsets.UTF_8));
      }
      Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      System.out.println(index + " " + input.get("path").getAsString());
    }
  }
}

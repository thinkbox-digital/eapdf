package eapdf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Assemble a private per-run configuration without Swing or CLI changes. */
final class GuiRunConfiguration {
  private GuiRunConfiguration() {}
  static Path write(EapdfConfig defaults, boolean pdfUaEnabled, boolean searchIndexEnabled,
                    EapdfConfig.SigningConfig signing) throws Exception {
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    EapdfConfig cfg = gson.fromJson(gson.toJson(defaults), EapdfConfig.class);
    cfg = EapdfConfig.normalize(cfg == null ? new EapdfConfig() : cfg);
    cfg.requireCreator();
    cfg.searchIndex.enabled = searchIndexEnabled;
    cfg.searchIndex.fullTextEnabled = searchIndexEnabled;
    cfg.accessibility.addPdfUaFeatures = pdfUaEnabled;
    cfg.signing = signing;
    Path tempConfig = Files.createTempFile("eapdf-gui-config-", ".json");
    Files.writeString(tempConfig, gson.toJson(cfg), StandardCharsets.UTF_8);
    tempConfig.toFile().deleteOnExit();
    return tempConfig;
  }
}

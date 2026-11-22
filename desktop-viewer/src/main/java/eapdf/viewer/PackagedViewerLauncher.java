package eapdf.viewer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class PackagedViewerLauncher {
    private PackagedViewerLauncher() {}

    public static void main(String[] args) throws Throwable {
        String platform = javafxPlatform();
        String resource = "/META-INF/eapdf/javafx/" + platform + ".jar";
        try (var graphics = PackagedViewerLauncher.class.getResourceAsStream(resource)) {
            if (graphics == null) {
                throw new IOException("The viewer JAR is missing JavaFX for " + platform + ".");
            }
            var extracted = Files.createTempFile("eapdf-javafx-" + platform + "-", ".jar");
            try {
                Files.copy(graphics, extracted, StandardCopyOption.REPLACE_EXISTING);
                var viewer = PackagedViewerLauncher.class.getProtectionDomain().getCodeSource().getLocation();
                // Load the viewer and JavaFX together so native lookup and service discovery use the same class loader.
                URL[] classpath = {extracted.toUri().toURL(), viewer};
                try (var loader = new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader())) {
                    var thread = Thread.currentThread();
                    var previousLoader = thread.getContextClassLoader();
                    thread.setContextClassLoader(loader);
                    try {
                        var launcher = loader.loadClass("eapdf.viewer.ViewerLauncher");
                        launcher.getMethod("main", String[].class).invoke(null, (Object) args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    } finally {
                        thread.setContextClassLoader(previousLoader);
                    }
                }
            } finally {
                Files.deleteIfExists(extracted);
            }
        }
    }

    private static String javafxPlatform() {
        String os = System.getProperty("os.name");
        String architecture = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean x64 = architecture.equals("amd64") || architecture.equals("x86_64");
        boolean arm64 = architecture.equals("aarch64") || architecture.equals("arm64");
        if (os.equals("Linux") && (x64 || arm64)) return arm64 ? "linux-aarch64" : "linux";
        if (os.startsWith("Mac") && (x64 || arm64)) return arm64 ? "mac-aarch64" : "mac";
        if (os.startsWith("Windows") && x64) return "win";
        throw new UnsupportedOperationException("No JavaFX build is bundled for " + os + " (" + architecture + ").");
    }
}

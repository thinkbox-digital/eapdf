package eapdf.viewer;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.IDisconnectCallback;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

final class XdgFileChooser {
    private static final String PORTAL = "org.freedesktop.portal.Desktop";
    private static final String DESKTOP_PATH = "/org/freedesktop/portal/desktop";

    private XdgFileChooser() {}

    static Path choose(String parentWindow, String title, String suggestedName) throws IOException {
        var unavailable = new CompletableFuture<DBusSignal>();
        var disconnect = new IDisconnectCallback() {
            @Override
            public void disconnectOnError(IOException failure) {
                unavailable.completeExceptionally(new IOException("Lost the desktop portal connection.", failure));
            }
        };
        try (var connection = DBusConnectionBuilder.forSessionBus()
                .withShared(false).withDisconnectCallback(disconnect).build()) {
            var bus = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
            if (!bus.NameHasOwner(PORTAL)) bus.StartServiceByName(PORTAL, new UInt32(0));
            String portalOwner = bus.GetNameOwner(PORTAL);
            var chooser = connection.getRemoteObject(portalOwner, DESKTOP_PATH, FileChooser.class, false);
            var pending = new ConcurrentHashMap<String, CompletableFuture<DBusSignal>>();
            var responses = DBusMatchRuleBuilder.create().withType("signal").withSender(portalOwner)
                    .withInterface("org.freedesktop.portal.Request").withMember("Response")
                    .withPathNamespace(DESKTOP_PATH + "/request").build();
            var ownerChanges = DBusMatchRuleBuilder.create().withType("signal").withSender("org.freedesktop.DBus")
                    .withInterface("org.freedesktop.DBus").withMember("NameOwnerChanged")
                    .withArg0123(0, portalOwner).build();

            // Subscribe on this connection before the method call. A response can arrive before its reply.
            try (var responseHandler = connection.addGenericSigHandler(responses, signal ->
                        pending.computeIfAbsent(signal.getPath(), path -> new CompletableFuture<>()).complete(signal));
                 var ownerHandler = connection.addGenericSigHandler(ownerChanges, signal ->
                        unavailable.completeExceptionally(new IOException("The desktop portal closed while choosing a file.")))) {
                var options = options(suggestedName);
                DBusPath request = suggestedName == null
                        ? chooser.OpenFile(parentWindow, title, options)
                        : chooser.SaveFile(parentWindow, title, options);
                var response = pending.computeIfAbsent(request.getPath(), path -> new CompletableFuture<>());
                return selectedPath((DBusSignal) CompletableFuture.anyOf(response, unavailable).get());
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("The desktop file picker was interrupted.", failure);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException message) throw message;
            throw new IOException("The desktop file picker stopped unexpectedly.", failure);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Could not use the desktop file picker. Check that the XDG Desktop Portal "
                    + "and a file chooser backend are available for this session.", failure);
        }
    }

    private static Map<String, Variant<?>> options(String suggestedName) {
        var options = new HashMap<String, Variant<?>>();
        options.put("handle_token", new Variant<>("eapdf_" + UUID.randomUUID().toString().replace("-", "")));
        options.put("modal", new Variant<>(true));
        if (suggestedName == null) {
            var patterns = List.of(new Object[] {new UInt32(0), "*.pdf"},
                    new Object[] {new UInt32(0), "*.PDF"});
            var pdfFilter = new Object[] {"PDF files", patterns};
            options.put("filters", new Variant<>(List.<Object[]>of(pdfFilter), "a(sa(us))"));
        } else {
            options.put("current_name", new Variant<>(suggestedName));
        }
        return options;
    }

    private static Path selectedPath(DBusSignal response) throws IOException, DBusException {
        var parameters = response.getParameters();
        if (parameters.length != 2 || !(parameters[0] instanceof UInt32 status)
                || !(parameters[1] instanceof Map<?, ?> results)) {
            throw new IOException("The desktop file picker returned an invalid response.");
        }
        if (status.intValue() == 1) return null;
        if (status.intValue() != 0) throw new IOException("The desktop file picker could not finish the request.");

        if (!(results.get("uris") instanceof Variant<?> uris)
                || !(uris.getValue() instanceof List<?> files) || files.size() != 1
                || !(files.getFirst() instanceof String file)) {
            throw new IOException("The desktop file picker returned an invalid file selection.");
        }
        try {
            URI uri = URI.create(file);
            if ("file".equalsIgnoreCase(uri.getScheme())) return Path.of(uri);
        } catch (IllegalArgumentException failure) {
            throw new IOException("The desktop file picker returned an invalid local file path.", failure);
        }
        throw new IOException("The desktop file picker must return a local file.");
    }

    @DBusInterfaceName("org.freedesktop.portal.FileChooser")
    public interface FileChooser extends DBusInterface {
        DBusPath OpenFile(String parentWindow, String title, Map<String, Variant<?>> options);

        DBusPath SaveFile(String parentWindow, String title, Map<String, Variant<?>> options);
    }
}

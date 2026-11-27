package org.verapdf.parser;

/** Observes cross-reference stream work on the thread that opens the parser. */
public final class XrefProgress implements AutoCloseable {
    @FunctionalInterface
    public interface Listener {
        void advance(long entries, long encodedBytes, long decodedBytes);
    }

    private static final ThreadLocal<Listener> CURRENT = new ThreadLocal<>();
    private final Listener previous;

    public XrefProgress(Listener listener) {
        previous = CURRENT.get();
        CURRENT.set(listener);
    }

    static Listener listener() { return CURRENT.get(); }

    @Override public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}

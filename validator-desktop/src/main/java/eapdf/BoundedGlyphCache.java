package eapdf;

import java.util.LinkedHashMap;
import java.util.Map;
import org.verapdf.gf.model.impl.containers.StaticContainers;
import org.verapdf.model.operator.Glyph;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import org.verapdf.pdfa.flavours.PDFFlavours;

/** Bounds reconstructible glyph models while leaving PDF/A-1's inventory cache intact. */
final class BoundedGlyphCache extends LinkedHashMap<String, Map<String, Glyph>> {
    BoundedGlyphCache() { super(128, 0.75f, true); }
    static void install() {
        // The two upstream consumers that enumerate cached glyphs are PDF/A-1
        // CIDSet/CharSet checks. Those must retain the complete inventory.
        if (PDFFlavours.isFlavourPart(StaticContainers.getFlavour(), PDFAFlavour.Specification.ISO_19005_1)) return;
        var cache = new BoundedGlyphCache();
        StaticContainers.getCachedGlyphs().forEach(cache::put);
        StaticContainers.setCachedGlyphs(cache);
    }
    @Override public Map<String, Glyph> put(String key, Map<String, Glyph> value) {
        var bounded = new LinkedHashMap<String, Glyph>(2048, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Glyph> eldest) { return size() > 2048; }
        };
        bounded.putAll(value);
        return super.put(key, bounded);
    }
    @Override protected boolean removeEldestEntry(Map.Entry<String, Map<String, Glyph>> eldest) { return size() > 128; }
}

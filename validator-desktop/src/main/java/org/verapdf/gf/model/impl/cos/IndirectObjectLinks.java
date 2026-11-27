package org.verapdf.gf.model.impl.cos;

import org.verapdf.cos.COSKey;
import org.verapdf.cos.COSObject;
import org.verapdf.gf.model.tools.GFIDGenerator;

/** The Greenfield indirect-object link in its original index order, without eager wrappers. */
public final class IndirectObjectLinks {
    private final COSKey[] keys;
    private final COSObject[] values;

    public IndirectObjectLinks(GFCosDocument document) {
        var entries = document.cosDocument.getObjectsMap();
        keys = new COSKey[entries.size()];
        values = new COSObject[keys.length];
        int index = 0;
        for (var entry : entries.entrySet()) {
            keys[index] = entry.getKey();
            values[index++] = entry.getValue();
        }
    }

    public int size() { return keys.length; }

    public String id(int index) { return GFIDGenerator.generateID(keys[index]); }

    public GFCosIndirect get(int index) { return new GFCosIndirect(keys[index], values[index]); }
}

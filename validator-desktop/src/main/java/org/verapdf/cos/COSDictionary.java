/*
 * This file is part of veraPDF Parser, a module of the veraPDF project.
 * Copyright (c) 2015-2026, veraPDF Consortium <info@verapdf.org>
 * All rights reserved.
 *
 * veraPDF Parser is free software: you can redistribute it and/or modify
 * it under the terms of either:
 *
 * The GNU General public license GPLv3+.
 * You should have received a copy of the GNU General Public License
 * along with veraPDF Parser as the LICENSE.GPL file in the root of the source
 * tree.  If not, see http://www.gnu.org/licenses/ or
 * https://www.gnu.org/licenses/gpl-3.0.en.html.
 *
 * The Mozilla Public License MPLv2+.
 * You should have received a copy of the Mozilla Public License along with
 * veraPDF Parser as the LICENSE.MPL file in the root of the source tree.
 * If a copy of the MPL was not distributed with this file, you can obtain one at
 * http://mozilla.org/MPL/2.0/.
 */
package org.verapdf.cos;

import org.verapdf.as.ASAtom;
import org.verapdf.cos.visitor.ICOSVisitor;
import org.verapdf.cos.visitor.IVisitor;

import java.util.*;

/**
 * @author Timur Kamalov
 */
public class COSDictionary extends COSDirect {

    public static final String SIZE = "size";
    public static final String DICTIONARY = "dictionary";

    private Map<ASAtom, COSObject> entries;

    protected COSDictionary() {
        super();
        this.entries = new CompactEntries(this);
    }

    protected COSDictionary(final ASAtom key, final COSObject value) {
        this();
        setKey(key, value);
    }

    protected COSDictionary(final ASAtom key, final boolean value) {
        this();
        setBooleanKey(key, value);
    }

    protected COSDictionary(final ASAtom key, final int value) {
        this();
        setIntegerKey(key, value);
    }

    protected COSDictionary(final ASAtom key, final double value) {
        this();
        setRealKey(key, value);
    }

    protected COSDictionary(final ASAtom key, final String value) {
        this();
        setStringKey(key, value);
    }

    protected COSDictionary(final ASAtom key, final ASAtom value) {
        this();
        setNameKey(key, value);
    }

    protected COSDictionary(final ASAtom key, final int size, final COSObject[] value) {
        this();
        setArrayKey(key, size, value);
    }

    protected COSDictionary(final ASAtom key, final int size, final double[] value) {
        this();
        setArrayKey(key, size, value);
    }

    protected COSDictionary(final COSDictionary dict) {
        super();
        this.entries = dict.entries;
    }

    //! Object type
    @Override
    public COSObjType getType() {
        return COSObjType.COS_DICT;
    }

    public static COSObject construct() {
        return new COSObject(new COSDictionary());
    }

    public static COSObject construct(final ASAtom key, final COSObject value) {
        return new COSObject(new COSDictionary(key, value));
    }

    public static COSObject construct(final ASAtom key, final boolean value) {
        return new COSObject(new COSDictionary(key, value));
    }

    public static COSObject construct(final ASAtom key, final int value) {
        return new COSObject(new COSDictionary(key, value));
    }

    public static COSObject construct(final ASAtom key, final double value) {
        return new COSObject(new COSDictionary(key, value));
    }

    public static COSObject construct(final ASAtom key, final String value) {
        return new COSObject(new COSDictionary(key, value));
    }

    public static COSObject construct(final ASAtom key, final ASAtom value) {
        return new COSObject(new COSDictionary(key, value));
    }

    public static COSObject construct(final ASAtom key, final int size, final COSObject[] value) {
        return new COSObject(new COSDictionary(key, size, value));
    }

    public static COSObject construct(final ASAtom key, final int size, final double[] value) {
        return new COSObject(new COSDictionary(key, size, value));
    }

    public static COSObject construct(final COSDictionary dict) {
        return new COSObject(new COSDictionary(dict));
    }

    @Override
    public void accept(IVisitor visitor) {
        visitor.visitFromDictionary(this);
    }

    @Override
    public Object accept(final ICOSVisitor visitor) {
        return visitor.visitFromDictionary(this);
    }

    @Override
    public Integer size() {
        return this.entries.size();
    }

    @Override
    public Boolean knownKey(final ASAtom key) {
        return this.entries.containsKey(key);
    }

    @Override
    public COSObject getKey(final ASAtom key) {
        COSObject value = this.entries.get(key);
        return value != null ? value : new COSObject();
    }

    @Override
    public boolean setKey(final ASAtom key, final COSObject value) {
        if (value.empty() || value.get() instanceof COSNull) {
            this.entries.remove(key);
        } else {
            this.entries.put(key, value);
        }
        return true;
    }

    @Override
    public Boolean getBooleanKey(final ASAtom key) {
        return getKey(key).getBoolean();
    }

    @Override
    public boolean setBooleanKey(final ASAtom key, final boolean value) {
        COSObject obj = new COSObject();
        obj.setBoolean(value);
        this.entries.put(key, obj);
        return true;
    }

    @Override
    public Long getIntegerKey(final ASAtom key) {
        return getKey(key).getInteger();
    }

    @Override
    public boolean setIntegerKey(final ASAtom key, final long value) {
        COSObject obj = new COSObject();
        obj.setInteger(value);
        this.entries.put(key, obj);
        return true;
    }

    @Override
    public Double getRealKey(final ASAtom key) {
        return getKey(key).getReal();
    }

    @Override
    public boolean setRealKey(final ASAtom key, final double value) {
        COSObject obj = new COSObject();
        obj.setReal(value);
        this.entries.put(key, obj);
        return true;
    }

    @Override
    public String getStringKey(final ASAtom key) {
        COSObject object = getKey(key);
        if (object.getType() == COSObjType.COS_NAME) {
            return null;
        }
        return object.getString();
    }

    @Override
    public boolean setStringKey(final ASAtom key, final String value) {
        COSObject obj = new COSObject();
        obj.setString(value);
        this.entries.put(key, obj);
        return true;
    }

    @Override
    public final ASAtom getNameKey(final ASAtom key) {
        return getKey(key).getName();
    }

    @Override
    public final String getNameKeyStringValue(final ASAtom key) {
        ASAtom value = getNameKey(key);
        return value != null ? value.getValue() : null;
    }

    @Override
    public final String getNameKeyUnicodeValue(final ASAtom key) {
        ASAtom value = getNameKey(key);
        return value != null ? ((COSName)COSName.fromValue(value)).getUnicodeValue() : null;
    }

    @Override
    public boolean setNameKey(final ASAtom key, final ASAtom value) {
        COSObject obj = new COSObject();
        obj.setName(value);
        this.entries.put(key, obj);
        return true;
    }

    @Override
    public boolean setArrayKey(final ASAtom key) {
        COSObject obj = new COSObject();
        obj.setArray();
        this.entries.put(key, obj);
        return true;
    }

    @Override
    public boolean setArrayKey(final ASAtom key, final COSObject array) {
        this.entries.put(key, array);
        return true;
    }

    @Override
    public boolean setArrayKey(final ASAtom key, final int size, final COSObject[] value) {
        COSObject obj = new COSObject();
        obj.setArray(size, value);
        this.entries.put(key, obj);
        return true;
    }

    @Override
    public boolean setArrayKey(final ASAtom key, final int size, final double[] value) {
        COSObject obj = new COSObject();
        obj.setArray(size, value);
        this.entries.put(key, obj);
        return true;
    }

    @Override
    public void removeKey(final ASAtom key) {
        this.entries.remove(key);
    }

    // Instead of iterator
    public Set<Map.Entry<ASAtom, COSObject>> getEntrySet() {
        return this.entries.entrySet();
    }

    @Override
    public Set<ASAtom> getKeySet() {
        return this.entries.keySet();
    }

    @Override
    public Collection<COSObject> getValues() {
        return this.entries.values();
    }

    private Set<ASAtom> getNonNullKeySet() {
        Set<ASAtom> nonNullKeys = new HashSet<>();
        for (ASAtom key : getKeySet()) {
            COSBase value = getKey(key).getDirectBase();
            if (value != null) {
                nonNullKeys.add(key);
            }
        }
        return nonNullKeys;
    }

    @Override
    public boolean isEquivalentTo(COSBase o) {
        if (this == o) return true;
        if (o == null) return false;

        if (o.isIndirect()) {
            return isEquivalentTo(o.getDirectBase());
        }

        if (!(o instanceof COSDictionary)) return false;

        List<COSBasePair> checkedObjects = new LinkedList<>();
        return isEquivalentTo(o, checkedObjects);
    }

    /**
     * Internal recursive comparison with cycle detection.
     */
    boolean isEquivalentTo(COSBase o, List<COSBasePair> checkedObjects) {
        if (this == o) return true;
        if (o == null) return false;
        if (!(o instanceof COSDictionary)) return false;

        COSDictionary that = (COSDictionary) o;

        if (COSBasePair.listContainsPair(checkedObjects, this, that)) {
            return true;
        }
        COSBasePair.addPairToList(checkedObjects, this, that);

        Set<ASAtom> thisKeys = this.getNonNullKeySet();
        Set<ASAtom> thatKeys = that.getNonNullKeySet();

        if (!thisKeys.equals(thatKeys)) {
            return false;
        }

        return isEquivalentKeys(that, thisKeys, checkedObjects);
    }

    protected boolean isEquivalentKeys(COSDictionary that, Set<ASAtom> thisKeys, List<COSBasePair> checkedObjects) {
        for (ASAtom key : thisKeys) {
            COSBase thisVal = this.getKey(key).getDirectBase();
            COSBase thatVal = that.getKey(key).getDirectBase();

            if (thisVal == null && thatVal == null) continue;
            if (thisVal == null || thatVal == null) return false;

            if (!thisVal.isEquivalentTo(thatVal, checkedObjects)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (obj instanceof COSObject) {
            return this.equals(((COSObject) obj).get());
        }
        List<COSBasePair> checkedObjects = new LinkedList<>();
        return this.equals(obj, checkedObjects);
    }

    @Override
    boolean equals(Object obj, List<COSBasePair> checkedObjects) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (obj instanceof COSObject) {
            return this.equals(((COSObject) obj).get());
        }
        if (COSBasePair.listContainsPair(checkedObjects, this, (COSBase) obj)) {
            return true;    // Not necessary true, but we should behave as it is
        }
        COSBasePair.addPairToList(checkedObjects, this, (COSBase) obj);
        if (getClass() != obj.getClass()) {
            return false;
        }
        COSDictionary that = (COSDictionary) obj;
        if (!that.size().equals(this.size())) {
            return false;
        }
        Set<ASAtom> set1 = this.getKeySet();
        if (!set1.equals(that.getKeySet())) {
            return false;
        }
        for (ASAtom name : set1) {
            COSBase cosBase1 = this.getKey(name).get();
            COSBase cosBase2 = that.getKey(name).get();
            if (!cosBase1.equals(cosBase2, checkedObjects)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return DICTIONARY + '(' + SIZE + " = " + size() + ')';
    }


    /** Local change: inline up to three entries, retaining canonical values and aliases. */
    private static final class CompactEntries extends AbstractMap<ASAtom, COSObject> {
        private final COSDictionary owner;
        private Map<ASAtom, COSObject> expanded;
        private ASAtom k0, k1, k2;
        private COSObject v0, v1, v2;
        private int count, version;
        CompactEntries(COSDictionary owner) { this.owner = owner; }
        private ASAtom key(int i) { return i == 0 ? k0 : i == 1 ? k1 : k2; }
        private COSObject value(int i) { return i == 0 ? v0 : i == 1 ? v1 : v2; }
        private void set(int i, ASAtom key, COSObject value) {
            if (i == 0) { k0 = key; v0 = value; }
            else if (i == 1) { k1 = key; v1 = value; }
            else { k2 = key; v2 = value; }
        }
        private int index(Object key) {
            for (int i = 0; i < count; i++) if (Objects.equals(key, key(i))) return i;
            return -1;
        }
        @Override public int size() { return expanded == null ? count : expanded.size(); }
        @Override public boolean containsKey(Object key) { return expanded == null ? index(key) >= 0 : expanded.containsKey(key); }
        @Override public COSObject get(Object key) {
            if (expanded != null) return expanded.get(key);
            int i = index(key); return i < 0 ? null : value(i);
        }
        @Override public COSObject put(ASAtom key, COSObject value) {
            if (expanded != null) return expanded.put(key, value);
            int i = index(key);
            if (i >= 0) { COSObject old = value(i); set(i, key(i), value); return old; }
            if (count < 3) { set(count++, key, value); version++; return null; }
            // The original default HashMap has capacity 16 at this point. Replaying
            // insertion order preserves bucket/collision order and subsequent growth.
            expanded = new HashMap<>();
            for (int n = 0; n < count; n++) expanded.put(key(n), value(n));
            for (int n = 0; n < count; n++) set(n, null, null);
            count = 0; version++;
            owner.entries = expanded;
            return expanded.put(key, value);
        }
        @Override public COSObject remove(Object key) {
            if (expanded != null) return expanded.remove(key);
            int i = index(key); if (i < 0) return null;
            COSObject old = value(i);
            for (int n = i; n < count - 1; n++) set(n, key(n + 1), value(n + 1));
            set(--count, null, null); version++; return old;
        }
        @Override public void clear() {
            if (expanded != null) expanded.clear();
            else { for (int n = 0; n < count; n++) set(n, null, null); count = 0; version++; }
        }
        private static int bucket(ASAtom key) {
            int h = key == null ? 0 : key.hashCode(); return (h ^ h >>> 16) & 15;
        }
        @Override public Set<Map.Entry<ASAtom, COSObject>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() { return CompactEntries.this.size(); }
                @Override public void clear() { CompactEntries.this.clear(); }
                @Override public Iterator<Map.Entry<ASAtom, COSObject>> iterator() {
                    if (expanded != null) return expanded.entrySet().iterator();
                    List<ASAtom> keys = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) keys.add(key(i));
                    keys.sort(Comparator.comparingInt(CompactEntries::bucket));
                    return new Iterator<>() {
                        int expected = version, cursor;
                        ASAtom last; boolean removable;
                        private void check() { if (expected != version) throw new ConcurrentModificationException(); }
                        @Override public boolean hasNext() { return cursor < keys.size(); }
                        @Override public Map.Entry<ASAtom, COSObject> next() {
                            check(); if (!hasNext()) throw new NoSuchElementException();
                            last = keys.get(cursor++); removable = true; return new SmallEntry(last);
                        }
                        @Override public void remove() {
                            check(); if (!removable) throw new IllegalStateException();
                            CompactEntries.this.remove(last); expected = version; removable = false;
                        }
                    };
                }
            };
        }
        private final class SmallEntry implements Map.Entry<ASAtom, COSObject> {
            private final ASAtom key;
            SmallEntry(ASAtom key) { this.key = key; }
            @Override public ASAtom getKey() { return key; }
            @Override public COSObject getValue() { return CompactEntries.this.get(key); }
            @Override public COSObject setValue(COSObject value) { return put(key, value); }
            @Override public boolean equals(Object other) {
                return other instanceof Map.Entry<?, ?> e && Objects.equals(key, e.getKey()) && Objects.equals(getValue(), e.getValue());
            }
            @Override public int hashCode() { return Objects.hashCode(key) ^ Objects.hashCode(getValue()); }
            @Override public String toString() { return key + "=" + getValue(); }
        }
    }

}

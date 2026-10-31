package dev.kristian.nullmod.client.variant;

import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * A small hash map from entity ids to values with no boxing, so the lookup made for every
 * living entity drawn each frame allocates nothing. Open addressing with linear probing and
 * backward-shift deletion. Not thread safe (render thread only).
 */
final class VariantIntMap<V> {
    private int[] keys;
    private Object[] values;
    private boolean[] used;
    private int mask;
    private int size;

    VariantIntMap() {
        allocate(64);
    }

    private void allocate(int capacity) {
        keys = new int[capacity];
        values = new Object[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
        size = 0;
    }

    private static int slot(int key, int mask) {
        int h = key * 0x9E3779B9;
        return (h ^ (h >>> 16)) & mask;
    }

    int size() {
        return size;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    V get(int key) {
        int i = slot(key, mask);
        while (used[i]) {
            if (keys[i] == key) {
                return (V) values[i];
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    V put(int key, V value) {
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        int i = slot(key, mask);
        while (used[i]) {
            if (keys[i] == key) {
                V old = (V) values[i];
                values[i] = value;
                return old;
            }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        values[i] = value;
        size++;
        return null;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    V remove(int key) {
        int i = slot(key, mask);
        while (used[i]) {
            if (keys[i] == key) {
                V old = (V) values[i];
                deleteAt(i);
                return old;
            }
            i = (i + 1) & mask;
        }
        return null;
    }

    /** Empties the slot and moves later entries of the same probe run back into it. */
    private void deleteAt(int hole) {
        int i = hole;
        int j = hole;
        while (true) {
            j = (j + 1) & mask;
            if (!used[j]) {
                break;
            }
            int home = slot(keys[j], mask);
            boolean move = j > i ? (home <= i || home > j) : (home <= i && home > j);
            if (move) {
                keys[i] = keys[j];
                values[i] = values[j];
                i = j;
            }
        }
        used[i] = false;
        values[i] = null;
        size--;
    }

    void clear() {
        if (size == 0) {
            return;
        }
        Arrays.fill(used, false);
        Arrays.fill(values, null);
        size = 0;
    }

    /** Copies up to {@code out.length} keys into {@code out}; returns how many. */
    int keys(int[] out) {
        int n = 0;
        for (int i = 0; i < used.length && n < out.length; i++) {
            if (used[i]) {
                out[n++] = keys[i];
            }
        }
        return n;
    }

    private void grow() {
        int[] oldKeys = keys;
        Object[] oldValues = values;
        boolean[] oldUsed = used;
        allocate(oldKeys.length * 2);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                insertFresh(oldKeys[i], oldValues[i]);
            }
        }
    }

    private void insertFresh(int key, Object value) {
        int i = slot(key, mask);
        while (used[i]) {
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        values[i] = value;
        size++;
    }
}

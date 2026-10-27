package com.elfmcys.ysm.util;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FifoHashMap<K, V> implements Map<K, V> {
    private final ObjectList<K> keys;
    private final ObjectList<V> values;
    private final Object2ObjectArrayMap<K, V> orderMap;
    private final Object2ObjectOpenHashMap<K, V> hashMap;

    public FifoHashMap(K[] keys, V[] values) {
        this.keys = ObjectLists.unmodifiable(ObjectArrayList.wrap(keys));
        this.values = ObjectLists.unmodifiable(ObjectArrayList.wrap(values));
        this.orderMap = new Object2ObjectArrayMap<>(keys, values);
        this.hashMap = new Object2ObjectOpenHashMap<>(this.orderMap);
    }

    @SuppressWarnings("unchecked")
    public FifoHashMap(Object2ObjectArrayMap<K, V> map) {
        K[] keys = map.keySet().toArray((K[])new Object[0]);
        V[] values = map.values().toArray((V[])new Object[0]);
        this.keys = ObjectLists.unmodifiable(ObjectArrayList.wrap(keys));
        this.values = ObjectLists.unmodifiable(ObjectArrayList.wrap(values));
        this.orderMap = new Object2ObjectArrayMap<>(keys, values);
        this.hashMap = new Object2ObjectOpenHashMap<>(this.orderMap);
    }

    @Override
    public int size() {
        return hashMap.size();
    }

    public K getKeyAt(int index) {
        return keys.get(index);
    }

    public List<K> keyList() {
        return keys;
    }

    public V getValueAt(int index) {
        return values.get(index);
    }

    public List<V> valueList() {
        return values;
    }

    @Override
    public boolean isEmpty() {
        return hashMap.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return hashMap.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return hashMap.containsValue(value);
    }

    @Override
    public V get(Object key) {
        return hashMap.get(key);
    }

    @Nullable
    @Override
    public V put(K key, V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(@NotNull Map<? extends K, ? extends V> m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @NotNull
    @Override
    public Set<K> keySet() {
        return orderMap.keySet();
    }

    @NotNull
    @Override
    public Collection<V> values() {
        return orderMap.values();
    }

    @NotNull
    @Override
    public Set<Entry<K, V>> entrySet() {
        return orderMap.entrySet();
    }
}

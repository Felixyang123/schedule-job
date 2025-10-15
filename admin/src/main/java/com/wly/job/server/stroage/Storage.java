package com.wly.job.server.stroage;

import java.util.Collection;
import java.util.List;

public interface Storage<T> {

    T get(String key);

    void put(T value);

    void putAll(Collection<T> values);

    void remove(T value);

    void clear();

    void add(T value);

    void addAll(Collection<T> values);

    List<T> list(Collection<String> keys);
}

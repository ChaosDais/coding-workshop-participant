package com.acme.repos;

import java.util.function.Supplier;

/** Named values shared by every Lambda instance, such as the token signing secret. */
public interface SettingsRepository {
    /** The stored value; when there is none yet, stores the supplied one (the first writer wins). */
    String getOrCreate(String name, Supplier<String> initialValue);
}

package com.codexquotaguard.backend.system;

import java.util.Optional;

/** Private local storage boundary; credential-bearing values never reach a controller. */
public interface SessionStorage extends AutoCloseable {

    void ensureAvailable();

    Optional<SavedSession> load();

    void save(SavedSession session);

    void clear();

    @Override
    void close();
}

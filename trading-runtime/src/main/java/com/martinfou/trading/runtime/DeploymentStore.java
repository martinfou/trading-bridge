package com.martinfou.trading.runtime;

import java.util.List;
import java.util.Optional;

/** Persists strategy deployment mode after successful promote gates. */
public interface DeploymentStore extends AutoCloseable {

    Optional<DeploymentRecord> get(String strategyId);

    void save(DeploymentRecord record);

    List<DeploymentRecord> listAll();

    @Override
    void close();
}

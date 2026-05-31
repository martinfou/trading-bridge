package com.martinfou.trading.runtime;

import java.util.Optional;

/** Optional pluggable validation for promote gates (Epic 19 extension point). */
public interface ValidationModule {

    Optional<GateCheckResult> evaluate(ValidationContext context);
}

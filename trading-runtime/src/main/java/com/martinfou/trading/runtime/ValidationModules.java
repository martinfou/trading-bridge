package com.martinfou.trading.runtime;

import java.util.ArrayList;
import java.util.List;

/** Loads default validation modules for the promote pipeline (Epic 19). */
public final class ValidationModules {

    private ValidationModules() {}

    public static List<ValidationModule> loadDefault() {
        List<ValidationModule> modules = new ArrayList<>();
        OosHoldoutConfig holdout = OosHoldoutConfig.loadDefault();
        if (holdout.enabled()) {
            modules.add(new OosHoldoutValidationModule(holdout));
        }
        ExecutionStressConfig stress = ExecutionStressConfig.loadDefault();
        if (stress.enabled()) {
            modules.add(new ExecutionStressValidationModule(stress));
        }
        return List.copyOf(modules);
    }
}

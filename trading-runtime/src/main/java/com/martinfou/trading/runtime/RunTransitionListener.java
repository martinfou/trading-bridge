package com.martinfou.trading.runtime;

/** Observes {@link RunLifecycle} transitions (audit hooks; no promote/gap logic here). */
@FunctionalInterface
public interface RunTransitionListener {

    void onTransition(RunRecord before, RunRecord after, RunTransition cause);
}

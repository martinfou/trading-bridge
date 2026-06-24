package com.martinfou.trading.core.strategy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation at class level to declare the calibration requirements
 * (time elapsed, bars loaded, or trades executed since last optimization)
 * to monitor strategy drift/freshness.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CalibrationPolicy {
    int maxAgeDays() default 30;
    int maxBarsCount() default 5000;
    int maxTradesCount() default 100;
}

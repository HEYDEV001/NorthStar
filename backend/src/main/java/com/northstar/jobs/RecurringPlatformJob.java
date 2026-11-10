package com.northstar.jobs;

import java.time.Duration;

/** A platform job the worker schedules on start, every {@link #interval()}. Declared as a bean by its module. */
public interface RecurringPlatformJob {

    /** Stable ID; scheduling again under the same ID replaces the previous definition. */
    String id();

    Duration interval();

    PlatformJobRequest request();
}

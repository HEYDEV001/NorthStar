package com.northstar.jobs;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/** Test-only bean that a hand-made lambda job tries to call; the worker's activator must refuse it. */
@Component
public class LambdaTarget {

    static final AtomicBoolean TOUCHED = new AtomicBoolean();

    public void touch() {
        TOUCHED.set(true);
    }
}

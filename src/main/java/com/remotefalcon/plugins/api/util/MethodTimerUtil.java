package com.remotefalcon.plugins.api.util;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MethodTimerUtil {
    public static void logExecutionTime(long startTime, String showSubdomain, String method) {
        long endTime = System.nanoTime();
        long duration = (endTime - startTime);
        double durationInMillis = duration / 1000000.0;
        log.info("{} execution time for {}: {} ms", method, showSubdomain, durationInMillis);
    }
}

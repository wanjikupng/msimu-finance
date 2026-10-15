package com.tracom.lipamdogo.support;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Single source of "now" for the domain: services and entity lifecycle
 * callbacks read the current date/time here instead of calling
 * {@code LocalDate.now()} directly.
 *
 * <p>Normally this is the system clock. The only override is
 * {@link #runAt(LocalDateTime, Supplier)}, which pins time for the calling
 * thread while a block runs. The demo seeder uses it to build a realistic
 * payment history through the real services (so every balance, allocation and
 * receipt stays consistent); tests can use it to exercise date rules. The
 * override is thread-confined, so request threads never see it.</p>
 */
public final class SystemTime {

    private static final ThreadLocal<Clock> OVERRIDE = new ThreadLocal<>();
    private static volatile Clock systemClock = Clock.systemDefaultZone();

    private SystemTime() {
    }

    public static LocalDate today() {
        return LocalDate.now(clock());
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(clock());
    }

    /** Runs {@code work} with "now" pinned to {@code instant} on this thread. */
    public static <T> T runAt(LocalDateTime instant, Supplier<T> work) {
        Clock previous = OVERRIDE.get();
        ZoneAwareFixedClock fixed = new ZoneAwareFixedClock(instant, systemClock.getZone());
        OVERRIDE.set(fixed.clock());
        try {
            return work.get();
        } finally {
            if (previous == null) {
                OVERRIDE.remove();
            } else {
                OVERRIDE.set(previous);
            }
        }
    }

    private static Clock clock() {
        Clock override = OVERRIDE.get();
        return override != null ? override : systemClock;
    }

    private record ZoneAwareFixedClock(LocalDateTime instant, java.time.ZoneId zone) {
        Clock clock() {
            return Clock.fixed(instant.atZone(zone).toInstant(), zone);
        }
    }
}

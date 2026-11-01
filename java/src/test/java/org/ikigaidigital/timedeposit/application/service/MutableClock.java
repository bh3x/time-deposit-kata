package org.ikigaidigital.timedeposit.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock that tests set and advance by hand, so time-dependent rules are tested at exact instants (ADR-0009). */
final class MutableClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    MutableClock(Instant now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    void set(Instant instant) {
        now = instant;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}

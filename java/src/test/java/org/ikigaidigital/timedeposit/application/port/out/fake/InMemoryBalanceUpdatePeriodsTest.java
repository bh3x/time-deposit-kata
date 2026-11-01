package org.ikigaidigital.timedeposit.application.port.out.fake;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The fake holds the port's precondition that times are truncated to microseconds (ADR-0012 C, the precision of
 * timestamptz), so a service that forgets the truncation fails here instead of passing against an exact copy.
 */
class InMemoryBalanceUpdatePeriodsTest {

    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);
    private static final Instant MICROSECONDS = Instant.parse("2026-11-01T10:15:30.123456Z");
    private static final Instant FINER = Instant.parse("2026-11-01T10:15:30.123456001Z");

    // Each call takes the instant under test in one of its time arguments; the row it needs exists beforehand.
    static Stream<Named<BiConsumer<InMemoryBalanceUpdatePeriods, Instant>>> timeArguments() {
        return Stream.of(
                named("reserve", (periods, at) -> periods.reserve(NOVEMBER, at)),
                named("takeOver", (periods, at) -> {
                    periods.storeInProgress(NOVEMBER, MICROSECONDS);
                    periods.takeOver(NOVEMBER, at);
                }),
                named("complete token", (periods, at) -> periods.complete(NOVEMBER, at,
                        new BalanceUpdateOutcome(0, MICROSECONDS), MICROSECONDS)),
                named("complete executedAt", (periods, at) -> periods.complete(NOVEMBER, MICROSECONDS,
                        new BalanceUpdateOutcome(0, at), MICROSECONDS)),
                named("complete completedAt", (periods, at) -> periods.complete(NOVEMBER, MICROSECONDS,
                        new BalanceUpdateOutcome(0, MICROSECONDS), at)),
                named("release", (periods, at) -> periods.release(NOVEMBER, at)),
                named("storeInProgress", (periods, at) -> periods.storeInProgress(NOVEMBER, at)),
                named("storeCompleted executedAt", (periods, at) -> periods.storeCompleted(NOVEMBER,
                        new BalanceUpdateOutcome(0, at), MICROSECONDS)),
                named("storeCompleted completedAt", (periods, at) -> periods.storeCompleted(NOVEMBER,
                        new BalanceUpdateOutcome(0, MICROSECONDS), at)));
    }

    private static Named<BiConsumer<InMemoryBalanceUpdatePeriods, Instant>> named(
            String name, BiConsumer<InMemoryBalanceUpdatePeriods, Instant> call) {
        return Named.of(name, call);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("timeArguments")
    void rejectsInstantsFinerThanMicroseconds(BiConsumer<InMemoryBalanceUpdatePeriods, Instant> call) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> call.accept(new InMemoryBalanceUpdatePeriods(new ArrayList<>()), FINER))
                .withMessageContaining(FINER.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("timeArguments")
    void acceptsInstantsInMicroseconds(BiConsumer<InMemoryBalanceUpdatePeriods, Instant> call) {
        assertThatCode(() -> call.accept(new InMemoryBalanceUpdatePeriods(new ArrayList<>()), MICROSECONDS))
                .doesNotThrowAnyException();
    }
}

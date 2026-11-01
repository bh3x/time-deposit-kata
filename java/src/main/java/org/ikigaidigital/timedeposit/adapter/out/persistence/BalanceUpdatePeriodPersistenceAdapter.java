package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdatePeriodState.InProgress;
import org.ikigaidigital.timedeposit.application.port.out.RecordBalanceUpdatePort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code "balanceUpdatePeriods"} queries of ADR-0013 (ADR-0012's mechanisms keyed by the month) as native SQL:
 * {@code ON CONFLICT DO NOTHING} and {@code FOR UPDATE SKIP LOCKED} are PostgreSQL specifics, which ADR-0008 keeps
 * inside the persistence adapter, and the table has no JPA entity, because nothing reads it as an object graph.
 * {@link JdbcClient} joins the caller's Spring transaction, the same one the JPA statements use. A month is stored as
 * its first day ({@code DATE}, bound as {@link java.time.LocalDate}, so no time zone can shift it); timestamps are
 * bound as {@code OffsetDateTime} in UTC, the type the PostgreSQL driver maps to {@code timestamptz}.
 */
@Component
class BalanceUpdatePeriodPersistenceAdapter implements RecordBalanceUpdatePort {

    private static final String STATE_COLUMNS = "\"status\", \"createdAt\", \"processedCount\", \"executedAt\"";

    private static final RowMapper<BalanceUpdatePeriodState> STATE = (row, rowNumber) ->
            "COMPLETED".equals(row.getString("status"))
                    ? new Completed(new BalanceUpdateOutcome(row.getInt("processedCount"),
                            row.getObject("executedAt", OffsetDateTime.class).toInstant()))
                    : new InProgress(row.getObject("createdAt", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    BalanceUpdatePeriodPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean reserve(YearMonth period, Instant token) {
        return jdbc.sql("""
                insert into "balanceUpdatePeriods" ("period", "status", "createdAt")
                values (:period, 'IN_PROGRESS', :token)
                on conflict ("period") do nothing
                """).param("period", period.atDay(1)).param("token", utc(token)).update() == 1;
    }

    @Override
    public Optional<BalanceUpdatePeriodState> findUnlocked(YearMonth period) {
        return jdbc.sql("select " + STATE_COLUMNS + """
                 from "balanceUpdatePeriods" where "period" = :period for update skip locked""")
                .param("period", period.atDay(1)).query(STATE).optional();
    }

    @Override
    public void takeOver(YearMonth period, Instant token) {
        int updated = jdbc.sql("""
                update "balanceUpdatePeriods" set "createdAt" = :token
                where "period" = :period and "status" = 'IN_PROGRESS'
                """).param("period", period.atDay(1)).param("token", utc(token)).update();
        if (updated != 1) {
            throw new IllegalStateException("balance update period " + period + " is not in progress");
        }
    }

    @Override
    public Optional<BalanceUpdatePeriodState> lock(YearMonth period) {
        return jdbc.sql("select " + STATE_COLUMNS + """
                 from "balanceUpdatePeriods" where "period" = :period for update""")
                .param("period", period.atDay(1)).query(STATE).optional();
    }

    @Override
    public boolean complete(YearMonth period, Instant token, BalanceUpdateOutcome outcome, Instant completedAt) {
        return jdbc.sql("""
                update "balanceUpdatePeriods"
                set "status" = 'COMPLETED', "processedCount" = :processedCount, "executedAt" = :executedAt,
                    "completedAt" = :completedAt
                where "period" = :period and "status" = 'IN_PROGRESS' and "createdAt" = :token
                """)
                .param("processedCount", outcome.processedCount())
                .param("executedAt", utc(outcome.executedAt()))
                .param("completedAt", utc(completedAt))
                .param("period", period.atDay(1))
                .param("token", utc(token))
                .update() == 1;
    }

    @Override
    public void release(YearMonth period, Instant token) {
        jdbc.sql("""
                delete from "balanceUpdatePeriods"
                where "period" = :period and "status" = 'IN_PROGRESS' and "createdAt" = :token
                """).param("period", period.atDay(1)).param("token", utc(token)).update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

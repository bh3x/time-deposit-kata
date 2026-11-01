package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.Completed;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateKeyState.InProgress;
import org.ikigaidigital.timedeposit.application.model.BalanceUpdateOutcome;
import org.ikigaidigital.timedeposit.application.port.out.RecordBalanceUpdatePort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code "idempotencyKeys"} queries of ADR-0012 as native SQL: {@code ON CONFLICT DO NOTHING} and
 * {@code FOR UPDATE SKIP LOCKED} are PostgreSQL specifics, which ADR-0008 keeps inside the persistence adapter, and
 * the table has no JPA entity, because nothing reads it as an object graph. {@link JdbcClient} joins the caller's
 * Spring transaction, the same one the JPA statements use. Timestamps are bound as {@code OffsetDateTime} in UTC, the
 * type the PostgreSQL driver maps to {@code timestamptz}.
 */
@Component
class IdempotencyKeyPersistenceAdapter implements RecordBalanceUpdatePort {

    private static final String STATE_COLUMNS = "\"status\", \"createdAt\", \"processedCount\", \"executedAt\"";

    private static final RowMapper<BalanceUpdateKeyState> STATE = (row, rowNumber) ->
            "COMPLETED".equals(row.getString("status"))
                    ? new Completed(new BalanceUpdateOutcome(row.getInt("processedCount"),
                            row.getObject("executedAt", OffsetDateTime.class).toInstant()))
                    : new InProgress(row.getObject("createdAt", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    IdempotencyKeyPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    // A DELETE has no locking clause of its own, so the subquery carries SKIP LOCKED (ADR-0012, D).
    @Override
    public void purge(Instant cutoff) {
        jdbc.sql("""
                delete from "idempotencyKeys" where "idempotencyKey" in (
                    select "idempotencyKey" from "idempotencyKeys"
                    where ("status" = 'COMPLETED' and "completedAt" <= :cutoff)
                       or ("status" = 'IN_PROGRESS' and "createdAt" <= :cutoff)
                    for update skip locked)
                """).param("cutoff", utc(cutoff)).update();
    }

    @Override
    public boolean reserve(String key, Instant token) {
        return jdbc.sql("""
                insert into "idempotencyKeys" ("idempotencyKey", "status", "createdAt")
                values (:key, 'IN_PROGRESS', :token)
                on conflict ("idempotencyKey") do nothing
                """).param("key", key).param("token", utc(token)).update() == 1;
    }

    @Override
    public Optional<BalanceUpdateKeyState> findUnlocked(String key) {
        return jdbc.sql("select " + STATE_COLUMNS + """
                 from "idempotencyKeys" where "idempotencyKey" = :key for update skip locked""")
                .param("key", key).query(STATE).optional();
    }

    @Override
    public void takeOver(String key, Instant token) {
        int updated = jdbc.sql("""
                update "idempotencyKeys" set "createdAt" = :token
                where "idempotencyKey" = :key and "status" = 'IN_PROGRESS'
                """).param("key", key).param("token", utc(token)).update();
        if (updated != 1) {
            throw new IllegalStateException("idempotency key is not in progress");
        }
    }

    @Override
    public Optional<BalanceUpdateKeyState> lock(String key) {
        return jdbc.sql("select " + STATE_COLUMNS + """
                 from "idempotencyKeys" where "idempotencyKey" = :key for update""")
                .param("key", key).query(STATE).optional();
    }

    @Override
    public boolean complete(String key, Instant token, BalanceUpdateOutcome outcome, Instant completedAt) {
        return jdbc.sql("""
                update "idempotencyKeys"
                set "status" = 'COMPLETED', "processedCount" = :processedCount, "executedAt" = :executedAt,
                    "completedAt" = :completedAt
                where "idempotencyKey" = :key and "status" = 'IN_PROGRESS' and "createdAt" = :token
                """)
                .param("processedCount", outcome.processedCount())
                .param("executedAt", utc(outcome.executedAt()))
                .param("completedAt", utc(completedAt))
                .param("key", key)
                .param("token", utc(token))
                .update() == 1;
    }

    @Override
    public void release(String key, Instant token) {
        jdbc.sql("""
                delete from "idempotencyKeys"
                where "idempotencyKey" = :key and "status" = 'IN_PROGRESS' and "createdAt" = :token
                """).param("key", key).param("token", utc(token)).update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

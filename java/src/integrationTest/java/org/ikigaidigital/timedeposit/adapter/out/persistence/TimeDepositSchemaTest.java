package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.ikigaidigital.testcontainers.PostgresContainerConfiguration;
import org.ikigaidigital.timedeposit.TimeDepositRows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Flyway schema implements ADR-0006 (scenarios S1–S4 in docs/testing.md). Rejections are identified by SQLState,
 * because ADR-0006 names no constraints; each rejected row is its own test invocation, since a failed statement aborts
 * the PostgreSQL transaction.
 */
@DataJpaTest
@Import(PostgresContainerConfiguration.class)
class TimeDepositSchemaTest {

    private static final String NOT_NULL_VIOLATION = "23502";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String RESTRICT_VIOLATION = "23001";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String NUMERIC_OUT_OF_RANGE = "22003";
    private static final String STRING_TOO_LONG = "22001";
    private static final String GENERATED_ALWAYS = "428C9";

    @Autowired
    private DataSource dataSource;

    private JdbcClient jdbc;
    private TimeDepositRows rows;

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        rows = new TimeDepositRows(jdbc);
    }

    private void assertRejected(Runnable statement, String sqlState) {
        assertThatThrownBy(statement::run)
                .rootCause()
                .isInstanceOf(SQLException.class)
                .extracting(cause -> ((SQLException) cause).getSQLState())
                .isEqualTo(sqlState);
    }

    @Test
    void schemaIsMigratedByFlywayOnPostgres18() {
        String database = jdbc.sql("select current_setting('server_version')").query(String.class).single();
        Boolean v1Succeeded = jdbc.sql("select success from flyway_schema_history where version = '1'")
                .query(Boolean.class).optional().orElse(false);

        assertThat(database).startsWith("18.");
        assertThat(v1Succeeded).isTrue();
    }

    // S7, other side: without the demo profile no seed is applied.
    @Test
    void appliesNoDemoSeedWithoutTheDemoProfile() {
        Integer seedRuns = jdbc.sql("select count(*) from flyway_schema_history where script = 'R__demo_seed.sql'")
                .query(Integer.class).single();
        Integer deposits = jdbc.sql("select count(*) from \"timeDeposits\"").query(Integer.class).single();

        assertThat(seedRuns).isZero();
        assertThat(deposits).isZero();
    }

    @Nested
    @DisplayName("S1: tables, columns and index with the brief's exact names")
    class Structure {

        @ParameterizedTest(name = "table \"{0}\"")
        @ValueSource(strings = {"timeDeposits", "withdrawals", "idempotencyKeys"})
        void tablesExistWithExactQuotedNames(String table) {
            Integer exact = jdbc.sql("select count(*) from information_schema.tables where table_schema = current_schema() and table_name = ?")
                    .param(table).query(Integer.class).single();
            Integer folded = jdbc.sql("select count(*) from information_schema.tables where table_schema = current_schema() and table_name = ?")
                    .param(table.toLowerCase()).query(Integer.class).single();

            assertThat(exact).isEqualTo(1);
            if (!table.equals(table.toLowerCase())) {
                assertThat(folded).as("unquoted, lower-case variant").isZero();
            }
        }

        @ParameterizedTest(name = "{0}.{1} is {2}, nullable {3}")
        @CsvSource({
                "timeDeposits,    id,             integer,                  NO",
                "timeDeposits,    publicId,       uuid,                     NO",
                "timeDeposits,    planType,       text,                     NO",
                "timeDeposits,    days,           integer,                  NO",
                "timeDeposits,    balance,        numeric,                  NO",
                "withdrawals,     id,             integer,                  NO",
                "withdrawals,     publicId,       uuid,                     NO",
                "withdrawals,     timeDepositId,  integer,                  NO",
                "withdrawals,     amount,         numeric,                  NO",
                "withdrawals,     date,           date,                     NO",
                "idempotencyKeys, idempotencyKey, character varying,        NO",
                "idempotencyKeys, status,         character varying,        NO",
                "idempotencyKeys, processedCount, integer,                  YES",
                "idempotencyKeys, executedAt,     timestamp with time zone, YES",
                "idempotencyKeys, createdAt,      timestamp with time zone, NO",
                "idempotencyKeys, completedAt,    timestamp with time zone, YES"})
        void columnsHaveTheDecidedTypesAndNullability(String table, String column, String type, String nullable) {
            var actual = jdbc.sql("""
                            select data_type, is_nullable from information_schema.columns
                            where table_schema = current_schema() and table_name = ? and column_name = ?
                            """)
                    .params(table, column)
                    .query((rs, n) -> rs.getString("data_type") + "/" + rs.getString("is_nullable"))
                    .single();

            assertThat(actual).isEqualTo(type + "/" + nullable);
        }

        @ParameterizedTest(name = "{0}.{1} has precision {2}, scale {3}")
        @CsvSource({"timeDeposits, balance, 15, 2", "withdrawals, amount, 15, 2"})
        void moneyColumnsHaveCentPrecisionBelowTenToTheThirteen(String table, String column, int precision,
                                                                int scale) {
            var actual = jdbc.sql("""
                            select numeric_precision, numeric_scale from information_schema.columns
                            where table_schema = current_schema() and table_name = ? and column_name = ?
                            """)
                    .params(table, column)
                    .query((rs, n) -> rs.getInt("numeric_precision") + "," + rs.getInt("numeric_scale"))
                    .single();

            assertThat(actual).isEqualTo(precision + "," + scale);
        }

        @ParameterizedTest(name = "idempotencyKeys.{0} holds at most {1} characters")
        @CsvSource({"idempotencyKey, 255", "status, 20"})
        void textColumnsHaveTheirMaximumLength(String column, int maximumLength) {
            Integer actual = jdbc.sql("""
                            select character_maximum_length from information_schema.columns
                            where table_schema = current_schema() and table_name = 'idempotencyKeys'
                              and column_name = ?
                            """)
                    .param(column).query(Integer.class).single();

            assertThat(actual).isEqualTo(maximumLength);
        }

        @ParameterizedTest(name = "\"{0}\".id is GENERATED ALWAYS AS IDENTITY")
        @ValueSource(strings = {"timeDeposits", "withdrawals"})
        void idsAreAlwaysGenerated(String table) {
            String generation = jdbc.sql("""
                            select identity_generation from information_schema.columns
                            where table_schema = current_schema() and table_name = ? and column_name = 'id'
                            """)
                    .param(table).query(String.class).single();

            assertThat(generation).isEqualTo("ALWAYS");
        }

        @Test
        void withdrawalsAreIndexedByTimeDepositId() {
            Integer indexes = jdbc.sql("""
                            select count(*) from pg_indexes
                            where tablename = 'withdrawals' and indexdef like '%("timeDepositId"%'
                            """)
                    .query(Integer.class).single();

            assertThat(indexes).isPositive();
        }
    }

    @Nested
    @DisplayName("S2: constraints on time deposits")
    class TimeDepositConstraints {

        @ParameterizedTest(name = "null {0} rejected")
        @CsvSource(nullValues = "NULL", value = {
                "planType, NULL,  31, 100.00",
                "days,     basic, NULL, 100.00",
                "balance,  basic, 31, NULL"})
        void rejectsMissingRequiredValues(String column, String planType, Integer days, String balance) {
            assertRejected(() -> jdbc.sql("""
                            insert into "timeDeposits" ("planType", "days", "balance") values (?, ?, ?::numeric)
                            """).params(planType, days, balance).update(), NOT_NULL_VIOLATION);
        }

        @ParameterizedTest(name = "days {0}, balance {1} rejected with {2}")
        @CsvSource({
                "-1, 100.00,            " + CHECK_VIOLATION,
                "31, -0.01,             " + CHECK_VIOLATION,
                "31, 10000000000000.00, " + NUMERIC_OUT_OF_RANGE})
        void rejectsOutOfRangeValues(int days, String balance, String sqlState) {
            assertRejected(() -> rows.insertDeposit("basic", days, balance), sqlState);
        }

        // Both bounds inclusive; 9,999,999,999,999.99 is the NUMERIC(15,2) maximum. No CHECK on planType: the legacy
        // calculator accepts unknown plans (ADR-0006), and text keeps whitespace and case.
        @ParameterizedTest(name = "plan \"{0}\", days {1}, balance {2} accepted")
        @CsvSource({
                "basic,     0, 0.00",
                "basic,    31, 9999999999999.99",
                "gold,     31, 100.00",
                "Basic,    31, 100.00",
                "' basic ', 31, 100.00",
                "'',       31, 100.00"})
        void acceptsBoundaryValues(String planType, int days, String balance) {
            int id = rows.insertDeposit(planType, days, balance);

            String stored = jdbc.sql("select \"planType\" from \"timeDeposits\" where \"id\" = ?")
                    .param(id).query(String.class).single();
            assertThat(stored).isEqualTo(planType);
        }

        // Known issue #8 (ADR-0011): pins what PostgreSQL does with non-finite balances, which the calculator cannot
        // round. Observed, not designed: whether the schema should change belongs to the balance update consistency ADR.
        // The value is cast in SQL, because java.math.BigDecimal (used by TimeDepositRows) cannot hold it.
        @Test
        void acceptsANotANumberBalance() {
            insertBasicDepositWithBalance("NaN");

            String stored = jdbc.sql("select \"balance\"::text from \"timeDeposits\"").query(String.class).single();
            assertThat(stored).isEqualTo("NaN");
        }

        @ParameterizedTest(name = "balance {0} rejected")
        @ValueSource(strings = {"Infinity", "-Infinity"})
        void rejectsAnInfiniteBalance(String balance) {
            assertRejected(() -> insertBasicDepositWithBalance(balance), NUMERIC_OUT_OF_RANGE);
        }

        private void insertBasicDepositWithBalance(String balance) {
            jdbc.sql("""
                    insert into "timeDeposits" ("planType", "days", "balance") values ('basic', 31, ?::numeric)
                    """).param(balance).update();
        }

        @Test
        void rejectsAnExplicitId() {
            assertRejected(() -> jdbc.sql("""
                    insert into "timeDeposits" ("id", "planType", "days", "balance") values (1, 'basic', 31, 1.00)
                    """).update(), GENERATED_ALWAYS);
        }

        @Test
        void rejectsADuplicatePublicId() {
            UUID publicId = UUID.fromString("00000000-0000-4000-8000-000000000001");
            rows.insertDeposit(publicId, "basic", 31, "1.00");

            assertRejected(() -> rows.insertDeposit(publicId, "basic", 31, "1.00"), UNIQUE_VIOLATION);
        }
    }

    @Nested
    @DisplayName("S2: constraints on withdrawals")
    class WithdrawalConstraints {

        @ParameterizedTest(name = "amount {0} rejected")
        @ValueSource(strings = {"0.00", "-0.01"})
        void rejectsANonPositiveAmount(String amount) {
            int deposit = rows.insertDeposit("basic", 31, "100.00");

            assertRejected(() -> rows.insertWithdrawal(deposit, amount, "2026-01-01"), CHECK_VIOLATION);
        }

        @Test
        void acceptsTheSmallestAmount() {
            int deposit = rows.insertDeposit("basic", 31, "100.00");

            assertThat(rows.insertWithdrawal(deposit, "0.01", "2026-01-01")).isPositive();
        }

        @ParameterizedTest(name = "null {0} rejected")
        @CsvSource(nullValues = "NULL", value = {
                "amount, NULL,  2026-01-01",
                "date,   10.00, NULL"})
        void rejectsMissingRequiredValues(String column, String amount, String date) {
            int deposit = rows.insertDeposit("basic", 31, "100.00");

            assertRejected(() -> jdbc.sql("""
                            insert into "withdrawals" ("timeDepositId", "amount", "date")
                            values (?, ?::numeric, ?::date)
                            """).params(deposit, amount, date).update(), NOT_NULL_VIOLATION);
        }

        @Test
        void rejectsAMissingTimeDeposit() {
            assertRejected(() -> jdbc.sql("""
                    insert into "withdrawals" ("timeDepositId", "amount", "date") values (null, 10.00, '2026-01-01')
                    """).update(), NOT_NULL_VIOLATION);
        }

        @Test
        void rejectsADuplicatePublicId() {
            int deposit = rows.insertDeposit("basic", 31, "100.00");
            UUID publicId = UUID.fromString("00000000-0000-4000-8000-000000000101");
            insertWithdrawal(publicId, deposit);

            assertRejected(() -> insertWithdrawal(publicId, deposit), UNIQUE_VIOLATION);
        }

        private void insertWithdrawal(UUID publicId, int deposit) {
            jdbc.sql("""
                            insert into "withdrawals" ("publicId", "timeDepositId", "amount", "date")
                            values (?, ?, 10.00, '2026-01-01')
                            """)
                    .params(publicId, deposit).update();
        }

        @Test
        void rejectsAnUnknownTimeDeposit() {
            assertRejected(() -> rows.insertWithdrawal(-1, "10.00", "2026-01-01"), FOREIGN_KEY_VIOLATION);
        }
    }

    @Nested
    @DisplayName("S2: constraints on idempotency keys")
    class IdempotencyKeyConstraints {

        private void insertKey(String key, String status, Integer processedCount, String executedAt,
                               String createdAt, String completedAt) {
            jdbc.sql("""
                            insert into "idempotencyKeys"
                                ("idempotencyKey", "status", "processedCount", "executedAt", "createdAt", "completedAt")
                            values (?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                            """)
                    .params(key, status, processedCount, executedAt, createdAt, completedAt).update();
        }

        // Exactly two states (ADR-0006): IN_PROGRESS without outcome, COMPLETED with the full outcome.
        @ParameterizedTest(name = "{0}: status {1}, count {2}, executed {3}, completed {4}")
        @CsvSource(nullValues = "NULL", value = {
                "unknown status,           FOO,         NULL, NULL,                 NULL",
                "lower-case status,        completed,   1,    2026-10-31T10:00:00Z, 2026-10-31T10:00:01Z",
                "completed without count,  COMPLETED,   NULL, 2026-10-31T10:00:00Z, 2026-10-31T10:00:01Z",
                "completed without run,    COMPLETED,   1,    NULL,                 2026-10-31T10:00:01Z",
                "completed without end,    COMPLETED,   1,    2026-10-31T10:00:00Z, NULL",
                "in progress with count,   IN_PROGRESS, 1,    NULL,                 NULL",
                "in progress with run,     IN_PROGRESS, NULL, 2026-10-31T10:00:00Z, NULL",
                "in progress with end,     IN_PROGRESS, NULL, NULL,                 2026-10-31T10:00:01Z",
                "negative count,           COMPLETED,   -1,   2026-10-31T10:00:00Z, 2026-10-31T10:00:01Z"})
        void rejectsInconsistentRows(String description, String status, Integer processedCount, String executedAt,
                                     String completedAt) {
            assertRejected(() -> insertKey("key", status, processedCount, executedAt, "2026-10-31T09:59:59Z",
                    completedAt), CHECK_VIOLATION);
        }

        @ParameterizedTest(name = "{0} accepted")
        @CsvSource(nullValues = "NULL", value = {
                "in progress,          k1, IN_PROGRESS, NULL, NULL,                 NULL",
                "completed with zero,  k2, COMPLETED,   0,    2026-10-31T10:00:00Z, 2026-10-31T10:00:01Z"})
        void acceptsConsistentRows(String description, String key, String status, Integer processedCount,
                                   String executedAt, String completedAt) {
            insertKey(key, status, processedCount, executedAt, "2026-10-31T09:59:59Z", completedAt);

            assertThat(jdbc.sql("select count(*) from \"idempotencyKeys\" where \"idempotencyKey\" = ?")
                    .param(key).query(Integer.class).single()).isEqualTo(1);
        }

        // The column holds up to 255 characters (storage half of P8); the header's minimum of 1 character is validated
        // by the REST adapter (ADR-0004, scenario P7).
        @ParameterizedTest(name = "key of length {0} accepted")
        @ValueSource(ints = {1, 255})
        void acceptsKeysOfTheAllowedLength(int length) {
            String key = "k".repeat(length);

            insertKey(key, "IN_PROGRESS", null, null, "2026-10-31T09:59:59Z", null);

            assertThat(jdbc.sql("select \"idempotencyKey\" from \"idempotencyKeys\"").query(String.class).single())
                    .isEqualTo(key);
        }

        @Test
        void rejectsATooLongKey() {
            assertRejected(() -> insertKey("k".repeat(256), "IN_PROGRESS", null, null, "2026-10-31T09:59:59Z", null),
                    STRING_TOO_LONG);
        }

        @Test
        void rejectsAMissingCreationTime() {
            assertRejected(() -> insertKey("key", "IN_PROGRESS", null, null, null, null), NOT_NULL_VIOLATION);
        }

        @Test
        void rejectsADuplicateKey() {
            insertKey("key", "IN_PROGRESS", null, null, "2026-10-31T09:59:59Z", null);

            assertRejected(() -> insertKey("key", "IN_PROGRESS", null, null, "2026-10-31T09:59:59Z", null),
                    UNIQUE_VIOLATION);
        }
    }

    @Nested
    @DisplayName("S3: withdrawal history is never deleted as a side effect")
    class DeleteRestriction {

        @Test
        void rejectsDeletingADepositWithWithdrawals() {
            int deposit = rows.insertDeposit("basic", 31, "100.00");
            rows.insertWithdrawal(deposit, "10.00", "2026-01-01");

            // ON DELETE RESTRICT reports restrict_violation (23001), not the generic foreign key violation (23503).
            assertRejected(() -> jdbc.sql("delete from \"timeDeposits\" where \"id\" = ?").param(deposit).update(),
                    RESTRICT_VIOLATION);
        }

        @Test
        void deletesADepositWithoutWithdrawals() {
            int deposit = rows.insertDeposit("basic", 31, "100.00");

            int deleted = jdbc.sql("delete from \"timeDeposits\" where \"id\" = ?").param(deposit).update();

            assertThat(deleted).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("S4: public ids")
    class PublicIds {

        @Test
        void generatesDistinctRandomPublicIdsWhenOmitted() {
            int first = rows.insertDeposit("basic", 31, "1.00");
            int second = rows.insertDeposit("basic", 31, "1.00");
            int withdrawal = rows.insertWithdrawal(first, "0.50", "2026-01-01");

            UUID a = publicIdOf("timeDeposits", first);
            UUID b = publicIdOf("timeDeposits", second);
            UUID w = publicIdOf("withdrawals", withdrawal);

            assertThat(a).isNotEqualTo(b);
            assertThat(new UUID[] {a, b, w}).allSatisfy(id -> {
                assertThat(id.version()).isEqualTo(4);
                assertThat(id.variant()).isEqualTo(2);
            });
        }

        @Test
        void keepsAnExplicitPublicId() {
            UUID publicId = UUID.fromString("00000000-0000-4000-8000-000000000042");

            int id = rows.insertDeposit(publicId, "basic", 31, "1.00");

            assertThat(publicIdOf("timeDeposits", id)).isEqualTo(publicId);
        }

        private UUID publicIdOf(String table, int id) {
            return jdbc.sql("select \"publicId\" from \"" + table + "\" where \"id\" = ?")
                    .param(id).query(UUID.class).single();
        }
    }
}

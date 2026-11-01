package org.ikigaidigital.timedeposit;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Inserts rows with plain SQL and the quoted camelCase names of ADR-0006, so tests state exactly the data they depend
 * on (ADR-0009) and exercise the schema rather than the JPA mapping. Shared by the persistence and end-to-end tests
 * of the integration suite.
 */
public final class TimeDepositRows {

    private final JdbcClient jdbc;

    public TimeDepositRows(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A fixed, valid version-4 UUID per number; the same scheme as {@code TimeDepositFixtures.uuid} in the unit suite,
     * which this suite cannot see.
     */
    public static UUID uuid(int number) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(number));
    }

    public int insertDeposit(String planType, int days, String balance) {
        return jdbc.sql("""
                        insert into "timeDeposits" ("planType", "days", "balance")
                        values (?, ?, ?) returning "id"
                        """)
                .params(planType, days, new BigDecimal(balance))
                .query(Integer.class).single();
    }

    public int insertDeposit(UUID publicId, String planType, int days, String balance) {
        return jdbc.sql("""
                        insert into "timeDeposits" ("publicId", "planType", "days", "balance")
                        values (?, ?, ?, ?) returning "id"
                        """)
                .params(publicId, planType, days, new BigDecimal(balance))
                .query(Integer.class).single();
    }

    public int insertWithdrawal(UUID publicId, int timeDepositId, String amount, String date) {
        return jdbc.sql("""
                        insert into "withdrawals" ("publicId", "timeDepositId", "amount", "date")
                        values (?, ?, ?, ?) returning "id"
                        """)
                .params(publicId, timeDepositId, new BigDecimal(amount), LocalDate.parse(date))
                .query(Integer.class).single();
    }

    public int insertWithdrawal(int timeDepositId, String amount, String date) {
        return jdbc.sql("""
                        insert into "withdrawals" ("timeDepositId", "amount", "date")
                        values (?, ?, ?) returning "id"
                        """)
                .params(timeDepositId, new BigDecimal(amount), LocalDate.parse(date))
                .query(Integer.class).single();
    }
}

package org.ikigaidigital.timedeposit.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/** Spring Data access to {@code "timeDeposits"}, used only by the persistence adapter. */
interface TimeDepositJpaRepository extends Repository<TimeDepositEntity, Integer> {

    /**
     * All deposits with their withdrawals in one statement (a consistent snapshot, see
     * {@code LoadTimeDepositViewsPort}): deposits by internal key, withdrawals by date and then internal key. The left
     * join keeps deposits without withdrawals.
     */
    @Query("""
            select d from TimeDepositEntity d
            left join fetch d.withdrawals w
            order by d.id, w.date, w.id
            """)
    List<TimeDepositEntity> findAllWithWithdrawals();

    /**
     * All deposits, locked until the transaction ends (ADR-0012, A): on {@code "timeDeposits"} alone, without the
     * withdrawals, and by internal key, so every run locks the rows in the same order and two runs cannot deadlock.
     * On PostgreSQL, Hibernate emits {@code FOR NO KEY UPDATE} here, not the {@code FOR UPDATE} ADR-0012 names:
     * it serialises runs all the same (pinned in {@code BalanceUpdateLockingTest}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from TimeDepositEntity d order by d.id")
    List<TimeDepositEntity> findAllForUpdate();

    /**
     * Writes one balance and nothing else; returns the number of rows changed. An explicit statement rather than
     * dirty checking, so it is executed when called — before the month's switch to completed (ADR-0012, ADR-0013) —
     * and the row count shows a missing deposit. The persistence context is cleared afterwards, so a later read does
     * not return the stale entity.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TimeDepositEntity d set d.balance = :balance where d.id = :id")
    int updateBalance(@Param("id") int id, @Param("balance") BigDecimal balance);
}

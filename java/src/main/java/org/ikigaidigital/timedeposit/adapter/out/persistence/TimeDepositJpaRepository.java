package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

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
}

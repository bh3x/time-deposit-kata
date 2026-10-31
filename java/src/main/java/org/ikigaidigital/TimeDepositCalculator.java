package org.ikigaidigital;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class TimeDepositCalculator {
    public void updateBalance(List<TimeDeposit> timeDeposits) {
        for (int i = 0; i < timeDeposits.size(); i++) {
            double interest = 0;

            if (timeDeposits.get(i).getDays() > 30) {
                if (timeDeposits.get(i).getPlanType().equals("student")) {
                    if (timeDeposits.get(i).getDays() < 366) {
                        interest += timeDeposits.get(i).getBalance() * 0.03 / 12;
                    }
                } else if (timeDeposits.get(i).getPlanType().equals("premium")) {
                    if (timeDeposits.get(i).getDays() > 45) {
                        interest += timeDeposits.get(i).getBalance() * 0.05 / 12;
                    }
                } else if (timeDeposits.get(i).getPlanType().equals("basic")) {
                    interest += timeDeposits.get(i).getBalance() * 0.01 / 12;
                }
            }

            double a2d = timeDeposits.get(i).getBalance() + (new BigDecimal(interest).setScale(2, RoundingMode.HALF_UP)).doubleValue();
            timeDeposits.get(i).setBalance(a2d);
        }
    }
}

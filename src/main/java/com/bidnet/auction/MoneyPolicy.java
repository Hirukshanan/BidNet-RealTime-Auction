package com.bidnet.auction;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class MoneyPolicy {
    static final int SCALE = 2;
    private static final BigDecimal NEXT_BID_MULTIPLIER = new BigDecimal("1.05");

    private MoneyPolicy() {
    }

    static boolean hasSupportedPrecision(BigDecimal amount) {
        return amount != null && amount.scale() <= SCALE;
    }

    static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    static BigDecimal nextMinimum(BigDecimal currentPrice) {
        return currentPrice.multiply(NEXT_BID_MULTIPLIER).setScale(SCALE, RoundingMode.CEILING);
    }
}

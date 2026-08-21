package me.lawsonhart.kremlin.misc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Mirrors MerchantOffer#updateDemand, which a villager runs on every timed restock and which Paper
 * leaves unguarded by default. Demand only ever raises the price of the first ingredient - negative
 * demand is floored to no change - so demand going positive is the thing that buries a discount.
 */
class UnlimitedTradesTest {

    private static int updateDemand(final int demand, final int uses, final int maxUses) {
        return demand + uses - (maxUses - uses);
    }

    @Test
    void maxValueMaxUsesUnderflowsDemandIntoRaisingPrices() {
        int demand = 0;
        boolean positive = false;
        for (int restock = 0; restock < 8 && !positive; restock++) {
            demand = updateDemand(demand, 0, Integer.MAX_VALUE);
            positive = demand > 0;
        }
        assertTrue(positive, "expected demand to underflow positive and start inflating prices");
    }

    @Test
    void repairedMaxUsesNeverInflatesPrices() {
        int demand = 0;
        for (int restock = 0; restock < 100_000; restock++) {
            demand = updateDemand(demand, 0, UnlimitedTrades.REPAIR_MAX_USES);
            assertTrue(demand <= 0, "demand must never climb into price-raising territory");
        }
    }
}

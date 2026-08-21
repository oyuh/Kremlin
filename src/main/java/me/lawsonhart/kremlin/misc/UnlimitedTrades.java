package me.lawsonhart.kremlin.misc;

import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;

/**
 * Trades lock once uses reaches maxUses. Rather than putting maxUses out of reach, this stops uses
 * from climbing in the first place - {@code processTrade} only calls {@code offer.increaseUses()}
 * when {@code willIncreaseTradeUses()} is true, so declining that leaves the trade permanently
 * unlocked with every other number exactly as vanilla wrote it.
 *
 * <p>The previous build instead set maxUses to Integer.MAX_VALUE, which broke pricing. A villager
 * restocks on a timer, not on demand, and every restock runs
 * {@code demand = demand + uses - (maxUses - uses)}. Paper only clamps that when
 * {@code preventNegativeVillagerDemand} is enabled and it defaults to false, so subtracting ~2.1
 * billion twice underflows demand back into large positive numbers. Demand raises the cost of the
 * first ingredient, so prices climbed until they pinned at a stack and the Hero of the Village and
 * cured-zombie discounts - real, but only worth a few emeralds - stopped being visible. Leaving
 * maxUses alone keeps that arithmetic in vanilla range.
 *
 * <p>Experience is untouched: {@code processTrade} gates {@code rewardTradeXp} on
 * {@code isRewardingExp()}, which is separate from uses, so villagers still level normally.
 */
public final class UnlimitedTrades implements Listener {

    /**
     * Offers saved by the old build carry maxUses = Integer.MAX_VALUE and a corrupted demand.
     * The original per-trade maxUses is unrecoverable, so repair uses vanilla's most common value;
     * with uses pinned at 0 it only ever feeds the restock demand arithmetic, never the UI.
     */
    static final int REPAIR_MAX_USES = 12;

    /** Villagers and wandering traders only - custom plugin merchants keep their own limits. */
    @EventHandler(ignoreCancelled = true)
    public void onTrade(final PlayerTradeEvent event) {
        event.setIncreaseTradeUses(false);
    }

    @EventHandler
    public void onOpen(final InventoryOpenEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory inventory)
                || !(inventory.getMerchant() instanceof AbstractVillager)) {
            return;
        }

        // These recipes delegate straight to the villager's live offers, so writing to them here is
        // enough - and it lands after updateSpecialPrices() but before the offers packet is built,
        // so the discounts applied on open survive.
        for (final MerchantRecipe recipe : inventory.getMerchant().getRecipes()) {
            if (recipe.getMaxUses() == Integer.MAX_VALUE) {
                recipe.setMaxUses(REPAIR_MAX_USES);
                recipe.setDemand(0);
            }
            // Unlocks anything the villager locked before this listener was in place.
            recipe.setUses(0);
        }
    }
}

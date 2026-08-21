package me.lawsonhart.kremlin.misc;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

/**
 * Vanilla builds advancement and death messages from the *entity* display name (the raw username,
 * team-formatted), never from Bukkit's Player#displayName(). Both messages are translatable
 * components whose first argument is the player, so swapping that one argument keeps the key,
 * the fallback, the advancement/weapon arguments and all styling exactly as the server built them.
 */
public final class DisplayNameMessages implements Listener {

    @EventHandler
    public void onAdvancementDone(final PlayerAdvancementDoneEvent event) {
        if (!(event.message() instanceof TranslatableComponent message)
                || !message.key().startsWith("chat.type.advancement.")
                || message.arguments().isEmpty()) {
            return;
        }

        final List<ComponentLike> arguments = new ArrayList<>(message.arguments());
        arguments.set(0, event.getPlayer().displayName());
        event.message(message.arguments(arguments));
    }

    @EventHandler
    public void onDeath(final PlayerDeathEvent event) {
        if (!(event.deathMessage() instanceof TranslatableComponent message)
                || !message.key().startsWith("death.")
                || message.arguments().isEmpty()) {
            return;
        }

        final List<ComponentLike> arguments = new ArrayList<>(message.arguments());
        arguments.set(0, event.getPlayer().displayName());

        // Argument 1 is the attacker when the message names one. getKiller() is only ever a player,
        // but kill credit and the named attacker can differ (a pet wolf credits its owner while the
        // message names the wolf), so only swap it when it really is the killer's name. Plain text
        // is used for that check only - never for output.
        final Player killer = event.getPlayer().getKiller();
        if (killer != null && arguments.size() > 1 && PlainTextComponentSerializer.plainText()
                .serialize(arguments.get(1).asComponent()).contains(killer.getName())) {
            arguments.set(1, killer.displayName());
        }

        event.deathMessage(message.arguments(arguments));
    }
}

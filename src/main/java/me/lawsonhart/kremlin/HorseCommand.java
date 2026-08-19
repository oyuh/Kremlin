package me.lawsonhart.kremlin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** /horsie - the best horse the game can roll, in the colourway you ask for. Two people only. */
final class HorseCommand implements CommandExecutor, TabCompleter {

    private static final Set<UUID> ALLOWED = Set.of(
            UUID.fromString("bfb45b17-a02e-4c9b-8b27-98b175ef2eb4"),  // oyuh
            UUID.fromString("aacdd057-f80a-40fa-9e50-28d65df1ea9e")); // ryxoxo

    // The top of every vanilla roll: 30 hearts of health, 0.3375 speed, 1.0 jump (~5.3 blocks).
    private static final double MAX_HEALTH = 30.0;
    private static final double MAX_SPEED = 0.3375;
    private static final double MAX_JUMP = 1.0;

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("The console cannot ride a horse.", NamedTextColor.RED));
            return true;
        }
        if (!ALLOWED.contains(player.getUniqueId())) {
            player.sendMessage(Component.text("The stables are closed to you.", NamedTextColor.RED));
            return true;
        }

        final Horse.Color colour = parse(Horse.Color.class, args.length > 0 ? args[0] : null);
        final Horse.Style style = parse(Horse.Style.class, args.length > 1 ? args[1] : null);
        if (colour == null || style == null) {
            player.sendMessage(Component.text("Usage: /" + label + " <colour> [style]", NamedTextColor.RED));
            return true;
        }

        // The consumer runs before the horse is added to the world, so it never exists un-maxed.
        player.getWorld().spawn(player.getLocation(), Horse.class, horse -> {
            horse.setColor(colour);
            horse.setStyle(style);
            horse.setTamed(true);
            horse.setOwner(player);
            horse.setMaxDomestication(1);
            horse.setDomestication(1);
            horse.getInventory().setSaddle(ItemStack.of(Material.SADDLE));
            horse.getAttribute(Attribute.MAX_HEALTH).setBaseValue(MAX_HEALTH);
            horse.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(MAX_SPEED);
            horse.getAttribute(Attribute.JUMP_STRENGTH).setBaseValue(MAX_JUMP);
            horse.setHealth(MAX_HEALTH);
            horse.customName(Component.text(player.getName() + "'s ridiculous horse", NamedTextColor.GOLD));
        });

        player.sendMessage(Component.text("One maxed-out ", NamedTextColor.GREEN)
                .append(Component.text(name(colour) + " " + name(style), NamedTextColor.GOLD))
                .append(Component.text(" horse, saddled and ready.", NamedTextColor.GREEN)));
        return true;
    }

    @Override
    public List<String> onTabComplete(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (sender instanceof Player player && !ALLOWED.contains(player.getUniqueId())) {
            return List.of();
        }
        return switch (args.length) {
            case 1 -> matching(Horse.Color.class, args[0]);
            case 2 -> matching(Horse.Style.class, args[1]);
            default -> List.of();
        };
    }

    /** Null argument means the default (first constant); an unknown one means null, so we can moan. */
    private static <E extends Enum<E>> E parse(final Class<E> type, final String argument) {
        if (argument == null) {
            return type.getEnumConstants()[0];
        }
        for (final E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(argument)) {
                return constant;
            }
        }
        return null;
    }

    private static <E extends Enum<E>> List<String> matching(final Class<E> type, final String prefix) {
        final List<String> names = new ArrayList<>();
        for (final E constant : type.getEnumConstants()) {
            final String name = name(constant);
            if (name.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                names.add(name);
            }
        }
        return names;
    }

    private static String name(final Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }
}

package me.lawsonhart.kremlin.discord;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link CommandSender} that is really a Discord user.
 *
 * This exists so a Discord command runs the *same* code an in-game one does. Without it the
 * choice is reimplementing every punishment against the Discord API, or dispatching through the
 * console and losing both the reply and any idea of who actually did it. With it, /ban in Discord
 * is literally {@code PunishCommands.onCommand}, the audit trail names the Discord user, and
 * whatever the command would have printed comes back as the reply.
 *
 * <h2>Permissions</h2>
 * {@link #hasPermission} answers true for everything. That is safe only because of where this is
 * built: {@link SlashCommands} constructs one solely after checking the configured staff role,
 * and only ever hands it a fixed list of moderation commands. It must never be handed a command
 * name that came from user input.
 */
public final class DiscordSender implements CommandSender {

    private final Plugin plugin;
    private final String name;
    private final CommandSender console;
    /** Whatever the command printed. Written from the region thread, read from a JDA thread. */
    private final List<Component> replies = new CopyOnWriteArrayList<>();

    public DiscordSender(final Plugin plugin, final String discordName) {
        this.plugin = plugin;
        // Marked so nobody reading punishments.yml has to guess where an action came from.
        this.name = discordName + " (Discord)";
        this.console = Bukkit.getConsoleSender();
    }

    /** Everything the command said, as plain text, ready to go back to Discord. */
    public String reply() {
        final StringBuilder out = new StringBuilder();
        for (final Component line : replies) {
            final String text = PlainTextComponentSerializer.plainText().serialize(line);
            if (text.isBlank()) continue;
            out.append(out.isEmpty() ? "" : "\n").append(text);
        }
        return out.isEmpty() ? "Done." : out.toString();
    }

    // ---------------------------------------------------------------- capture

    /**
     * Every Component overload in CommandSender and Audience is a default that funnels here, so
     * this one override catches what the commands actually send.
     */
    @Override
    public void sendMessage(@NotNull final Component message) {
        replies.add(message);
    }

    @Override
    public void sendMessage(@NotNull final ComponentLike message) {
        replies.add(message.asComponent());
    }

    @Override
    public void sendMessage(@NotNull final String message) {
        replies.add(Component.text(message));
    }

    @Override
    public void sendMessage(@NotNull final String... messages) {
        for (final String message : messages) sendMessage(message);
    }

    @Override
    public void sendMessage(final UUID sender, @NotNull final String message) {
        sendMessage(message);
    }

    @Override
    public void sendMessage(final UUID sender, @NotNull final String... messages) {
        sendMessage(messages);
    }

    // ---------------------------------------------------------------- identity

    @Override
    public @NotNull String getName() {
        return name;
    }

    @Override
    public @NotNull Component name() {
        return Component.text(name);
    }

    @Override
    public @NotNull Server getServer() {
        return plugin.getServer();
    }

    @Override
    public CommandSender.@NotNull Spigot spigot() {
        return console.spigot();
    }

    // ---------------------------------------------------------------- permissions

    @Override
    public boolean hasPermission(@NotNull final String name) {
        return true;
    }

    @Override
    public boolean hasPermission(@NotNull final Permission perm) {
        return true;
    }

    @Override
    public boolean isPermissionSet(@NotNull final String name) {
        return true;
    }

    @Override
    public boolean isPermissionSet(@NotNull final Permission perm) {
        return true;
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public void setOp(final boolean value) {
        throw new UnsupportedOperationException("A Discord sender is not a real account.");
    }

    // Attachments belong to a real Permissible; there is nothing here to attach them to.
    @Override
    public PermissionAttachment addAttachment(@NotNull final Plugin plugin, @NotNull final String name,
                                              final boolean value) {
        return null;
    }

    @Override
    public PermissionAttachment addAttachment(@NotNull final Plugin plugin) {
        return null;
    }

    @Override
    public PermissionAttachment addAttachment(@NotNull final Plugin plugin, @NotNull final String name,
                                              final boolean value, final int ticks) {
        return null;
    }

    @Override
    public PermissionAttachment addAttachment(@NotNull final Plugin plugin, final int ticks) {
        return null;
    }

    @Override
    public void removeAttachment(@NotNull final PermissionAttachment attachment) {
    }

    @Override
    public void recalculatePermissions() {
    }

    @Override
    public @NotNull Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return Set.of();
    }
}

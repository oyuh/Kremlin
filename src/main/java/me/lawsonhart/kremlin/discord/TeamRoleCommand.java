package me.lawsonhart.kremlin.discord;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Perms;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Map;

/**
 * {@code /team admin discord <team> <roleid>}: point a team at a Discord role.
 *
 * <h2>Why this is intercepted rather than registered</h2>
 * {@code /team} belongs to SimpleTeams, exactly like {@code /hq} does, so the only way to add a
 * subcommand under it without editing that plugin is to catch the command before it is
 * dispatched -- the same thing {@code Homes.onTeamHomeCommand} does and for the same reason.
 *
 * The alternative was adding the subcommand to SimpleTeams itself, which would mean a mapping
 * stored by a plugin that has no interest in Discord, a new API method to read it back, and two
 * projects that have to be released together. This keeps it in one codebase, and the mapping
 * where the rest of the Discord config already lives.
 */
public final class TeamRoleCommand implements Listener {

    /** Where the bindings live, under the rest of the Discord config. */
    public static final String PATH = "discord.roles.teams.map";

    private final Combat plugin;
    private final Bot bot;
    private final RoleSync roleSync;

    public TeamRoleCommand(final Combat plugin, final Bot bot, final RoleSync roleSync) {
        this.plugin = plugin;
        this.bot = bot;
        this.roleSync = roleSync;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(final PlayerCommandPreprocessEvent event) {
        // Trimmed once and used for both halves: rootCommand does not trim on its own, so
        // reading the label off the raw message while splitting the trimmed one can disagree.
        final String message = event.getMessage().trim();
        final String[] parts = message.split("\\s+");
        final String root = Combat.rootCommand(message);
        if (!root.equals("team") && !root.equals("t") && !root.equals("teams")) return;
        if (parts.length < 3 || !parts[1].equalsIgnoreCase("admin")
                || !parts[2].equalsIgnoreCase("discord")) {
            return;
        }
        // Ours from here on: SimpleTeams has no idea what this subcommand is and would only
        // answer with its own usage line.
        event.setCancelled(true);
        handle(event.getPlayer(), parts);
    }

    private void handle(final CommandSender sender, final String[] parts) {
        if (!Perms.may(sender, "kremlin.discord.teamrole")) {
            sender.sendMessage(plugin.msg("chat-no-permission"));
            return;
        }
        if (parts.length < 4) {
            sender.sendMessage(plugin.msg("teamrole-usage"));
            return;
        }
        if (parts[3].equalsIgnoreCase("list")) {
            list(sender);
            return;
        }
        if (parts.length < 5) {
            sender.sendMessage(plugin.msg("teamrole-usage"));
            return;
        }

        final String team = parts[3];
        final String key = PATH + "." + team.toLowerCase(Locale.ROOT);
        final String value = parts[4];

        if (value.equalsIgnoreCase("clear") || value.equalsIgnoreCase("none")) {
            if (plugin.getConfig().get(key) == null) {
                sender.sendMessage(plugin.msg("teamrole-not-set", Placeholder.unparsed("team", team)));
                return;
            }
            set(key, null);
            sender.sendMessage(plugin.msg("teamrole-cleared", Placeholder.unparsed("team", team)));
            roleSync.syncAllAsync();
            return;
        }

        if (!value.matches("\\d{5,25}")) {
            sender.sendMessage(plugin.msg("teamrole-bad-id", Placeholder.unparsed("id", value)));
            return;
        }
        // A name that is not a team today is worth saying out loud, but not worth refusing --
        // pre-binding a team that is about to be made is a reasonable thing to do.
        if (!plugin.getTeamHook().teamExists(team)) {
            sender.sendMessage(plugin.msg("teamrole-no-team", Placeholder.unparsed("team", team)));
        }

        final String roleName = roleName(value);
        if (roleName == null && bot.ready()) {
            sender.sendMessage(plugin.msg("teamrole-no-role", Placeholder.unparsed("id", value)));
            return;
        }
        set(key, value);
        sender.sendMessage(plugin.msg("teamrole-set", Placeholder.unparsed("team", team),
                Placeholder.unparsed("role", roleName == null ? value : roleName)));
        roleSync.syncAllAsync();
    }

    private void list(final CommandSender sender) {
        final var section = plugin.getConfig().getConfigurationSection(PATH);
        if (section == null || section.getKeys(false).isEmpty()) {
            sender.sendMessage(plugin.msg("teamrole-none"));
            return;
        }
        sender.sendMessage(plugin.msg("teamrole-list-header"));
        for (final Map.Entry<String, Object> e : section.getValues(false).entrySet()) {
            final String id = String.valueOf(e.getValue());
            final String name = roleName(id);
            sender.sendMessage(plugin.msg("teamrole-list-line",
                    Placeholder.unparsed("team", e.getKey()),
                    Placeholder.unparsed("role", name == null ? id : name)));
        }
    }

    /** The role's name, or null when the bot cannot see a role with that id. */
    private String roleName(final String id) {
        if (!bot.ready()) return null;
        final Guild guild = bot.guild();
        if (guild == null) return null;
        final Role role = guild.getRoleById(id);
        return role == null ? null : role.getName();
    }

    private void set(final String key, final String value) {
        plugin.getConfig().set(key, value);
        plugin.owner().saveConfig();
        // Re-read so the running sync agrees with what was just written.
        roleSync.load();
    }
}

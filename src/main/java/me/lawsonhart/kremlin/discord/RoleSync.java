package me.lawsonhart.kremlin.discord;

import me.lawsonhart.kremlin.combat.Combat;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Discord roles, driven from Minecraft.
 *
 * Minecraft is the source of truth: a rank or a team decides a Discord role, never the other way
 * round. That makes this a reconcile rather than a sync -- work out which of the roles we own
 * this member should have, and add or remove only the difference.
 *
 * <h2>Why this polls</h2>
 * SimpleTeams 2.2.0 publishes exactly one event, {@code TeamHomeTeleportEvent}. There is nothing
 * to listen to for joining, leaving or disbanding a team, so team membership has to be looked at
 * rather than waited for: on a timer, on join, and a moment after somebody runs a /team command
 * that could have changed it.
 *
 * <h2>Roles we own</h2>
 * Every role this touches is one an admin named by id: the verified role, the rank roles, and the
 * team roles bound with {@code /team admin discord}. Nothing is matched by name and nothing is
 * created -- a bot that guesses which roles are its own is a bot that eventually removes
 * somebody's colour role at three in the morning.
 */
public final class RoleSync implements Listener {

    /**
     * The /team subcommands that can change who is on which team. Anything else -- home, info,
     * list, chat -- cannot, so it is not worth a reconcile.
     */
    private static final Set<String> TEAM_CHANGING = Set.of(
            "create", "disband", "join", "leave", "accept", "kick", "invite",
            "promote", "demote", "setleader", "ban", "unban", "alias");

    /** Long enough for SimpleTeams to have finished with the command before we look. */
    private static final long AFTER_COMMAND_TICKS = 40L;

    private final Combat plugin;
    private final Links links;
    private final Bot bot;

    private String verifiedRole = "";
    private Map<String, String> rankRoles = Map.of();
    private boolean teamRoles = true;
    private Map<String, String> teamRoleIds = Map.of();
    private long resyncMinutes = 30L;

    public RoleSync(final Combat plugin, final Links links, final Bot bot) {
        this.plugin = plugin;
        this.links = links;
        this.bot = bot;
    }

    public void load() {
        verifiedRole = plugin.getConfig().getString("discord.roles.verified", "").trim();
        teamRoles = plugin.getConfig().getBoolean("discord.roles.teams.enabled", true);
        teamRoleIds = readIds(TeamRoleCommand.PATH);
        resyncMinutes = Math.max(1L, plugin.getConfig().getLong("discord.resync-minutes", 30));

        rankRoles = readIds("discord.roles.ranks");
    }

    /** A {@code name: roleid} section, lowercased so lookups never hinge on capitalisation. */
    private Map<String, String> readIds(final String path) {
        final Map<String, String> out = new HashMap<>();
        final var section = plugin.getConfig().getConfigurationSection(path);
        if (section == null) return Map.of();
        for (final String key : section.getKeys(false)) {
            if (!section.isString(key)) continue;
            final String id = section.getString(key, "").trim();
            if (!id.isEmpty()) out.put(key.toLowerCase(Locale.ROOT), id);
        }
        return Map.copyOf(out);
    }

    /** A full reconcile, off whatever thread asked for it. */
    public void syncAllAsync() {
        Bukkit.getAsyncScheduler().runNow(plugin.owner(), t -> syncAll());
    }

    /** The repeating reconcile. Async: it is entirely Discord and config, never the world. */
    public void startTimer() {
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin.owner(), t -> syncAll(),
                resyncMinutes, resyncMinutes, TimeUnit.MINUTES);
    }

    // ---------------------------------------------------------------- triggers

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        sync(event.getPlayer().getUniqueId());
    }

    /**
     * The stand-in for the team events SimpleTeams does not have. MONITOR and after a delay,
     * because the command has to have actually run and been saved before its result is readable.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeamCommand(final PlayerCommandPreprocessEvent event) {
        if (!teamRoles || !bot.ready()) return;
        final String root = Combat.rootCommand(event.getMessage());
        if (!root.equals("team") && !root.equals("t") && !root.equals("teams")) return;
        final String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length < 2 || !TEAM_CHANGING.contains(parts[1].toLowerCase(Locale.ROOT))) return;

        // Everyone on the server, not just the player who typed it: /team kick and /team disband
        // change somebody else's roles, and there is no event telling us who.
        Bukkit.getAsyncScheduler().runDelayed(plugin.owner(), t -> syncAll(),
                AFTER_COMMAND_TICKS * 50L, TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- the reconcile

    /** Everyone linked. Safe to call from anywhere; it never touches a live entity. */
    public int syncAll() {
        if (!bot.ready()) return 0;
        int done = 0;
        for (final UUID id : plugin.getUsers().ids()) {
            if (links.isLinked(id) && sync(id)) done++;
        }
        return done;
    }

    /** One player. Returns false when there is nothing to do. */
    public boolean sync(final UUID player) {
        if (!bot.ready() || !links.isLinked(player)) return false;
        final Guild guild = bot.guild();
        if (guild == null) return false;
        final long discordId = links.discordOf(player);
        if (discordId == 0L) return false;

        // The wanted set is worked out here, off Discord's threads and without touching the
        // world; only the add/remove goes to Discord, and only when it differs.
        final Desired desired = wantedRoles(player);
        guild.retrieveMemberById(discordId).queue(
                member -> apply(guild, member, desired),
                error -> {
                    // Left the server, or never joined it. Not an error worth shouting about.
                });
        return true;
    }

    /**
     * Strip every role we own from somebody who has just unlinked.
     *
     * Takes the Discord id rather than the player, because by the time this runs the mapping is
     * already gone -- that is the only handle left on the member.
     */
    public void forget(final long discordId) {
        if (!bot.ready() || discordId == 0L) return;
        final Guild guild = bot.guild();
        if (guild == null) return;
        guild.retrieveMemberById(discordId).queue(
                // Unlinking is decidable for every category, so everything of ours comes off.
                member -> apply(guild, member, new Desired(Set.of(), everything())),
                error -> {
                    // Not in the server any more; nothing to take off.
                });
    }

    /**
     * What a player should hold, and which of our roles this pass is entitled to take away.
     *
     * The two are not the same, and conflating them is destructive. "You should not have the VIP
     * role" and "I could not work out your rank just now" produce an identical empty answer, but
     * only the first one justifies removing it. Anything we could not determine is left exactly
     * as it is until a pass that can determine it.
     */
    private record Desired(Set<String> wanted, Set<String> removable) {}

    private Desired wantedRoles(final UUID player) {
        final Set<String> wanted = new HashSet<>();
        final Set<String> removable = new HashSet<>();

        // Linked is the whole condition, and we know it: always decidable.
        if (!verifiedRole.isEmpty()) {
            wanted.add(verifiedRole);
            removable.add(verifiedRole);
        }

        // Vault only answers for an online player. Offline, the rank is unknown -- not absent --
        // so no rank role is touched. Without this the timer strips the rank role off every
        // linked player who happens to be offline, which is most of them.
        final Player online = plugin.getServer().getPlayer(player);
        if (!rankRoles.isEmpty() && online != null && plugin.getVault().present()) {
            removable.addAll(rankRoles.values());
            final String group = plugin.getVault().group(online);
            final String role = group == null || group.isBlank()
                    ? null : rankRoles.get(group.toLowerCase(Locale.ROOT));
            if (role != null) wanted.add(role);
        }

        // Likewise: teamNameOf returns null both for "no team" and for "SimpleTeams is not
        // answering". Only the first is a reason to take a team role away.
        if (teamRoles && !teamRoleIds.isEmpty() && plugin.getTeamHook().available()) {
            removable.addAll(teamRoleIds.values());
            final String team = plugin.getTeamHook().teamNameOf(player);
            final String role = team == null ? null : teamRoleIds.get(team.toLowerCase(Locale.ROOT));
            if (role != null) wanted.add(role);
        }
        return new Desired(Set.copyOf(wanted), Set.copyOf(removable));
    }

    /**
     * Add what is missing, remove what is ours and no longer earned, and touch nothing else.
     */
    /**
     * Reconcile one player and report what it did, so somebody fixing their own roles can see
     * that something actually happened rather than being told "done" either way.
     */
    public void syncReporting(final UUID player, final java.util.function.Consumer<String> report) {
        if (!bot.ready()) {
            report.accept("off");
            return;
        }
        if (!links.isLinked(player)) {
            report.accept("unlinked");
            return;
        }
        final Guild guild = bot.guild();
        final long discordId = links.discordOf(player);
        if (guild == null || discordId == 0L) {
            report.accept("off");
            return;
        }

        final Desired desired = wantedRoles(player);
        guild.retrieveMemberById(discordId).queue(member -> {
            final int changed = apply(guild, member, desired);
            report.accept(changed == 0 ? "ok" : String.valueOf(changed));
        }, error -> report.accept("notinguild"));
    }

    /** Every role id we own, for the one case where all of them are decidable. */
    private Set<String> everything() {
        final Set<String> all = new HashSet<>(rankRoles.values());
        all.addAll(teamRoleIds.values());
        if (!verifiedRole.isEmpty()) all.add(verifiedRole);
        return all;
    }

    private int apply(final Guild guild, final Member member, final Desired desired) {
        final Set<String> wanted = desired.wanted();
        final List<Role> add = new ArrayList<>();
        final List<Role> remove = new ArrayList<>();

        for (final String id : wanted) {
            final Role role = guild.getRoleById(id);
            if (role == null) {
                plugin.getLogger().warning("Configured Discord role " + id + " does not exist.");
                continue;
            }
            if (!member.getRoles().contains(role) && canManage(guild, role)) add.add(role);
        }
        for (final Role role : member.getRoles()) {
            // removable, not ours: a role we own but could not decide about this pass stays put.
            if (wanted.contains(role.getId()) || !desired.removable().contains(role.getId())
                    || !canManage(guild, role)) {
                continue;
            }
            remove.add(role);
        }
        if (add.isEmpty() && remove.isEmpty()) return 0;
        guild.modifyMemberRoles(member, add, remove).queue(null,
                error -> plugin.getLogger().warning("Could not update roles for "
                        + member.getUser().getName() + ": " + error));
        return add.size() + remove.size();
    }

    /**
     * Only the roles an admin has actually named. Everything else on a member is somebody
     * else's, and is never added or removed.
     */
    private boolean ours(final Role role) {
        final String id = role.getId();
        return id.equals(verifiedRole) || rankRoles.containsValue(id)
                || (teamRoles && teamRoleIds.containsValue(id));
    }

    /**
     * A role above the bot's own highest role cannot be assigned, and asking anyway is a failed
     * request per member per pass rather than one clear warning.
     */
    private boolean canManage(final Guild guild, final Role role) {
        return guild.getSelfMember().canInteract(role)
                && guild.getSelfMember().hasPermission(net.dv8tion.jda.api.Permission.MANAGE_ROLES);
    }

    public String describe() {
        return rankRoles.size() + " rank role(s), " + teamRoleIds.size() + " team role(s)";
    }
}

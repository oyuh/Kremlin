package me.lawsonhart.kremlin.discord;

import me.lawsonhart.kremlin.combat.Combat;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.bukkit.Bukkit;

import java.util.concurrent.TimeUnit;

/**
 * The bot's lifecycle.
 *
 * There is no separate bot process: JDA is loaded by Paper's own library loader (see the
 * {@code libraries:} block in plugin.yml) and runs inside the plugin, so "the bot" and "the
 * plugin" are one thing. Nothing has to be authenticated between them and there is no protocol
 * to keep in step.
 *
 * <h2>Threading</h2>
 * Every JDA callback arrives on a JDA thread, which is not a Folia region thread and not the
 * main thread either. Nothing in a Discord handler may touch a Player, a world or an inventory
 * directly -- it must hop across with {@link #onServerThread}. This is the single rule that
 * matters here, and breaking it produces something that works in testing and throws in
 * production.
 */
public final class Bot {

    private final Combat plugin;
    private final Links links;

    private JDA jda;
    private RoleSync roleSync;
    private boolean enabled;
    private String token = "";
    private String guildId = "";
    private String staffRole = "";

    public Bot(final Combat plugin, final Links links) {
        this.plugin = plugin;
        this.links = links;
    }

    // ---------------------------------------------------------------- lifecycle

    public void start(final RoleSync roleSync) {
        this.roleSync = roleSync;

        enabled = plugin.getConfig().getBoolean("discord.enabled", false);
        token = plugin.getConfig().getString("discord.token", "").trim();
        guildId = plugin.getConfig().getString("discord.guild", "").trim();
        staffRole = plugin.getConfig().getString("discord.staff-role", "").trim();
        links.configure(plugin.getConfig().getLong("discord.link.code-seconds", 300),
                plugin.getConfig().getInt("discord.link.code-length", 6));

        if (!enabled) return;
        if (token.isEmpty()) {
            plugin.getLogger().warning("discord.enabled is true but no token is set -- Discord is off.");
            enabled = false;
            return;
        }

        // Off the region thread: logging in is a network round trip, and Folia will not forgive
        // blocking a region for it.
        Bukkit.getAsyncScheduler().runNow(plugin.owner(), t -> connect());
    }

    private void connect() {
        try {
            // GUILD_MEMBERS is privileged and has to be enabled on the application page. It is
            // what lets roles be read and assigned for someone who is not currently speaking.
            jda = JDABuilder.createLight(token,
                            GatewayIntent.GUILD_MEMBERS)
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .setChunkingFilter(ChunkingFilter.ALL)
                    .disableCache(CacheFlag.VOICE_STATE, CacheFlag.EMOJI, CacheFlag.STICKER,
                            CacheFlag.SCHEDULED_EVENTS, CacheFlag.ACTIVITY)
                    .addEventListeners(new SlashCommands(plugin, links, this, roleSync))
                    .build();
            jda.awaitReady();
            registerSlashCommands();
            plugin.getLogger().info("Discord bot connected as " + jda.getSelfUser().getAsTag() + ".");
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (final Throwable t) {
            jda = null;
            enabled = false;
            plugin.getLogger().severe("Could not connect to Discord (" + t + ") -- Discord features are off.");
        }
    }

    /**
     * Registered against the one guild rather than globally: guild commands appear immediately,
     * where global ones take up to an hour to propagate.
     */
    private void registerSlashCommands() {
        final Guild guild = guild();
        if (guild == null) {
            plugin.getLogger().warning("discord.guild is not a server this bot is in -- slash commands are off.");
            return;
        }
        guild.updateCommands().addCommands(SlashCommands.definitions()).queue(
                ok -> plugin.getLogger().info("Registered " + ok.size() + " Discord command(s)."),
                error -> plugin.getLogger().warning("Could not register Discord commands: " + error));
    }

    public void stop() {
        if (jda == null) return;
        final JDA closing = jda;
        jda = null;
        // shutdownNow rather than shutdown: a clean shutdown waits for queued requests, and this
        // runs while the server is trying to stop.
        closing.shutdownNow();
        try {
            closing.awaitShutdown(5, TimeUnit.SECONDS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- access

    public boolean ready() {
        return enabled && jda != null;
    }

    public JDA jda() {
        return jda;
    }

    public Guild guild() {
        if (jda == null || guildId.isEmpty()) return null;
        try {
            return jda.getGuildById(guildId);
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    public RoleSync roleSync() {
        return roleSync;
    }

    public String staffRole() {
        return staffRole;
    }

    /**
     * Run something that touches the server, from a Discord thread.
     *
     * The global region scheduler is the right one for anything not tied to a single player;
     * work aimed at one player should use that player's own scheduler instead.
     */
    public void onServerThread(final Runnable work) {
        Bukkit.getGlobalRegionScheduler().execute(plugin.owner(), work);
    }

    public String describe() {
        if (!enabled) return "discord off";
        return jda == null ? "discord connecting" : "discord on";
    }
}

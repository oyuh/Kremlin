package me.lawsonhart.kremlin;

import me.lawsonhart.kremlin.chat.ChatAdmin;
import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.info.InfoCommands;
import me.lawsonhart.kremlin.misc.DisplayNameMessages;
import me.lawsonhart.kremlin.misc.DragonDamage;
import me.lawsonhart.kremlin.misc.HorseCommand;
import me.lawsonhart.kremlin.misc.UnlimitedTrades;
import me.lawsonhart.kremlin.player.NickGui;

import java.util.Objects;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

public final class Kremlin extends JavaPlugin {

    private Combat combat;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(new DisplayNameMessages(), this);
        getServer().getPluginManager().registerEvents(new UnlimitedTrades(), this);

        final HorseCommand horse = new HorseCommand();
        final PluginCommand horsie = command("horsie");
        horsie.setExecutor(horse);
        horsie.setTabCompleter(horse);

        // Combat wires its own listeners, config and sub-features (homes, tpa, action bar).
        this.combat = new Combat(this);
        this.combat.enable();
        command("kremlin").setExecutor(this.combat);
        command("playerlist").setExecutor(this.combat.getPlayers());

        // Every label these three own, now that Bukkit dispatches them rather than a
        // PlayerCommandPreprocessEvent hook cancelling whatever Essentials would have run.
        handle(this.combat.getTpa(), "tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel");
        handle(this.combat.getHomes(), "home", "sethome", "delhome", "renamehome", "homes",
                "adminhome", "comhub", "setcomhub", "delcomhub");
        handle(this.combat.getInvSee(), "invsee", "enderchest");
        handle(this.combat.getMsg(), "msg", "reply", "msgtoggle");
        handle(this.combat.getIgnore(), "ignore");
        command("broadcast").setExecutor(this.combat.getBroadcast());
        handle(this.combat.getNicknames(), "nick");
        handle(this.combat.getLookup(), "seen", "whois", "list");
        handle(this.combat.getPlayerCommands(), "heal", "feed", "kill", "fly", "speed",
                "sudo", "weather", "gamemode", "gms", "gmc", "gma", "gmsp");
        handle(this.combat.getItemCommands(), "repair", "more", "give", "skull", "itemname",
                "itemlore", "clearinventory", "trash");
        handle(this.combat.getEnchant(), "enchant");
        handle(this.combat.getPowertool(), "powertool", "powertooltoggle");
        handle(this.combat.getWarps(), "warp", "warps", "setwarp", "delwarp");
        handle(this.combat.getTpCommands(), "tp", "tphere", "tpo", "tpohere", "tpall",
                "tpaall", "tppos", "tptoggle", "back", "top");
        command("options").setExecutor(this.combat.getOptions());
        handle(this.combat.getPunishCommands(), "ban", "tempban", "unban", "banip", "unbanip",
                "mute", "unmute", "kick", "kickall", "warn", "alts", "history");
        handle(this.combat.getPunishGui(), "punish");
        // Every info command is the same executor under a different label.
        for (final String name : InfoCommands.LABELS) {
            command(name).setExecutor(this.combat.getInfo());
        }
        // /discord is an info command until a name follows it, so the link handler takes it
        // back and falls through to the info text when there is no argument.
        handle(this.combat.getLinkCommands(), "link", "unlink", "discord", "resync");

        // The gradient builder needs the nickname store, which Combat owns, so it is wired
        // after it rather than first.
        final NickGui nickGui = new NickGui(this, this.combat.getNicknames());
        getServer().getPluginManager().registerEvents(nickGui, this);
        command("nickg").setExecutor(nickGui);

        final DragonDamage dragon = new DragonDamage();
        getServer().getPluginManager().registerEvents(dragon, this);
        command("dragondamage").setExecutor(dragon);

        final ChatAdmin chat = new ChatAdmin(this.combat);
        getServer().getPluginManager().registerEvents(chat, this);
        command("clearchat").setExecutor(chat);
        final PluginCommand slowchat = command("slowchat");
        slowchat.setExecutor(chat);
        slowchat.setTabCompleter(chat);
    }

    @Override
    public void onDisable() {
        if (this.combat != null) this.combat.disable();
    }

    private PluginCommand command(final String name) {
        return Objects.requireNonNull(getCommand(name), name + " missing from plugin.yml");
    }

    /** One object serving several labels: it branches on the label it was handed. */
    private <T extends CommandExecutor & TabCompleter> void handle(final T handler, final String... names) {
        for (final String name : names) {
            final PluginCommand cmd = command(name);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
    }
}

package me.lawsonhart.kremlin;

import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class Kremlin extends JavaPlugin {

    private Combat combat;

    @Override
    public void onEnable() {
        final NickGui nickGui = new NickGui(this);
        getServer().getPluginManager().registerEvents(new DisplayNameMessages(), this);
        getServer().getPluginManager().registerEvents(new UnlimitedTrades(), this);
        getServer().getPluginManager().registerEvents(nickGui, this);
        command("nickg").setExecutor(nickGui);

        final HorseCommand horse = new HorseCommand();
        final PluginCommand horsie = command("horsie");
        horsie.setExecutor(horse);
        horsie.setTabCompleter(horse);

        // Combat wires its own listeners, config and sub-features (homes, tpa, action bar).
        this.combat = new Combat(this);
        this.combat.enable();
        command("kremlin").setExecutor(this.combat);
        command("playerlist").setExecutor(this.combat.getPlayers());

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
}

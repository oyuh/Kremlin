package me.lawsonhart.kremlin.core;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * The group, prefix and suffix a permissions plugin publishes, read through Vault.
 *
 * Reflective, for the same reason {@code TeamHook} is: it keeps Vault off the compile classpath
 * entirely. That is worth more than usual here, because "Vault" is not one plugin any more --
 * stock Vault is not Folia-flagged and servers run VaultUnlocked instead. Looking the service up
 * by class *name* through the services manager works with either, and with neither.
 *
 * Resolved on first use rather than at enable: LuckPerms registers its Vault services when it
 * enables, which may be after we do.
 */
public final class VaultHook {

    private static final String CHAT_SERVICE = "net.milkbowl.vault.chat.Chat";

    private final Logger log;
    private boolean looked;
    private Object chat;
    private Method primaryGroup;
    private Method prefix;
    private Method suffix;
    private boolean warned;

    public VaultHook(final Logger log) {
        this.log = log;
    }

    private synchronized void find() {
        if (looked) return;
        looked = true;
        final ServicesManager services = Bukkit.getServicesManager();
        for (final Class<?> service : services.getKnownServices()) {
            if (!CHAT_SERVICE.equals(service.getName())) continue;
            final RegisteredServiceProvider<?> registration = services.getRegistration(service);
            if (registration == null) continue;
            try {
                chat = registration.getProvider();
                primaryGroup = service.getMethod("getPrimaryGroup", Player.class);
                prefix = service.getMethod("getPlayerPrefix", Player.class);
                suffix = service.getMethod("getPlayerSuffix", Player.class);
                log.info("Reading chat groups and prefixes from Vault (" + registration.getPlugin().getName() + ").");
            } catch (final Throwable t) {
                chat = null;
                log.warning("Vault is present but its Chat service did not match (" + t + ").");
                log.warning("Group chat formats and {PREFIX}/{SUFFIX} will be empty.");
            }
            return;
        }
        log.info("No Vault chat service -- group formats and {PREFIX}/{SUFFIX} will be empty.");
    }

    /** True once a provider has been found, so callers can skip group lookups entirely. */
    public boolean present() {
        find();
        return chat != null;
    }

    /** Never null: an absent provider, or one that throws, reads as "". */
    private String call(final Method method, final Player player) {
        find();
        if (chat == null) return "";
        try {
            final Object value = method.invoke(chat, player);
            return value == null ? "" : value.toString();
        } catch (final Throwable t) {
            if (!warned) {
                warned = true;
                log.warning("Vault lookup failed, treating group and affixes as empty: " + t);
            }
            return "";
        }
    }

    public String group(final Player player) {
        return call(primaryGroup, player);
    }

    public String prefix(final Player player) {
        return call(prefix, player);
    }

    public String suffix(final Player player) {
        return call(suffix, player);
    }
}

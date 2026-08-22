package me.lawsonhart.kremlin.player;

import me.lawsonhart.kremlin.combat.Combat;
import me.lawsonhart.kremlin.core.Durations;
import me.lawsonhart.kremlin.core.TeamHook;
import me.lawsonhart.kremlin.teleport.Tpa;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Who's who: the /playerlist menu, and telling someone whose head they just right-clicked.
 *
 * Both answer the same question -- which player is this, and what are they called now -- off the
 * same nickname lookup, which is why they share a class.
 *
 * Nothing here owns any of that data. The roster is whoever the server has player data for, the
 * nickname is {@link Nicknames}', and the team is SimpleTeams' via {@link TeamHook}. Any of the
 * three being absent costs a lore line, not the command.
 *
 * The roster is read on the async scheduler, because it is disk, and only the page being drawn is
 * read -- forty-five entries per open, not one per player who has ever joined. The menu itself is
 * built back on the viewer's own region.
 */
public final class PlayerList implements Listener, CommandExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /**
     * Team prefixes come from SimpleTeams as legacy colour codes, hex in the section-x form, one
     * code per digit. They are deserialised with this and appended as a component -- nothing from
     * another plugin is ever concatenated into a MiniMessage string. Nicknames used to arrive the
     * same way from EssentialsX; they are ours and MiniMessage now.
     */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final int SIZE = 54;
    private static final int PER_PAGE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 53;

    private final Combat plugin;
    private final TeamHook teams;
    private final Nicknames nicknames;

    public PlayerList(Combat plugin, TeamHook teams, Nicknames nicknames) {
        this.nicknames = nicknames;
        this.plugin = plugin;
        this.teams = teams;
    }

    /** One row of the menu: everything gathered off-thread, ready to render. */
    private record Row(UUID id, String name, String nick, boolean online, long lastSeen,
                       PlayerProfile skin, String discord, long played) {}

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Players only -- this one is a menu."));
            return true;
        }
        if (!p.hasPermission("kremlin.playerlist")) {
            p.sendMessage(plugin.msg("playerlist-no-permission"));
            return true;
        }
        open(p, 0);
        return true;
    }

    private void open(Player viewer, int page) {
        Bukkit.getAsyncScheduler().runNow(plugin.owner(), t -> {
            List<OfflinePlayer> all = new ArrayList<>(List.of(Bukkit.getOfflinePlayers()));
            all.removeIf(o -> o.getName() == null);
            all.sort(Comparator.comparing(OfflinePlayer::getName, String.CASE_INSENSITIVE_ORDER));

            int pages = Tpa.pageCount(all.size(), PER_PAGE);
            int shown = Tpa.wrapPage(page, pages);
            List<Row> rows = new ArrayList<>();
            for (int i = shown * PER_PAGE; i < Math.min(all.size(), (shown + 1) * PER_PAGE); i++) {
                rows.add(row(all.get(i)));
            }
            int total = all.size();
            viewer.getScheduler().run(plugin.owner(),
                    t2 -> draw(viewer, rows, shown, pages, total), null);
        });
    }

    private Row row(OfflinePlayer o) {
        Player online = o.getPlayer();
        String nick = storedNick(o.getUniqueId());
        long lastSeen = 0L;
        try {
            lastSeen = o.getLastSeen();
        } catch (Throwable ignored) {
            // Player data we can't read is a blank column, not a failed command.
        }
        // Statistics are a disk read for an offline player, which is why rows are built on the
        // async scheduler rather than while the menu is being drawn.
        return new Row(o.getUniqueId(), o.getName(), nick, online != null, lastSeen,
                skin(o, online), discordOf(o.getUniqueId()), Lookup.playedMillis(o));
    }

    /**
     * The head texture, or null to leave a default head.
     *
     * Strictly what is already cached. setOwningPlayer() on a player whose textures aren't cached
     * sends Paper off to Mojang for them, and a page is forty-five of those at once -- which the
     * session API answers with 429 and a stack trace per head. A blank head on someone the server
     * has never had a skin for is a much better trade than rate-limiting the whole server.
     */
    private static PlayerProfile skin(OfflinePlayer o, Player online) {
        try {
            PlayerProfile profile = online != null ? online.getPlayerProfile() : o.getPlayerProfile();
            if (!profile.hasTextures()) profile.completeFromCache();
            return profile.hasTextures() ? profile : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Their Discord name for the lore, or null when they have not linked.
     *
     * Read from JDA's cache rather than fetched: this runs once per row while a page is being
     * built, and forty-five blocking lookups to answer one menu is not a trade worth making. An
     * id we cannot put a name to is shown as the id, which is still enough to find them.
     */
    private String discordOf(UUID id) {
        long discord = plugin.getLinks().discordOf(id);
        if (discord == 0L) return null;
        var jda = plugin.getBot().ready() ? plugin.getBot().jda() : null;
        if (jda == null) return String.valueOf(discord);
        var user = jda.getUserById(discord);
        return user == null ? String.valueOf(discord) : user.getName();
    }

    /** Ours now, out of users.yml, and readable whether or not the player is on. */
    private String storedNick(UUID id) {
        return nicknames.rawOf(id);
    }

    // ---------------------------------------------------------------- placed heads

    /** Right-clicking the same head over and over shouldn't fill anyone's chat. */
    private static final long HEAD_COOLDOWN_MILLIS = 1_500L;

    private final Map<UUID, Long> lastHead = new ConcurrentHashMap<>();

    /**
     * Right-click a placed player head and be told whose it is. Never cancels the interaction --
     * placing a block against a head, or opening whatever is behind it, still works.
     */
    @EventHandler(ignoreCancelled = true)
    public void onHeadClick(PlayerInteractEvent e) {
        // The event fires once per hand; without this every click would say it twice.
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("kremlin.headinfo")) return;
        Long last = lastHead.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < HEAD_COOLDOWN_MILLIS) return;
        lastHead.put(p.getUniqueId(), now);

        if (!(block.getState() instanceof Skull skull) || !skull.hasOwner()) return;
        PlayerProfile profile = skull.getPlayerProfile();
        if (profile == null) return;
        describeHead(p, profile.getId(), profile.getName());
    }

    /** Off-thread: an offline owner's nickname is a file read, and this runs on a block click. */
    private void describeHead(Player viewer, UUID id, String profileName) {
        Bukkit.getAsyncScheduler().runNow(plugin.owner(), t -> {
            Player owner = id == null ? null : plugin.getServer().getPlayer(id);
            String real = owner != null ? owner.getName() : profileName;
            if (real == null && id != null) real = Bukkit.getOfflinePlayer(id).getName();
            if (real == null) return; // a head with no name we can put to it

            // An online owner's display name is their nickname already, gradient and all.
            Component shown = owner != null ? owner.displayName() : offlineNick(id);
            String plain = shown == null
                    ? null : PlainTextComponentSerializer.plainText().serialize(shown).trim();
            boolean nicked = plain != null && !plain.isEmpty() && !plain.equalsIgnoreCase(real);

            viewer.sendMessage(nicked
                    ? plugin.msg("head-owner-nick", Placeholder.component("player", shown),
                            Placeholder.unparsed("name", real))
                    : plugin.msg("head-owner", Placeholder.unparsed("name", real)));
        });
    }

    /** Per-player state, dropped with the player, like every other map in this plugin. */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastHead.remove(e.getPlayer().getUniqueId());
    }

    private Component offlineNick(UUID id) {
        return id == null ? null : nicknames.nickOf(id);
    }

    // ---------------------------------------------------------------- gui

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        int page;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private void draw(Player viewer, List<Row> rows, int page, int pages, int total) {
        Menu menu = new Menu();
        menu.page = page;
        Inventory inv = plugin.getServer().createInventory(menu, SIZE, plugin.msg("playerlist-title"));
        menu.inv = inv;

        for (int i = 0; i < rows.size(); i++) {
            inv.setItem(i, head(rows.get(i)));
        }
        if (pages > 1) {
            inv.setItem(SLOT_PREV, item(Material.ARROW, "<white>Previous page", List.of()));
            inv.setItem(SLOT_NEXT, item(Material.ARROW, "<white>Next page", List.of()));
        }
        inv.setItem(SLOT_INFO, item(Material.PAPER, "<gold>Page " + (page + 1) + " / " + pages, List.of(
                "<gray>Known players: <white>" + total,
                "<gray>Online now: <white>" + plugin.getServer().getOnlinePlayers().size())));

        viewer.openInventory(inv);
    }

    private ItemStack head(Row r) {
        String team = teams.teamNameOf(r.id());
        List<Component> lore = new ArrayList<>();
        lore.add(mm("<gray>Name: <white>").append(Component.text(r.name())));
        // Nicknames are ours now, and stored as MiniMessage -- a gradient survives to the lore
        // instead of being flattened, which is what the legacy form did to it. Team prefixes are
        // still somebody else's data, and still legacy.
        lore.add(r.nick() == null
                ? mm("<gray>Nick: <dark_gray>none")
                : mm("<gray>Nick: ").append(nicknames.nickOf(r.id())));
        lore.add(team == null
                ? mm("<gray>Team: <dark_gray>none")
                : mm("<gray>Team: ").append(legacy(team)));
        lore.add(r.discord() == null
                ? mm("<gray>Discord: <dark_gray>not linked")
                : mm("<gray>Discord: <white>").append(Component.text(r.discord())));
        lore.add(r.played() <= 0
                ? mm("<gray>Playtime: <dark_gray>none")
                : mm("<gray>Playtime: <white>" + Durations.describe(r.played())));
        lore.add(Component.empty());
        lore.add(r.online()
                ? mm("<green>Online now")
                : mm("<dark_gray>Last seen <gray>" + ago(r.lastSeen())));

        ItemStack stack = ItemStack.of(Material.PLAYER_HEAD);
        if (r.skin() != null) stack.editMeta(SkullMeta.class, meta -> meta.setPlayerProfile(r.skin()));
        return decorate(stack, mm(r.online() ? "<green>" : "<gray>").append(Component.text(r.name())), lore);
    }

    /** Legacy colour codes in either the ampersand or the section form, rendered as written. */
    static Component legacy(String s) {
        return LEGACY.deserialize(s.replace('&', LegacyComponentSerializer.SECTION_CHAR))
                .decoration(TextDecoration.ITALIC, false);
    }

    public static String ago(long epochMillis) {
        if (epochMillis <= 0L) return "never";
        long millis = Math.max(0L, System.currentTimeMillis() - epochMillis);
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        if (days > 0) return days + "d ago";
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        if (hours > 0) return hours + "h ago";
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        return minutes > 0 ? minutes + "m ago" : "just now";
    }

    /** Our own lore text, which is MiniMessage because we wrote it. */
    private static Component mm(String s) {
        return MM.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        return decorate(ItemStack.of(material), mm(name), lore.stream().map(PlayerList::mm).toList());
    }

    private static ItemStack decorate(ItemStack stack, Component name, List<Component> lore) {
        stack.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
        });
        return stack;
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getRawSlot() == SLOT_PREV) open(p, menu.page - 1);
        else if (e.getRawSlot() == SLOT_NEXT) open(p, menu.page + 1);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}

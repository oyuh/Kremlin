# Kremlin: replacing EssentialsX + EssentialsChat

Goal: Kremlin is the only general-purpose plugin on the server. EssentialsX and
EssentialsChat are uninstalled at the end of this, with no loss of behaviour and no
LuckPerms re-configuration.

---

## 0. Decisions

Three of these actually branch the work. Recommended answer given; say so if you disagree
before Phase 1 starts.

| # | Decision | Recommendation | Why |
|---|---|---|---|
| D1 | Command registration | **Real `plugin.yml` commands + `CommandExecutor`/`TabCompleter`.** Delete the `PlayerCommandPreprocessEvent` interception in `Tpa`/`Homes` and delete `TabComplete.java` entirely. | Both exist *only* because Essentials owned the labels. With Essentials gone Kremlin wins every label, and normal registration gives tab-completion for free. Net deletion of ~200 lines. |
| D2 | Ban storage | **Paper's built-in `BanList`** for ban / tempban / ipban. Our own `punishments.yml` only for mutes + history. | `BanList` already does expiry, reason, source, the kick screen and the login gate. Reimplementing it is pure cost. |
| D3 | Messages location | Move all text to **`messages.yml`**, one-time migration lifting the existing `messages:` block out of `config.yml`. | `config.yml` is 211 lines today and this project roughly quadruples the message count. Same `migrate()` precedent already in `Combat.java:197`. |
| D4 | Vault | Look up `net.milkbowl.vault.chat.Chat` / `Permission` through the services manager, `compileOnly` on VaultAPI. | What was asked for. **Verify at implementation time:** stock Vault is not Folia-flagged — the server may need VaultUnlocked, or we fall back to the LuckPerms API directly. Isolate this behind one `VaultHook` class so the fallback is a one-file change. |
| D5 | Per-player data | One `users.yml` keyed by UUID. | Small server. Mark with a `ponytail:` comment naming the ceiling (split per-UUID or SQLite past a few thousand players). |

### Known conflict

`/playerlist`, `/players`, `/plist` are already Kremlin's **GUI**. Essentials' `/list`
uses those as aliases. Resolution: plain `/list`, `/who`, `/online` for the new text
list; `/playerlist` keeps the GUI.

---

## 1. Target layout

Split by **feature**, not by technical layer. A layer split (`commands/`, `listeners/`,
`handlers/` across the whole plugin) means opening four files to add one command, and
every feature's logic ends up smeared across four packages. Inside a feature, a
`Commands`/`Listener` split happens only when that feature genuinely has both and they
are big.

```
me.lawsonhart.kremlin
├─ Kremlin.java                bootstrap + module registration, nothing else
├─ core/
│   ├─ Messages.java           msg() lifted out of Combat, layered defaults, messages.yml
│   ├─ Settings.java           config.yml accessors + reload
│   ├─ Perms.java              the may(node...) helper currently duplicated in 4 files
│   ├─ Users.java              users.yml store: nick, toggles, ignores, powertools, last-ip, back
│   ├─ Cmd.java                small base: sender/target resolution, self-vs-others perms, usage
│   ├─ Args.java               duration parsing ("2d4h"), item parsing, player resolution
│   ├─ Items.java              (moved)
│   └─ Palette.java            (moved)
├─ combat/                     Combat, BarMenu
├─ chat/                       ChatFormat, ChatAdmin, Broadcast, JoinQuit, Msg, Ignore
├─ teleport/                   Tpa, TpCommands, Warps, Back, Top, Homes, TeamHook
├─ punish/                     Punish, PunishGui, Mutes, Alts, History
├─ player/                     Nick, NickGui, NickMenu, ColourPicker, Whois, Seen,
│                              PlayerList, ListCommand, InvSee, Heal, Kill, Fly,
│                              Gamemode, Speed, Sudo
├─ item/                       Repair, Enchant, ItemMeta (name/lore), More, Give, Skull,
│                              Powertool, ClearInventory, Trash
├─ world/                      Weather
├─ options/                    OptionsGui
├─ info/                       InfoCommands (help, rules, …)
└─ misc/                       HorseCommand, DragonDamage, UnlimitedTrades, DisplayNameMessages
```

`Combat.java` is 773 lines and currently doubles as the plugin's service locator
(`getConfig()`, `msg()`, `getLogger()`, `owner()`, `getHomes()`, …). Phase 1 strips that
role out into `core/` so new features do not all have to take a `Combat` reference.

---

## 2. Phases

Each phase ends green: `./gradlew build` passes and the named check holds.

### Phase 0 — Restructure only, zero behaviour change

1. Create the packages above, move existing files in, fix imports and visibility
   (package-private members crossing a package boundary become `public` only where
   genuinely needed).
2. Keep every class name and every method body byte-identical. No refactoring here.
3. Move test classes to matching packages.

**Check:** `./gradlew build nickCheck` passes, `git diff --stat` shows renames and import
lines only.

### Phase 1 — Core extraction and real command registration

1. `core/Messages` — `msg()` and the bundled-defaults layering move out of `Combat`.
   Add `messages.yml`, and a migration that copies `config.yml`'s `messages:` block into
   it on first start (same shape as `Combat.migrate`). `Combat` delegates.
2. `core/Perms` — one `may(sender, String... nodes)`, replacing the four private copies
   in `Combat`, `Homes`, `InvSee`, `TabComplete`.
3. `core/Users` — `users.yml`, async save on the pattern of `Homes.save()`
   (`Bukkit.getAsyncScheduler().runNow`), `saveNow()` on disable.
4. `core/Cmd` — base class giving every command: arg count/usage handling,
   console-vs-player, `target(args, index)` resolving online → offline → nickname, and
   the self/others permission split (`kremlin.x` vs `kremlin.x.others`, with
   `essentials.x` honoured alongside).
5. **D1**: delete the `PlayerCommandPreprocessEvent` handlers in `Tpa` and `Homes`,
   delete `TabComplete.java`, register those commands properly in `plugin.yml` with real
   executors and completers. `Combat`'s own preprocess handler stays — that one is the
   combat block, a different job.

**Check:** existing tests still pass; `/tpa`, `/home`, `/invsee` and their tab-completion
behave the same with Essentials **disabled**.

### Phase 2 — Chat (highest risk, do it early)

This is the one that has to be right on day one, because the existing EssentialsChat
config must keep working verbatim.

1. `chat/VaultHook` — services-manager lookup of `Chat` and `Permission`; `primaryGroup`,
   `prefix`, `suffix`, all null-safe when Vault is absent.
2. `chat/ChatFormat` — an `AsyncChatEvent` listener at `NORMAL` that sets a
   `ChatRenderer` (Paper's modern path — the same hook EssentialsX uses, so other
   plugins can still modify around us). Do **not** rewrite `event.message()`.
   - Supports EssentialsX's placeholder names: `{DISPLAYNAME}`, `{USERNAME}`,
     `{NICKNAME}`, `{GROUP}`, `{PREFIX}`, `{SUFFIX}`, `{MESSAGE}`, `{WORLDNAME}`,
     `{SHORTWORLDNAME}`, `{TEAMPREFIX}`, `{TEAMNAME}`.
   - Accepts legacy `&`/`§` codes **and** MiniMessage in the format string.
   - `chat.format` default plus `chat.group-formats.<group>` overrides, resolved through
     Vault's primary group.
   - Colour permissions preserved: `essentials.chat.color`, `.format`, `.magic`, plus
     `kremlin.chat.*` twins. Message body is otherwise escaped so players cannot inject
     tags.
3. One-time import: read `plugins/EssentialsChat/config.yml` and Essentials' `chat:`
   section on first start and write the formats into Kremlin's config, so nothing is
   retyped.
4. `chat/JoinQuit` — configurable join/leave/first-join messages via
   `PlayerJoinEvent#joinMessage(Component)` / `PlayerQuitEvent#quitMessage(Component)`,
   silent-join permission.
5. `chat/Broadcast` — `/broadcast`.
6. `chat/Msg` + `chat/Ignore` — `/msg`, `/reply`, `/msgtoggle`, `/ignore`. Ignore list
   and msg-toggle live in `core/Users`; ignore also suppresses public chat from the
   ignored player.

**Check:** a test asserting format resolution — group format beats default, every
placeholder substitutes, legacy codes survive, a player-supplied `<red>` in the body does
**not**. Then in-game: message renders identically to EssentialsChat with the same config.

### Phase 3 — Player and admin commands (the easy batch)

`player/`: `/heal`, `/kill`, `/fly`, `/gamemode` + `/gms /gmc /gma /gmsp`, `/speed`,
`/sudo`, `/seen`, `/whois`, `/list`, `/nick`.
`world/`: `/weather`.

Notes:
- `/seen` uses `OfflinePlayer#getLastSeen()` / `getLastLogin()` — no storage needed for
  the basic form. Only the IP shown under `kremlin.seen.extra` comes from `users.yml`.
- **`/nick` is the Essentials cut-line.** Implement it natively, store the nick in
  `users.yml`, apply via `Player#displayName()` + `playerListName()`. Then:
  - `NickGui.java:94` stops dispatching `/nick` to Essentials and sets it directly.
  - `PlayerList.java:165` stops reading `Essentials/userdata/` and reads `users.yml`.
  - Add a one-time import of nicks out of Essentials userdata.
  - `Nick#essentialsNick()` and its `NickCheck` assertions can go once the handoff is
    gone — keep the serializer only if you still want legacy-format output anywhere.
- Every command here follows the self/others permission split.

**Check:** `NickCheck` updated and passing; `/nick`, `/seen`, `/whois` correct for an
offline player.

### Phase 4 — Items

`item/`: `/repair [hand|all]`, `/enchant <name> [level]` (unsafe levels behind
`kremlin.enchantments.allowunsafe`, using `addUnsafeEnchantment`), `/itemname`,
`/itemlore <add|set|clear|remove>`, `/more`, `/give`, `/skull [player]`,
`/powertool` + `/powertooltoggle`, `/clearinventory`, `/trash`.

Notes:
- Powertool bindings are `Material -> command` in `users.yml`, fired from
  `PlayerInteractEvent`.
- `/trash` is `createInventory(null, 36)` with nothing persisted — contents vanish on
  close, which is the whole feature.

**Check:** a test for the item/enchantment name parsing and the unsafe-level gate.

### Phase 5 — Teleport suite, warps, and the options GUI

1. `teleport/TpCommands` — `/tp`, `/tphere`, `/tpo`, `/tpohere`, `/tpall`, `/tpaall`,
   `/tppos`, `/tptoggle`. **Every one routes through `Combat.startWarmup` /
   `Combat.denyTeleport`** — that is the existing single entry point and nothing may
   teleport around it.
2. `teleport/Back` — `/back`, last location stored on death and on teleport in
   `users.yml`.
3. `teleport/Top` — `/top` via `World#getHighestBlockYAt`.
4. `teleport/Warps` — `warps.yml`, `/warp [name] [player]`, `/setwarp`, `/delwarp`,
   `/warps`. Optional per-warp permission `kremlin.warp.<name>` behind a config flag,
   matching Essentials. Import `plugins/Essentials/warps/` on first start.
5. `options/OptionsGui` — `/options`. Toggles read/write `core/Users`:
   - combat action-bar style (**absorbs `BarMenu`**, `/kremlin bar` becomes an alias)
   - `/msgtoggle`
   - `/tptoggle`
   - ignore-list view

   Reuse `BarMenu`'s marker-`InventoryHolder` pattern so clicks are identified by
   identity, not title text.
6. Update `config.yml`'s `blocked-commands` for the new labels (`warp`, `back`, `top`,
   `tptoggle` are already listed — add `tpaall`, `tppos`, `trash`, `disposal`).

**Check:** teleporting while combat-tagged is refused for every new command; `/options`
toggles survive a restart.

### Phase 6 — Punishments

`punish/`:
- `/ban <player> [reason]`, `/tempban <player> <duration> [reason]`, `/unban`,
  `/banip <player|ip> [reason]`, `/unbanip` — all on Paper's `BanList` (**D2**).
- `/mute <player> [duration] [reason]`, `/unmute` — our own store, enforced in
  `AsyncChatEvent` and against a configurable command list (so a muted player cannot
  `/msg` around it).
- `/kick <player> [reason]`, `/kickall`.
- `/warn <player> <reason>` — history entry plus a message to the player.
- `/punish <player>` — GUI listing the configured punishment ladder from `config.yml`,
  one click per rung.
- `/alts <player>` — IP-history match out of `users.yml` (recorded on
  `AsyncPlayerPreLoginEvent`).
- `/history <player>` (alias `/punishments`) — the audit trail.
- `punishments.yml`: mutes + full history. Import Essentials' `banned-players.json` /
  userdata mutes on first start.
- Exempt permission `kremlin.punish.exempt` respected by every rung.

**Check:** tests for duration parsing and mute-expiry arithmetic (pure logic, no Bukkit);
in-game, a tempban's kick screen shows the reason and the remaining time.

### Phase 7 — Info commands and Essentials removal

1. `info/InfoCommands` — a fixed set declared in `plugin.yml` (`help`, `rules`, `discord`,
   `website`, `vote`, `store`), each rendering a configurable list of MiniMessage lines
   from `messages.yml`. One class, one config map. Not a plugin framework.
2. Removal checklist:
   - Drop `Essentials`/`EssentialsX` from `plugin.yml` `softdepend`.
   - Confirm every Essentials importer has run, then leave the import code in place
     (it is a no-op once the data exists — same as the CombatPrev migration).
   - Grep for `essentials` across `src/` — only permission-node aliases should remain.
   - Uninstall EssentialsX + EssentialsChat, restart, walk the command table below.

---

## 3. Command table

Permissions: `kremlin.<node>` throughout, with `essentials.<node>` accepted alongside via
`core/Perms` so no LuckPerms group is retyped. Where a command can act on someone else,
that is a **separate** `.others` node.

| Command | Aliases | Args | Perms |
|---|---|---|---|
| `/repair` | `fix` | `[hand\|all]` | `repair`, `repair.all`, `repair.enchanted` |
| `/msg` | `m tell whisper w pm t` | `<player> <message>` | `msg` |
| `/reply` | `r` | `<message>` | `msg` |
| `/msgtoggle` | `messagetoggle` | `[on\|off]` | `msgtoggle` |
| `/ignore` | `unignore` | `<player>` | `ignore`, `ignore.exempt` |
| `/tp` | `tele tp2p` | `<player> [player]` | `tp`, `tp.others` |
| `/tphere` | `s` | `<player>` | `tphere` |
| `/tpo` | | `<player> [player]` | `tp.override` |
| `/tpohere` | | `<player>` | `tp.override` |
| `/tpall` | | `[player]` | `tpall` |
| `/tpaall` | | `[player]` | `tpaall` |
| `/tppos` | | `<x> <y> <z> [yaw] [pitch] [world]` | `tppos` |
| `/tptoggle` | | `[player] [on\|off]` | `tptoggle`, `tptoggle.others` |
| `/back` | `return` | | `back`, `back.ondeath` |
| `/top` | | | `top` |
| `/warp` | | `[name] [player]` | `warp`, `warp.<name>`, `warp.others` |
| `/setwarp` `/delwarp` | | `<name>` | `setwarp`, `delwarp` |
| `/warps` | | | `warp` |
| `/options` | `settings` | | `options` |
| `/ban` | | `<player> [reason]` | `ban`, `ban.exempt` |
| `/tempban` | | `<player> <duration> [reason]` | `tempban`, `tempban.max.<time>` |
| `/unban` | `pardon` | `<player>` | `unban` |
| `/banip` `/unbanip` | | `<player\|ip> [reason]` | `banip`, `unbanip` |
| `/mute` `/unmute` | | `<player> [duration] [reason]` | `mute`, `mute.exempt` |
| `/kick` | | `<player> [reason]` | `kick`, `kick.exempt` |
| `/kickall` | | `[reason]` | `kickall` |
| `/warn` | | `<player> <reason>` | `warn` |
| `/punish` | `p` | `<player>` | `punish` |
| `/alts` | | `<player>` | `alts` |
| `/history` | `punishments` | `<player>` | `history` |
| `/whois` | | `<player>` | `whois` |
| `/seen` | | `<player>` | `seen`, `seen.extra` |
| `/list` | `who online` | | `list` |
| `/more` | | | `more` |
| `/kill` | | `<player>` | `kill`, `kill.exempt` |
| `/give` | | `<player> <item> [amount]` | `give` |
| `/sudo` | | `<player> <command>` | `sudo`, `sudo.exempt` |
| `/fly` | | `[player]` | `fly`, `fly.others` |
| `/skull` | | `[player]` | `skull`, `skull.others` |
| `/broadcast` | `bcast` | `<message>` | `broadcast` |
| `/itemname` | `iname` | `<name\|clear>` | `itemname` |
| `/itemlore` | `il` | `<add\|set\|clear\|remove> …` | `itemlore` |
| `/enchant` | `enchantment` | `<enchant> [level]` | `enchant`, `enchantments.allowunsafe` |
| `/heal` | | `[player]` | `heal`, `heal.others` |
| `/gamemode` | `gm` | `<mode> [player]` | `gamemode.<mode>`, `gamemode.others` |
| `/gms /gmc /gma /gmsp` | | `[player]` | as above |
| `/nick` | | `<nick\|off> [player]` | `nick`, `nick.others`, `nick.color` |
| `/powertool` | `pt` | `<command\|clear>` | `powertool`, `powertool.append` |
| `/powertooltoggle` | `ptt` | | `powertool` |
| `/clearinventory` | `ci clean clear` | `[player] [item] [amount]` | `clearinventory`, `.others`, `.all` |
| `/weather` | `sky` | `<sun\|storm> [duration]` | `weather` |
| `/speed` | | `[walk\|fly] <0-10> [player]` | `speed`, `speed.others`, `speed.bypass` |
| `/trash` | `disposal` | | `trash` |
| `/help` `/rules` `…` | | | `help`, `rules`, … |

---

## 4. Files on disk

| File | Contents |
|---|---|
| `config.yml` | settings only, after the `messages:` block migrates out |
| `messages.yml` | every message, MiniMessage, layered defaults from the jar |
| `users.yml` | per-UUID: nick, msg/tp toggles, bar style, ignore list, powertools, last IP, back location |
| `homes.yml` | unchanged |
| `warps.yml` | new |
| `punishments.yml` | mutes + history (bans live in Paper's `BanList`) |
| `inventories.yml` | unchanged |

---

## 5. Tests

Existing pattern: JUnit against real `YamlConfiguration`, pure logic only. Add exactly
these, nothing per-command:

- `ChatFormatTest` — group format beats default, placeholder substitution, legacy codes
  survive, player-supplied tags in the body are escaped.
- `DurationTest` — `"2d4h"`, `"30m"`, `"perm"`, garbage input.
- `PunishmentTest` — mute expiry arithmetic, exempt handling.
- `PermsTest` — `kremlin.x` / `essentials.x` / `.others` resolution.
- `ArgsTest` — item and enchantment parsing, unsafe-level gate.

---

## 6. Order of execution

0 → 1 → 2 → 3 → 4 → 5 → 6 → 7, and Essentials stays installed but **disabled** from the
end of Phase 1 so each phase is verified without it. It is uninstalled for real at the
end of Phase 7.

# Kremlin ↔ Discord

Account linking, role sync for ranks and teams, and moderation from Discord.

---

## Decisions

| # | Decision | Answer |
|---|---|---|
| B1 | Where the bot runs | **Inside the plugin.** JDA is loaded by Paper's own library loader, so the "bot" is Kremlin. One process, no bridge, no shared secret, no protocol to keep in sync. |
| B2 | Linking required? | **Optional.** Unlinked players play normally; linking earns roles. No login gate, so a Discord or bot outage can never lock the server out. |
| B3 | How JDA gets on the classpath | **`libraries:` in plugin.yml**, not shading. Paper resolves it and its transitives at load into an isolated classloader. No shadow plugin, no 10MB jar, no relocation. |
| B4 | Where links are stored | **`users.yml`**, key `discord`, beside every other per-player value. |
| B5 | Direction of authority | **Minecraft is the source of truth for roles**: rank and team drive Discord roles, never the other way. Discord drives Minecraft only for moderation, which is an explicit action rather than a sync. |
| B6 | Team change detection | **Polling.** SimpleTeams 2.2.0 fires only `TeamHomeTeleportEvent` — there are no join/leave/disband events to listen to — so team roles resync on a timer and on demand. |
| B7 | How a team gets a role | **An admin binds one, in game, with `/team admin discord <team> <roleid>`.** The bot never creates a role and never matches one by name. Replaced an earlier design where a `Team <name>` role was created on demand and the name prefix decided ownership — that made the bot's idea of "mine" a guess, and a guess that removes somebody's role is the worst kind. |

### The thing that shapes the code

**JDA callbacks arrive on JDA's own threads.** Every one of them is off Folia's region
threads, so nothing touching a `Player`, a world or an inventory may run inline in a Discord
handler — it has to hop to the global region scheduler or the player's own. Getting this wrong
is the failure mode for this whole feature, and it will look like it works in testing.

---

## Layout

```
me.lawsonhart.kremlin.discord
├─ Bot.java             JDA lifecycle, slash-command registration, config
├─ Links.java           the link store, pending codes, redemption -- pure logic where possible
├─ LinkCommands.java    in-game: /link, /unlink, /discord <player>
├─ SlashCommands.java   Discord-side: /link, /whois, /punish, /resync
└─ RoleSync.java        rank + team roles, on a timer and on demand
```

---

## Phases

### D1 — Linking (this phase)

1. `Bot`: read config, start JDA when enabled, shut it down cleanly on disable. Absent or blank
   token means the whole feature is simply off, exactly like Vault being missing.
2. `Links`: pending codes with an expiry, redemption from either side, and the stored pairing.
3. In-game `/link`, `/unlink`, `/discord <player>`.
4. Discord `/link`, so the flow starts on either side and finishes on the other.

**Check:** a code made in game redeems in Discord and vice versa; a code cannot redeem on the
side that made it; expiry and single-use both hold.

### D2 — Role sync

1. Rank roles from the Vault primary group, using the hook chat formatting already has.
2. Team roles from SimpleTeams, bound to an existing role by an admin. Nothing is created.
3. A verified role for anyone linked at all.
4. On a timer, on join, and on `/discord resync` (in game) or `/resync` (Discord).
5. Only roles named by id in this config are ever touched. Nothing is matched by name.

### D3 — Moderation from Discord

1. `/punish`, `/ban`, `/tempban`, `/mute`, `/kick`, `/unban`, `/unmute` as slash commands,
   gated on a configured Discord role.
2. Each one routes into the same `PunishCommands` path the in-game commands use, hopped onto
   the global region scheduler, so there is one implementation and one audit trail.
3. `/whois` and `/history` read-only lookups.
4. Every action lands in `punishments.yml` with the Discord user recorded as the source.

### D4 — Polish

1. A log channel for punishments and links.
2. `/discord` in game shows a player's linked account (staff) or the invite (everyone).
3. Unlink cleans up roles.

---

## Config shape

```yaml
discord:
  enabled: false
  token: ''            # keep this out of version control
  guild: ''            # server id
  link:
    code-seconds: 300
    code-length: 6
  roles:
    verified: ''       # role id, blank to skip
    ranks: {}          # luckperms group -> role id
    teams:
      enabled: true
      map: {}          # team name -> role id, set with /team admin discord
  resync-minutes: 30
  staff-role: ''       # who may use the moderation slash commands
```

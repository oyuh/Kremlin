# Kremlin

A Folia/Paper plugin for one Minecraft server. It replaces EssentialsX and
EssentialsChat outright, and adds combat tagging on top.

## What it does

- **Combat tagging** — take or deal player damage and you're tagged. Teleports are
  refused while tagged, running away lengthens your next teleport warmup, and logging
  out mid-fight kills you and drops your inventory.
- **Teleports** — `/tpa` with a menu and auto-accept, `/home`, `/warp`, `/back`,
  `/top`, and the staff `/tp` suite. `/tp <player>` on someone offline takes you to
  where they last logged out. Every player-facing teleport has a stand-still warmup.
- **Chat** — formatting, `/msg` and `/reply`, `/ignore`, `/broadcast`, `/nick` with a
  gradient builder, join/quit messages, MOTD, `/clearchat` and `/slowchat`.
- **Moderation** — bans, IP bans, mutes, kicks, warns, a `/punish` ladder, `/alts`
  and `/history`.
- **Player utilities** — `/invsee`, `/enderchest`, `/heal`, `/fly`, `/speed`,
  `/gamemode`, `/give`, `/enchant`, `/powertool` and the rest of the Essentials set.
- **Per-player settings** — `/options` (or `/settings`) for the combat bar style,
  private messages, teleport requests, notification sounds and auto-accept.
- **Discord** — a JDA bot for account linking, role sync, moderation and chat relay.
  JDA is fetched by the server at load via `libraries:` in `plugin.yml`, never shaded
  into the jar.

Soft-depends on SimpleTeams, Vault/VaultUnlocked, PlaceholderAPI and LuckPerms — all
optional, all looked up lazily.

## Install

Grab `Kremlin-<version>.jar` from [Releases](../../releases), drop it in `plugins/`
and restart. `config.yml` and `messages.yml` are written on first run and migrated in
place on upgrade, so your own wording survives an update.

## Build

```
./gradlew build
```

Output lands in `build/libs/`. Needs a JDK 25 toolchain; Gradle downloads one if the
machine doesn't have it.

## Test

```
./gradlew test
./gradlew nickCheck   # palette + gradient self-check
```

## Releasing

Push a `v*` tag and the [build workflow](.github/workflows/build.yml) builds the jar
and attaches it to a GitHub release. The tag sets the version, so `v1.2.0` ships
`Kremlin-1.2.0.jar` with a matching `plugin.yml`.

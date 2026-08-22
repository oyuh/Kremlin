# Kremlin

A Folia/Paper plugin for one server: combat tagging, homes and warps, tpa, chat
formatting, private messages, punishments, moderation, and player utilities.
Written to replace EssentialsX + EssentialsChat outright.

Also ships a Discord bot (JDA) for account linking, role sync, moderation and chat
relay. JDA is fetched by the server at load via `libraries:` in `plugin.yml` — it is
never shaded into the jar.

## Build

```
./gradlew build
```

Output: `build/libs/Kremlin-1.0.0.jar`. Drop it in `plugins/` and restart.

Needs a JDK 25 toolchain; Gradle will download one if the machine doesn't have it.

## Test

```
./gradlew test
./gradlew nickCheck   # palette + gradient self-check
```

## Config

`config.yml` and `messages.yml` are generated on first run and migrated in place on
upgrade. Soft-depends on SimpleTeams, Vault/VaultUnlocked, PlaceholderAPI and LuckPerms —
all optional.

See [PLAN.md](PLAN.md) and [DISCORD-PLAN.md](DISCORD-PLAN.md) for design notes.

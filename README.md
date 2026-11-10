# Sentinel

A server-side Fabric mod for Minecraft 26.3 that connects your server to Discord.

- Players post their Minecraft username in Discord to get whitelisted and receive a role. Each Discord user can link one account.
- Staff configure channels and roles with `/sentinel` in Discord.
- `/sban <player> <days> <reason>` handles timed bans, Discord logs and a banned role. The role is removed when the ban expires or the player is pardoned.

## Download

[Download Sentinel 1.0](https://github.com/IDontCaramel/Sentinel/releases/download/v1.0/sentinel-1.0.jar) for Minecraft 26.3. Requires Java 25.

## Setup

1. Install Fabric Loader and [Fabric API](https://modrinth.com/mod/fabric-api) for Minecraft 26.3.
2. Put the mod jar in `mods/` and start the server to create `config/sentinel.toml`.
3. [Create a Discord bot](https://discord.com/developers/applications), enable **Message Content Intent**, and invite it with the `bot` and `applications.commands` scopes.
4. Give it View Channels, Send Messages, Read Message History, Add Reactions and Manage Roles permissions. Place its role above the roles it will manage.
5. Set the bot token and guild ID in `config/sentinel.toml`. You can also supply the token through `SENTINEL_TOKEN`. Restart the server or run `/sentinel reload`.
6. Use `/sentinel channel` and `/sentinel role` in Discord to choose channels and roles. `/sentinel settings` shows the current setup.

The config file documents the remaining options. Discord commands require Manage Server by default. To grant access through a staff role, allow it in Discord's integration settings and in `permissions.discord_admin_role_ids`.

Linked accounts and active bans are stored in `config/sentinel/sentinel.db`.

## Build

Requires JDK 25.

```sh
./gradlew build
```

Use `build/libs/sentinel-<version>.jar`. The `-slim` jar cannot run on its own.

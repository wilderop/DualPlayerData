# DualPlayerData

Paper plugin for servers behind Velocity that need to support **both online-mode and offline-mode proxies** while keeping playerdata, advancements, and stats in sync between the two UUID types.

## Features

- **Dual UUID sync** – Automatically copies `playerdata`, `advancements`, and `stats` between the real Mojang UUID and the offline UUID on every quit.
- **Offline password protection** – When a known online account tries to join through an offline proxy, a password is required.
- **Auto-generated passwords** – On first online join (or first protected offline attempt) a random 12-character password is generated and shown only to the legitimate player (or logged to console).
- **Legacy import** – `/datasyncadmin importlegacyplayers` scans all existing playerdata files, looks up current usernames via the Mojang API, and populates the known-online list.
- **Strict single-session lock** – A username can only be online once. If a player is connected via online UUID, any offline-UUID attempt for the same name is kicked (and vice versa). This prevents mirroring / griefing when both proxies are running.
- **Brand-new players** – Completely new usernames still get the normal `/register` flow in offline mode.
- **IP session** – After a successful login, the last IP is remembered for convenience (can be extended later).

## Commands

| Command | Description |
|---------|-------------|
| `/login <password>` | Authenticate in offline mode |
| `/register <password> <confirm>` | Register a new password (brand-new accounts) |
| `/changedatapass <old> <new> <confirm>` | Change your password |
| `/datasync <player> <direction> [password]` | Manual sync (OP or correct password) |
| `/datasyncadmin importlegacyplayers` | One-time scan of all playerdata + Mojang lookup |
| `/datasyncadmin resetpass <player>` | Clear a player's password (OP) |

## Installation

1. Build with Maven (`mvn clean package`).
2. Put `DualPlayerData.jar` into the backend Paper server's `plugins/` folder.
3. Make sure the backend has `online-mode=false` in `server.properties`.
4. Restart the server.
5. (Recommended) Run `/datasyncadmin importlegacyplayers` once as OP to claim all pre-existing online accounts.

## How it works

- When a player joins with a **real Mojang UUID** (online proxy) → auto-authenticated, mapping recorded, password auto-generated if missing.
- When a player joins with an **offline UUID**:
  - If the username is in `mappings.yml` → password required (or kick + console log if no password yet).
  - If the username is brand new → normal `/register`.
- On every quit the plugin copies the three data folders between the two UUIDs so the player keeps their inventory/progress regardless of which proxy they use next.

## Files created

- `plugins/DualPlayerData/players.yml` – BCrypt password hashes + last IP
- `plugins/DualPlayerData/mappings.yml` – username → online UUID mapping

## Requirements

- Paper 1.20.4+ / 1.21+
- Java 17+
- Velocity (or any proxy that can forward real UUIDs when in online mode)

## License

MIT

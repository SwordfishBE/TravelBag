# TravelBag

TravelBag is a free and reliable backpack mod for Fabric with configurable sizes, optional LuckPerms support, smart file-based storage, and a built-in shortcut item for quick access.

[![GitHub Release](https://img.shields.io/github/v/release/SwordfishBE/TravelBag?display_name=release&logo=github)](https://github.com/SwordfishBE/TravelBag/releases)
[![GitHub Downloads](https://img.shields.io/github/downloads/SwordfishBE/TravelBag/total?logo=github)](https://github.com/SwordfishBE/TravelBag/releases)
[![Modrinth Downloads](https://img.shields.io/modrinth/dt/mBHOL0vT?logo=modrinth&logoColor=white&label=Modrinth%20downloads)](https://modrinth.com/mod/travelbag)
[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1513977?logo=curseforge&logoColor=white&label=CurseForge%20downloads)](https://www.curseforge.com/minecraft/mc-mods/travelbag)

It is designed to feel safe on real servers:
- each player has their own stored TravelBag
- the storage format always keeps room for 6 rows internally
- downgrading permissions or config size does not delete hidden items
- backups are created safely without generating nested backup files
- item NBT is preserved

---

## ✨ Features
- Configurable TravelBag size from 1 to 6 rows
- Optional LuckPerms integration for size and feature permissions
- Optional auto-pickup when a player's inventory is full
- Optional shortcut head item for fast TravelBag access
- Per-player TravelBag files stored by UUID
- Automatic backup support
- Item filter support
- Shulker box restriction toggle
- Config reload command
- Mod Menu integration
- Optional Cloth Config support for an in-game config screen
- Modrinth update checker

---

## 🔄 Commands

- `/travelbag` - Open your own TravelBag
- `/travelbag help` - Show the available TravelBag commands
- `/travelbag clean` - Remove all items from your own TravelBag
- `/travelbag clean <player>` - Remove all items from another player's TravelBag
- `/travelbag sort` - Compact and sort your own TravelBag
- `/travelbag <player>` - Open another player's TravelBag
- `/travelbag backup` - Create backups of all TravelBag data files
- `/travelbag reload` - Reload the config file

Aliases are configurable and default to:
- `/bp`
- `/backpack`

---

## 📊 Permissions

When LuckPerms support is enabled, TravelBag uses the following nodes:

- `travelbag.use`
- `travelbag.fullpickup`
- `travelbag.size.1`
- `travelbag.size.2`
- `travelbag.size.3`
- `travelbag.size.4`
- `travelbag.size.5`
- `travelbag.size.6`
- `travelbag.clean`
- `travelbag.clean.other`
- `travelbag.sort`
- `travelbag.others`
- `travelbag.others.edit`
- `travelbag.keepOnDeath`
- `travelbag.noCooldown`
- `travelbag.ignoreBlacklist`
- `travelbag.backup`
- `travelbag.reload`
- `travelbag.bypass.gamemode`
- `travelbag.admin`

`travelbag.admin` acts as an umbrella admin permission for TravelBag management features.

When LuckPerms support is disabled, TravelBag follows the config fallback values. Admin-only actions fall back to vanilla OP / gamemaster level.

---

## 🤷🏻‍♂️ How Storage Works

TravelBag stores player data in the Fabric config directory: `config/travelbag/players/`

Each player gets a separate file based on their UUID. TravelBag always stores up to 6 rows internally, even if a player currently only has access to fewer rows.

This means:
- hidden rows stay preserved when a player is downgraded
- re-upgrading size or permissions reveals those items again
- compacting can move stackable items upward into visible rows when possible

Backups are written as:
- `<uuid>.dat`
- `<uuid>.backup.dat`

---

## 🎒 Shortcut Item

TravelBag can give players a shortcut head item that opens their backpack quickly.

The shortcut item:
- can be enabled or disabled globally
- supports a configurable head texture
- can be moved freely inside the player's inventory and hotbar
- cannot be placed inside the TravelBag
- cannot be placed inside chests or ender chests
- cannot be dropped normally
- is normalized automatically if duplicates somehow appear

The displayed shortcut name uses the player's name, for example:
- `Alex's TravelBag`
- `James' TravelBag`

---

## 🚗 Auto Pickup

If enabled, TravelBag can collect nearby item entities when a player's inventory is full.

Safety rules:
- filtered items are never removed from the ground
- blocked shulker boxes are never removed from the ground
- items are only collected if they can be stored safely
- open TravelBag screens stay in sync with auto-pickup changes

---

## ⚙️ Config Highlights

TravelBag uses a simple properties config file: `config/travelbag/travelbag.properties`

Important options include:
- titles for owner and non-owner views
- drop-on-death behavior
- keepInventory interaction
- default size
- LuckPerms toggle
- cooldown
- shortcut item toggle
- shortcut preferred slot
- open sound toggle
- open sound id
- auto-pickup settings
- shulker restriction
- item filter
- allowed game modes
- aliases

---

## 🏠 Mod Menu / Cloth Config

TravelBag supports Mod Menu and Cloth Config as optional client-side extras.

This means:
- with Mod Menu + Cloth Config installed, you get a config screen
- on dedicated servers, Cloth Config is not required
- on clients without Cloth Config, the mod still works normally

---

## ⁉️ Requirements

- Minecraft `26.1.2`
- Fabric Loader `0.18.6+`
- Java `25+`
- Fabric API

Optional:
- LuckPerms / Fabric Permissions API usage
- Mod Menu
- Cloth Config

---

## 📦 Installation

| Platform   | Link |
|------------|------|
| GitHub     | [Releases](https://github.com/SwordfishBE/TravelBag/releases) |
| Modrinth | [TravelBag](https://modrinth.com/mod/travelbag) |
| CurseForge | [TravelBag](https://www.curseforge.com/minecraft/mc-mods/travelbag) |


1. Download the latest JAR from your preferred platform above.
2. Place the JAR in your server's `mods/` folder.
3. Make sure [Fabric API](https://modrinth.com/mod/fabric-api) is also installed.
4. Start Minecraft — the config file will be created automatically.

---

## 🧱 Building

```bash
git clone https://github.com/SwordfishBE/TravelBag.git
cd TravelBag
chmod +x gradlew
./gradlew build
# Output: build/libs/travelbag-<version>.jar
```

---

## 💡 Credits/Idea

Fabric mod idea based on [Minepacks](https://dev.bukkit.org/projects/minepacks) by GeorgH93.

---

## 📄 License

Released under the [AGPL-3.0 License](LICENSE)

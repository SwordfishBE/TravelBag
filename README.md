# TravelBag

[![GitHub Release](https://img.shields.io/github/v/release/SwordfishBE/TravelBag?display_name=release&logo=github)](https://github.com/SwordfishBE/TravelBag/releases)
[![GitHub Downloads](https://img.shields.io/github/downloads/SwordfishBE/TravelBag/total?logo=github)](https://github.com/SwordfishBE/TravelBag/releases)
[![Modrinth Downloads](https://img.shields.io/modrinth/dt/mBHOL0vT?logo=modrinth&logoColor=white&label=Modrinth%20downloads)](https://modrinth.com/mod/travelbag)
[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1513977?logo=curseforge&logoColor=white&label=CurseForge%20downloads)](https://www.curseforge.com/minecraft/mc-mods/travelbag)

TravelBag is a free and reliable backpack mod for Fabric with configurable sizes, optional LuckPerms support, smart file-based storage, and a built-in shortcut item for quick access.

It is designed to feel safe on real servers:
- Each player has their own stored TravelBag
- The storage format always keeps room for 6 rows internally
- Downgrading permissions or config size does not delete hidden items
- Backups are created safely without generating nested backup files
- The latest 2 backups are rotated automatically per player file
- Item data is serialized with registry-aware codecs so enchantments and other components stay intact
- Unsafe bag data is locked instead of being silently overwritten
- No client-side mod needed

---

## ✨ Features
- Configurable TravelBag size from 1 to 6 rows
- Optional LuckPerms integration for size and feature permissions
- Optional auto-pickup when a player's inventory is full
- Optional shortcut head item for fast TravelBag access
- Configurable TravelBag shortcut head item.
- Per-player TravelBag files stored by UUID
- Automatic backup support with rotation
- Item filter support
- Shulker box restriction toggle
- Config reload command
- Mod Menu integration
- Optional Cloth Config support for an in-game config screen

---

## 🔄 Commands

- `/travelbag` - Open your own TravelBag
- `/travelbag help` - Show the available TravelBag commands
- `/travelbag clean` - Remove all items from your own TravelBag
- `/travelbag clean <player>` - Remove all items from another player's TravelBag
- `/travelbag sort` - Compact and sort your own TravelBag
- `/travelbag <player>` - Open another player's TravelBag
- `/travelbag backup` - Create backups of all TravelBag data files
- `/travelbag restore <player> <latest|previous>` - Preview a valid backup for a locked TravelBag
- `/travelbag restore <player> <latest|previous> confirm` - Restore the previewed backup within 60 seconds
- `/travelbag upgrade <player> <legacy|current> [latest|previous]` - Preview an explicit Minecraft data-version choice for an ambiguous old file
- `/travelbag upgrade <player> <legacy|current> [latest|previous] confirm` - Confirm that choice within 60 seconds, preserving the original as a recovery snapshot
- `/travelbag reload` - Reload the config file
- `/travelbag migrate` - Preview old central singleplayer data and show manual migration instructions
- `/travelbag migrate confirm` - Import the previewed bag into the current world within 60 seconds

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
- `travelbag.restore`
- `travelbag.reload`
- `travelbag.bypass.gamemode`
- `travelbag.admin`

`travelbag.admin` acts as an umbrella admin permission for TravelBag management features.

When LuckPerms support is disabled, TravelBag follows the config fallback values. Admin-only actions fall back to vanilla OP / gamemaster level.

---

## 🤷🏻‍♂️ How Storage Works

TravelBag chooses player storage when a server starts:

- Singleplayer worlds (including worlds opened to LAN): `<world-save>/travelbag/players/`
- Dedicated servers: `config/travelbag/players/`, as before

Each singleplayer world has independent bags, backups and recovery snapshots, including bags belonging to LAN guests.
Switching dimensions within a world uses the same bag. 
The configuration file remains in `config/travelbag/travelbag.properties` for both environments.

Each player gets a separate file based on their UUID. TravelBag always stores up to 6 rows internally, even if a player currently only has access to fewer rows.

This means:
- hidden rows stay preserved when a player is downgraded
- re-upgrading size or permissions reveals those items again
- compacting can move stackable items upward into visible rows when possible
- item serialization uses registry-aware codecs to preserve enchantments and other modern item components
- if TravelBag cannot safely decode a bag file, that bag is locked to prevent accidental overwrite or item loss

Backups are written as:
- `<uuid>.dat`
- `<uuid>.backup.1.dat` - newest backup
- `<uuid>.backup.2.dat` - previous backup

Legacy `<uuid>.backup.dat` files from older TravelBag versions remain available as the latest restore candidate when no rotated latest backup exists.

Backups are created:
- on server start
- on `/travelbag reload`
- on `/travelbag backup`
- automatically every 10 minutes, but only if TravelBag data changed since the previous backup

If a bag file cannot be decoded safely, TravelBag also creates a timestamped failure snapshot before refusing further saves for that bag.

On dedicated servers, locked bags can only be restored by an administrator with `travelbag.restore` or `travelbag.admin`, and the target must be offline.
In a singleplayer world, the world owner can recover their own locked bag while online without enabling cheats.
That exception does not allow recovery of another player's bag.
All viewers must close the bag first, and the bag must still be locked when confirmation runs.
Upgrade commands use the same recovery permissions and target restrictions.

TravelBag validates the selected backup before confirmation, snapshots the current data file, replaces it atomically, verifies the restored data, and rolls back automatically if verification fails.

### Older Item Components

New saves record `MinecraftDataVersion` separately from TravelBag's unchanged version-1 bag schema.
Files with an older recorded Minecraft data version pass through Minecraft's item data fixes, including items inside containers.
Ordinary historical and unversioned TravelBag layouts remain supported.
Missing mod content and future data versions stay locked without stripping items or replacing the original file.

Some old files do not record a Minecraft version.
If an explicit consumable or equippable component omits sound fields. 
TravelBag locks these ambiguous files instead of guessing.

The check inspects actual item stacks, including container slots, bundles, charged projectiles and use remainders.
Arbitrary custom-data payloads are not treated as additional item components and are preserved unchanged.

The locked-bag message explains the choice:

```text
/travelbag upgrade Alex legacy
/travelbag upgrade Alex legacy confirm
```

Choose `legacy` for pre-26.4 default sounds, or `current` to preserve intentional silence.
The command previews the item count and requires confirmation within 60 seconds against the same file fingerprint.
An exact `.pre-upgrade.*.dat` recovery copy is kept.
A file with an existing Minecraft data version cannot be reinterpreted through this command.
This is not a repair for missing mods or malformed item data.

Add `latest` or `previous` before `confirm` to upgrade a backup while leaving the active bag locked, then use the normal restore preview and confirmation.
If an old central singleplayer file is ambiguous, move it manually into the chosen world's empty bag location while the world is closed, then choose its version with the upgrade command after opening that world.

### Failed Transfers

Menu transfers are committed only after the bag saves successfully.
On a handled encoding or write failure, the bag, player inventory, cursor and attempted item drops are rolled back; other viewers never receive the uncommitted bag state. 
Pickup and shortcut allocation retain their source items, and failed death drops are removed while their contents remain in the bag.
Sorting and cleaning also retain the previous data on failure.
Repeated write errors for the same cached bag log at DEBUG after the first warning.

Open menus recheck current size, permissions and allowed game mode.
Reload closes existing bag menus and returns their cursor items normally.
These checks handle refused saves during normal execution; they do not make Minecraft's separate player, entity and bag files one crash-atomic transaction.

### Moving Old Singleplayer Data

Older versions shared `config/travelbag/players/` between singleplayer worlds.
Those files do not identify their original world, so TravelBag never imports them automatically.
The world owner sees a migration notice when their old central file exists.

Run `/travelbag migrate` for a preview, then `/travelbag migrate confirm` within 60 seconds to choose this world.
The world owner can use these commands without enabling cheats.
LAN guests and dedicated-server players cannot use them.
Migration requires an empty, unlocked world-local bag with no viewers; the source must decode completely and must match the preview fingerprint.

Successful migration archives the old active file as `<uuid>.migrated.<unique-id>.dat` in the central directory.
This preserves the original bytes for recovery and removes it from automatic import eligibility in other worlds.
Old central backups remain untouched; a new backup is created in the chosen world.
If a migration is interrupted, check the active world file and the central `.migrated.*.dat` archive before retrying or recovering manually.
Existing world-local files are not merged with old bags.

For manual migration, exit the world first. Keep a recovery copy, then move the intended `<uuid>.dat` from the old central directory into `<world-save>/travelbag/players/`.
Only replace a world-local bag that you have verified is empty.
Old backups may be moved alongside the chosen bag, keeping their original names and preserving any existing world backups separately.
Do not copy the same active bag into multiple worlds or re-import recovery copies after taking items out, as that duplicates items.

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
- pickup radius is bounded to 0.25-16 blocks; non-finite values fall back to 1.5

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

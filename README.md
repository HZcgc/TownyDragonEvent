# TownyDragonEvent

TownyDragonEvent runs scheduled, isolated Ender Dragon fights for TownySMP on Paper 1.21.11. A pristine End-world template is copied for every event and the disposable runtime world is removed only after all players have left and Paper confirms that it was unloaded.

## Highlights

- Vanilla End Crystal respawn sequence in a custom arena
- Three-hour announcements, five-minute lobby, late joining and a compact final countdown
- Normal and `/dragon start april_fools` modes
- April Fools portal cease-fire for a fair melee damage window
- Expanded post-fight safety platform around the exit fountain
- Group-scaled effective boss health above Minecraft's 1024 HP attribute limit
- Separate April Fools health multiplier (50% of the equivalent normal fight by default)
- Per-player damage, death, crystal and explosion statistics with PlaceholderAPI support
- Reward commands, damage tiers, rankings, seasons and victory fireworks
- Five-life spectator system, KeepInventory, safe respawns, void rescue and a world border
- Configurable arena destruction and optional building lock
- Crash-safe, serialized template copy/reset lifecycle with validated paths and an ownership marker
- One controlled asynchronous teleport per player and a reconnect grace period
- No runtime dependency except Paper; PlaceholderAPI is optional

## Setup

1. Put the JAR into `plugins/` and restart Paper.
2. Create or load an End world named `dragonevent_template`.
3. In that template world, stand at the participant spawn and run `/dragon setspawn`.
4. Stand on the center of the exit fountain and run `/dragon setportal`.
5. Stand at the normal server spawn and run `/dragon setexit`.
6. Run `/dragon start 30` for a short test or `/dragon start` for the configured three-hour schedule.

The template should remain close to X/Z 0 so the Vanilla Dragon battle and tower regeneration use the intended center. The plugin automatically merges new default keys into existing `config.yml` and `messages.yml` files without replacing configured values.

## Commands

| Command | Purpose |
| --- | --- |
| `/dragon join` | Register or enter an open event |
| `/dragon leave` | Leave and return to the configured exit |
| `/dragon status` | Show event state and player counts |
| `/dragon stats [player]` | Show persistent statistics |
| `/dragon start [normal\|april_fools] [seconds]` | Start an event |
| `/dragon stop` | Stop and safely reset an event |
| `/dragon reload` | Reload config and messages while idle |
| `/dragon setspawn` | Set the arena arrival point in the template |
| `/dragon setportal` | Set the fountain center in the template |
| `/dragon setexit` | Set the destination outside the arena |
| `/dragon season <name>` | Change the optional statistics season |

## Permissions

- `townysmp.dragon.join` — enabled by default
- `townysmp.dragon.admin` — operator by default
- `townysmp.dragon.build` — bypasses `event.block-building: false`

## Build

Requires Java 21:

```bash
mvn -B clean package
```

The compiled plugin is written to `target/TownyDragonEvent-0.1.0-build28.jar`.

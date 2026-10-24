# Build 37: Freddie names and existing-installation timers

Freddie's boss name, visible frog name and titles now read new default messages immediately after upgrading an existing `messages.yml`. Bukkit's `getString(key, explicitFallback)` bypasses the configured defaults: saving merged defaults did not update the already loaded values used by that overload. Message reads now consult those defaults before applying the unknown-key fallback. Custom message text and prefixes remain intact.

Configuration version 5 upgrades an absent fight limit or the old explicit **1200-second** value to **1800 seconds**. This also covers installations already marked version 4. Other explicitly configured limits remain intact, and the migration runs once. The timer shared by Normal, Fools and Freddie uses that migrated value. An active fight is not retroactively restarted by editing files; apply the JAR between events and restart the server.

Replace the previous TownyDragonEvent JAR with `TownyDragonEvent-0.1.0-build37.jar`. Keep the existing plugin folder, template, arena locations, statistics and rewards. No manual deletion of `config.yml` or `messages.yml` is required.

Regression coverage includes the first read of old message files, custom names/prefixes, legacy configurations at versions 0/3/4 and the actual scheduled timer for all three modes after migration. The existing combat, reward, live-board and world-file checks also run against the packaged JAR using the real Paper 26.2 API. These are local JVM checks, not a live Minecraft client/server test.

The CI artifact and release paths now match build 37. Mockito is attached as a startup test agent instead of relying on self-attachment during the test run. Staff Connector code is unchanged by this release.

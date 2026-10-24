# Staff HQ gameplay telemetry — build 36

This is the companion Dragon JAR for Staff HQ 5.11.0 / Connector 1.11.0.

`StaffAudit` adds optional observations for scheduled events, fight start/end, every participant result (including defeats), all three modes and reward-command dispatch results. It publishes plain values through an optional connector helper. No connector is required to run the event. Missing/disabled telemetry does not change payments, keys, damage credit, world safety or gameplay.

The existing `DragonResultEvent` and lifetime API remain in place for older connectors. The additional `dragon_results` records carry mode, event ID, rank, damage, deaths, crystals, explosions and victory. They are a separate metric, not another damage/win total. Transition records are deduplicated by event ID.

Reward audit distinguishes accepted console commands from confirmed economy/key delivery. `dragon_money_commands` and `dragon_key_commands` are parsed only from accepted `eco give` / `cratekey give` commands. Rejected dispatches retain a diagnostic command record and do not add money/key command amounts. Telemetry never runs commands again.

Local verification: the previous 21 Dragon regressions passed against the packaged build 36 alongside connector/native telemetry tests on the actual Paper 26.2 API and JDK 25. The new website was also checked with isolated MariaDB, real HTTP ingestion and desktop/mobile browser checks. No production Paper server or Minecraft client was used.

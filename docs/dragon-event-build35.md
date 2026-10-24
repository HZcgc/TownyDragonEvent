# Dragon Event build35

Three boss modes share the existing disposable arena, combat lifecycle and staff statistics integration.

| Mode | Automatic chance | Solo effective HP | HP per additional fighter | Effective HP cap | Battle limit |
|---|---:|---:|---:|---:|---:|
| Ancient Dragon | 90% | 5,000 | 1,500 | 40,000 | 30 minutes |
| Flying Creeper | 5% | 10,000 | 3,000 | 80,000 | 30 minutes |
| Freddie the Frog | 5% | 10,000 | 3,000 | 80,000 | 30 minutes |

`/dragon start` and `/dragon start auto 10` select a mode once. Explicit `normal`, `april_fools`, or `freddie` starts force that mode. A time limit is an upper limit; defeating the boss still ends combat immediately. Existing external schedules and the five-minute lobby are retained. An external scheduler that explicitly passes `normal` must use the mode-free command to participate in random selection.

At 25% remaining boss health, an announced final phase increases outgoing damage by 20%, adds one projectile within the existing volley cap, and speeds special-mode salvos up to every 15 ticks. It triggers once per fight and can be disabled under `event.enrage`. The personal actionbar shows boss health, remaining time, damage rank and your damage.

Both special modes fire eight projectiles every second (90% TNT), in addition to converted Dragon volleys. Portal cease-fire windows and a 64-entity cap remain. Freddie uses the native green cold-variant Frog, so no new resource pack or custom-model plugin is required.

Four boards display one live damage snapshot on every side, refreshed once per second. They sit 13.5 blocks from the portal centre, five farther out than build34. Historic statistics remain available separately.

## Dragon Crate

The install folder adds the Dragon Crate to the existing `/crates` menu. Left click previews, right click opens or chooses a batch. Virtual key and crate ID: `dragon`. Only the victorious eligible top three receive keys: 3/2/1, alongside their existing cash prizes. Other eligible participants receive their existing money rewards only, with no keys. No victory payout is repeated within the same event.

| Reward | Chance per opening |
|---|---:|
| 64 End Stone | 15.4% |
| 64 End Stone Bricks | 12% |
| 64 Purpur Blocks | 12% |
| 32 Chorus Fruit | 10% |
| 32 Popped Chorus Fruit | 8% |
| 16 End Rods | 8% |
| 16 Ender Pearls | 8% |
| 32 Obsidian | 7% |
| 1 Ender Chest | 5% |
| 2 Shulker Shells | 5% |
| 8 Dragon's Breath | 3% |
| 2 End Crystals | 3% |
| 1 Shulker Box | 2% |
| 1 Dragon Head | 1% |
| 1 Elytra | 0.5% |
| 1 Dragon Egg | 0.1% |

The supplied modern ExcellentCrates configuration has one roll per key and total reward weight 100. The v7 [release documentation](https://nightexpressdev.com/excellentcrates/releases/v7-0-0/) confirms that rarity weights no longer alter reward chances. Existing crates, Halloween, balances, logs and databases are not modified.

## Validation

Compiled against the actual Paper 26.2 API on JDK 25. JUnit tests use production classes and real API types with mocked server objects; this is not a live Paper/client test. The uploaded build34 world recovery and HQ API bytecode are retained unchanged in the install JAR. Server-side visual/compatibility testing is still required.

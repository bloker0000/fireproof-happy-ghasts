# Fireproof Happy Ghasts

A Fabric mod that makes happy ghasts immune to lava and fire.

It only runs on the server, so players without the mod can still join a server that has it. In singleplayer you just install it like any other mod. All it needs is [Fabric Loader](https://fabricmc.net/use/), no Fabric API.

## Which jar

| Minecraft | Jar | Java |
|---|---|---|
| 1.21.6 - 1.21.8 | `fireproof-happy-ghasts-1.0.0+mc1.21.6-1.21.8.jar` | 21 |
| 1.21.9 - 1.21.10 | `fireproof-happy-ghasts-1.0.0+mc1.21.9-1.21.10.jar` | 21 |
| 1.21.11 | `fireproof-happy-ghasts-1.0.0+mc1.21.11.jar` | 21 |
| 26.1 - 26.3 | `fireproof-happy-ghasts-1.0.0+mc26.1-26.3.jar` | 25 |

Happy ghasts were added in 1.21.6, so there's nothing for older versions.

## Settings

Edit `config/fireproofghasts.properties` and run `/fireproofghasts reload`, or change settings in-game with `/fireproofghasts` (needs op, or cheats in singleplayer). Clicking a setting in that list fills in the command for you.

| Setting | Default | |
|---|---|---|
| `enabled` | `true` | Turns the whole mod on or off. |
| `lava` | `true` | Immune to lava. |
| `fire` | `true` | Immune to fire, soul fire, campfires, magma blocks and fireballs. |
| `burning` | `true` | Never catches fire, so no burning afterwards and no flames. |
| `extra_damage_types` | | More damage to ignore, like `minecraft:lightning_bolt` or `#minecraft:is_explosion`. |
| `protect_adults` | `true` | Protect grown-up happy ghasts. |
| `protect_ghastlings` | `true` | Protect ghastlings. |
| `require_harness` | `false` | Only protect happy ghasts wearing a harness. |
| `require_rider` | `false` | Only protect happy ghasts while someone rides them. |
| `protect_riders` | `false` | Riders get the same protection. |

## Building

```
./gradlew build
```

The jars end up in `build/libs/`. Gradle runs on Java 25 and downloads it if you don't have it. To try the mod in a dev environment, run `./gradlew :26.1:runClient` (or any other folder name from `versions/`).

The code in `src/` is shared. Each folder in `versions/` builds one jar: its `gradle.properties` says which Minecraft version to compile against and which versions the jar accepts, and its `VersionCompat.java` holds the few lines that differ between versions. The groups are split where Minecraft changed something the mod uses:

- 1.21.9 renamed `Entity.level()` in Fabric's intermediary names, so the 1.21.6 jar crashes there even though the code is the same.
- 1.21.11 moved the `HappyGhast` class and changed how command permissions work.
- 26.1 isn't obfuscated anymore and needs Java 25, so it's built without remapping.

## Testing

```
python smoketest/smoketest.py
```

This starts a real Fabric server for every version since 1.21.6 and runs 68 checks in-game. A happy ghast in a lava pool has to die with the mod off and survive with it on, every kind of fire damage has to be blocked while other damage still goes through, and every setting, the command and the config file get used. Pass version numbers to test only those, or `--loader min` to use each jar's oldest supported Fabric Loader. The script accepts the [Minecraft EULA](https://aka.ms/MinecraftEULA) for its test servers and caches them in your temp folder.

When a new Minecraft version comes out, run `python smoketest/smoketest.py <version> --try-anyway` to test the closest jar on it. If it passes, widen `minecraft_range` and `minecraft_label` for that group. If it doesn't, copy the newest `versions/` folder, point it at the new version, add it to `settings.gradle` and fix whatever breaks. Sometimes recompiling is enough, like it was for 1.21.9.

## License

MIT

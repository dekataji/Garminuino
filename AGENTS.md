# AGENTS.md — Garminuino

Instructions for AI coding agents working in this repository. This is a fork of
[skyforcetw/Garminuino](https://github.com/skyforcetw/Garminuino) (dekataji/Garminuino),
an Android app that forwards navigation info from the phone to a Garmin HUD over Bluetooth SPP.

## Build

Preferred: Gradle wrapper.

```bash
cd gmaps_hud   # project root for the :gmaps_hud module is Garminuino/ (settings.gradle at repo root)
./gradlew :gmaps_hud:assembleDebug
```

Environment notes (Hatch VM):
- `ANDROID_HOME=/home/hatch/android-sdk`, `JAVA_HOME=/home/hatch/jdk17/jdk-17.0.9+9`,
  Gradle 8.13 at `~/gradle-dist/gradle-8.13`, `GRADLE_USER_HOME=/root/.gradle`.
- `/tmp` is wiped on VM restart, including the offline Maven repo at `/tmp/local-maven-repo`.
  Rebuild it with `python3 ~/workspace/garminuino/rebuild-maven-repo.py` (~25–30 min), then build offline.
- `~/workspace/garminuino/apply-local-build-edits.sh apply|revert` points Gradle at the local
  repo. Edits are currently APPLIED. **Run `revert` before committing; never commit the
  `.bak-local` files or the modified build.gradle hunks.**
- The Gradle daemon cannot run in this sandbox (localhost TCP is intercepted; the client fails
  with "Could not receive a message from the daemon"). If Gradle is unusable, the APK can be
  built manually: aapt2 → javac → d8 → apksigner. The working procedure is documented in
  `~/workspace/garminuino/MANUAL_BUILD.md` (dependency resolver, manifest merge, resource dedup).

## Module layout

- `gmaps_hud/` — the app (`namespace 'sky4s.garminhud.app'`, applicationId `sky4s.garminhud.app`,
  minSdk 33, targetSdk 36).
- `bt-spp-library/` — Bluetooth SPP library (`namespace 'app.akexorcist.bluetotohspp.library'`).
- `gmaps_hud/src/main/java/sky4s/garminhud/` — HUD protocol layer
  (`hud/GarminHUD.java`, `eLane.java`, `eOutAngle.java`, `eOutType.java`, `Arrow*.java`).
- `gmaps_hud/src/main/java/sky4s/garminhud/app/` — app logic:
  `NotificationMonitor.java` (nav notification parsing → HUD updates),
  `navigation/NavigationInstructionParser.java`, `navigation/OsmRoadDataProvider.java`,
  `HudNavigationState.java`, `LocationService.java`, `MainActivity.java`,
  `detect/` (screen detectors for Google Maps / AutoMapa).
- `gmaps_hud/src/test/` — unit tests. The Gradle test executor crashes in this environment;
  verify parser logic with a standalone `javac` harness instead.

## Conventions

- **Never commit** `build/`, `.gradle/`, or any build outputs. Restore them with
  `git checkout --` before committing.
- Docs ship in commits separate from features.
- Preference keys live in `res/values/keys.xml`; user-visible strings in `strings.xml`.
  Do not rename existing preference keys (MainActivity and NotificationMonitor read them directly).
- The HUD protocol frame is `10 7B <len+6> … 55 15 <payload> <crc> 10 03`,
  CRC = `(-(0xeb + len + len + Σ payload)) & 0xff`. See ARCHITECTS.md.
- `setLanes` command is `0x02`; lane bitmask runs OuterLeft `0x40` … OuterRight `0x02`,
  DotsRight `0x01`, DotsLeft `0x80`. `setTime` is `0x05`; `setAlphabet` takes exactly 4 chars.
- The flashing "PAIR" text is HUD firmware behaviour, not something the app sends.
- `NotificationMonitor` is the hot path for navigation updates — keep it allocation-light.
- `OsmRoadDataProvider` is a throttled singleton (30 s / 100 m); never call Overpass directly
  from the UI thread.

## Git

- Remote: `dekataji/Garminuino`, branch `master`. HTTPS/SSH push does not work from the
  sandbox — sync files via the GitHub Contents API.
- Upstream `MozaPrime/Garminuino` PR #1 is open and unmerged; do not open new upstream PRs
  with the fork's token.

# ARCHITECTS.md — Garminuino architecture

How the app is put together: data flow, components, the HUD protocol, and the
navigation state machine. Companion to AGENTS.md (agent workflow) and README.md (user docs).

## System overview

```
┌──────────────┐   notifications    ┌────────────────────┐   parsed manoeuvre  ┌───────────────┐
│ Google Maps / │ ───────────────▶ │ NotificationMonitor │ ─────────────────▶ │ HudNavigation │
│ AutoMapa      │   (Notification   │  + Navigation-      │                     │ State         │
└──────────────┘   ListenerService) │  InstructionParser  └────────────────────┘       │
        │                           └────────────────────┘                              │ HUDInterface
        │ screen capture (optional)                                                     ▼
        │                           ┌────────────────────┐                     ┌───────────────┐
        └─────────────────────────▶ │ Screen detectors   │ ── speed limits ─▶ │ GarminHUD     │ ──Bluetooth SPP──▶ Garmin HUD
                                    │ (Gmaps/AutoMapa)   │                     │ (frame codec) │
                                    └────────────────────┘                     └───────────────┘
                                                          ┌────────────────────┐
                                    GPS ───────────────▶ │ LocationService    │ ── speed ──▶ ▲
                                                         │ + OsmRoadDataProvider│ ── limits ─▶ ▲
                                                          └────────────────────┘
```

The phone is the brains, the HUD is a dumb display. Everything the HUD shows —
arrow, distance, lanes, speed, clock, camera icon — is pushed from the app as
single-purpose protocol frames over Bluetooth SPP.

## Components

### NotificationMonitor (`app/NotificationMonitor.java`)
The hot path. A `NotificationListenerService` that watches navigation notifications
from Google Maps / AutoMapa, parses the instruction text and distance, and drives the HUD.
Owns the rerouting state: while the nav app recalculates it sends a neutral
`setDirection(AsDirection, ArrowOnly, AsDirection)` frame (blank arrow) instead of leaving
a stale manoeuvre on screen, and keeps the last known distance.

### NavigationInstructionParser (`app/navigation/NavigationInstructionParser.java`)
Turns free-text instructions into structured manoeuvres:
`parseGoogle()` / `parseAutoMapa()` → `Result{ direction, roundaboutExit, distance }`.
`parseRoundaboutExit()` understands EN ("take the 2nd exit") and PL ("3. zjazd",
"zjazd nr 3"), returning an exit number 1–8 or null. Roundabout exits are mapped to
HUD spokes: RHT 1→Right, 2→Straight, 3→Left, 4+→SharpLeft (mirrored for left-hand
traffic roundabouts).

### HudNavigationState (`app/HudNavigationState.java`)
Holds the current navigation picture: manoeuvre, distance, ETA mode, OSM speed limit
(60 s freshness), camera-ahead flag. ETA display modes: 0 off, 1 remaining
(`setRemainTime`), 2 arrival (`setTime`), 3 cycle every 10 s.

### OsmRoadDataProvider (`app/navigation/OsmRoadDataProvider.java`)
Singleton that queries the Overpass API for `maxspeed` on ways within 60 m and
`speed_camera` nodes within 600 m. Throttled: at most one request per 30 s AND per
100 m of movement. Converts mph → km/h (×1.60934), ignores `signals`/`none`, and the
most restrictive limit wins. Failures return cached data or null — the HUD never blocks
on the network. `LocationService` prefers the OSM limit, falling back to AutoMapa's
screen-detected limit.

### Screen detectors (`app/detect/`)
Optional full-screen analysis (MediaProjection): `GmapsScreenDetector` and
`AutoMapaScreenDetector` do OCR (ML Kit, bundled Latin model — works offline) for
speed limits and lane guidance when notifications don't carry them. Single-app capture
is deliberately not used.

### HUD protocol layer (`hud/GarminHUD.java`, `sky4s/garminhud/*.java`)
`GarminHUD` implements `HUDInterface`/`HUDAdapter` and encodes each display update as
one frame:

```
10 7B <len+6> … 55 15 <payload> <crc> 10 03
CRC = (-(0xeb + len + len + Σ payload bytes)) & 0xff
```

Commands used: `setDirection` (arrow + roundabout exit spoke), `setLanes` (`0x02`,
bitmask OuterLeft `0x40` … OuterRight `0x02`, DotsRight `0x01`, DotsLeft `0x80`),
`setDistance` / `setRemainingDistance`, `setTime` (`0x05`, also drives the idle clock),
`setRemainTime`, `setAlphabet` (exactly 4 chars), `setSpeed`, `setSpeedWarning`,
`setCameraIcon`, `setBrightness`. `DummyHUD` is a no-op implementation for testing
without hardware.

### Bluetooth (`bt-spp-library/`, `hud/HudConnectionService.java`)
Classic Bluetooth SPP via the bundled `bt-spp-library`. `HudConnectionService` manages
connect/reconnect to the remembered HUD; `HudBootReceiver` auto-starts it on phone boot.
`HudSessionManager` tracks session lifecycle; `HudIdleClock` shows the time on the HUD
when connected but not navigating.

### UI (`MainActivity.java`, `res/layout/activity_main.xml`)
Single-screen Material3 settings UI (one scrollable view of cards: Connection,
Navigation, Safety, Display, Session, Advanced, Appearance) after the 0.9.18 revamp
— the old ViewPager/TabLayout fragments were removed. All preference keys are defined
in `res/values/keys.xml` and must not be renamed.

## Safety features

- **Speed display + overspeed nudge**: GPS speed vs the active limit (OSM first,
  AutoMapa OCR second); `setSpeedWarning` when over the limit + configurable threshold.
- **Speed camera alerts**: OSM `speed_camera` nodes within 500 m raise `setCameraIcon`.
- **Lane guidance**: up to 6 lanes from notifications or screen text ("Use the left 2 lanes").

## Settings model

Preferences are plain `SharedPreferences` keyed by `keys.xml`. Feature toggles added
in 0.9.18: `roundabout_exit_numbers` (default on), `reroute_indicator` (default on),
`eta_display_mode` (0–3, migrated once from the old boolean `option_show_eta`),
`overspeed_alert` (default on), `overspeed_threshold_kmh` (default 5),
`speed_camera_alerts` (default on).

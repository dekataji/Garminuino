# Garminuino — Google Maps and AutoMapa on Garmin HUD

Garminuino is an Android application that sends navigation information from a phone to a compatible **Garmin HUD** over Bluetooth. It supports current versions of **Google Maps** and **AutoMapa**, translating their navigation instructions into the compact symbols, distances, lane guidance and status information available on the HUD.

This project is a modernized fork of the original [Garminuino](https://github.com/skyforcetw/Garminuino) Android application. It has been updated for recent Android releases and extended with AutoMapa support, screen analysis and more reliable HUD connection handling.

> [!IMPORTANT]
> This version supports Garmin HUD hardware only. The legacy BMW HUD integration from the original project has been removed.

## Current status

- Application version: **0.9.16**
- Minimum Android version: **Android 13 (API 33)**
- Target Android version: **Android 16 (API 36)**
- Tested device: **Samsung Galaxy S25 Ultra**
- Application interface: **English**

### Navigation applications

| Navigation app | Support level | Data source |
| --- | --- | --- |
| Google Maps | Supported | Android navigation notifications, with optional screen analysis |
| AutoMapa | Supported | Navigation notifications, GPS and full-screen analysis |
| Google Maps Go | Best effort | Android navigation notifications |
| Sygic | Not implemented | Can be included in diagnostic recordings only |

## Features

- Current vehicle speed read directly from GPS.
- Distance to the next manoeuvre.
- Turn direction and roundabout guidance.
- Remaining route distance and travel time when provided by the navigation app.
- Estimated arrival time (ETA), remaining time, or both alternating.
- Roundabout exit numbers parsed from the instruction text ("take the 2nd exit").
- Rerouting indicator: a neutral HUD state while the navigation app recalculates.
- Lane guidance with up to six lanes.
- Text-based lane guidance parsed from Google Maps notifications ("Use the left 2 lanes"), shown even with the screen off.
- Next-turn preview: compound instructions ("Turn left, then turn right") alternate the HUD arrow between the current and next manoeuvre.
- Idle clock: the HUD shows the current time whenever it is connected but no navigation is active.
- Automatic Bluetooth connection to the previously used Garmin HUD.
- Automatic reconnection after the HUD is disconnected and becomes available again.
- Automatic service start on phone boot (reconnects to the remembered HUD without opening the app).
- AutoMapa speed-limit recognition from the screen.
- OpenStreetMap speed limits: when GPS is on, the app looks up the local speed limit and the nearest speed cameras from OpenStreetMap data and shows an overspeed warning and a camera icon on the HUD.
- Optional automatic startup when supported navigation begins.
- Automatic shutdown after navigation ends when the application was auto-started.
- Automatic or manual HUD brightness control.
- Full-screen capture only; single-application capture is intentionally not used.
- Built-in diagnostics for collecting navigation notifications and screen-analysis results.

## Information displayed on the HUD

| Information | Google Maps | AutoMapa |
| --- | ---: | ---: |
| GPS speed | Yes | Yes |
| Next manoeuvre | Yes | Yes |
| Distance to next manoeuvre | Yes | Yes |
| Remaining route distance | Yes | Yes |
| Remaining travel time | Yes | Yes |
| ETA | Yes | Yes |
| Lane guidance | Yes | Yes, up to 6 lanes |
| Speed limit | OpenStreetMap lookup | Yes, through screen analysis; OpenStreetMap lookup as fallback |
| Speed camera alert | OpenStreetMap lookup | OpenStreetMap lookup |
| Traffic/congestion indication | Screen analysis when available | Not currently used |

The Garmin HUD protocol has a limited set of symbols. Some navigation instructions therefore have to be mapped to the closest symbol supported by the hardware.

## How it works

```text
Google Maps notification ─┐
                          ├─> instruction parser ─> Garmin HUD protocol ─> Bluetooth ─> HUD
AutoMapa notification ────┤
                          │
Full-screen analysis ─────┤
GPS speed ────────────────┘
```

The application combines several Android data sources rather than depending on one fragile interface:

1. A notification-listener service reads navigation notifications.
2. GPS supplies the current speed independently of the navigation application.
3. Optional full-screen analysis fills gaps that notifications do not expose, especially in AutoMapa.
4. Parsed data is normalized and converted into Garmin HUD commands.
5. A Bluetooth service maintains the HUD connection and retries it after an interruption.

## Google Maps support

Google Maps primarily exposes navigation data through its ongoing Android notification. Garminuino reads the current instruction and route information from the modern notification fields, then maps the manoeuvre to a Garmin HUD symbol.

Supported instructions include, among others:

- left and right turns;
- slight and sharp turns;
- keep left and keep right;
- forks and merges;
- motorway entrances and exits;
- roundabouts and exit numbers when the source data identifies them;
- straight ahead;
- arrival at the destination.

Optional screen analysis supplements the notification data with visual information such as lane guidance and congestion state. The implementation also contains a compatibility fallback for older Google Maps notification formats, but current notification data is preferred.

### Text lane guidance and next-turn preview

When a Google Maps instruction contains lane hints such as "Use the left 2 lanes", Garminuino parses them from the notification text and shows the lane guidance on the HUD directly, without requiring screen analysis. This works even with the phone screen off.

Compound instructions such as "Turn left onto Main St, then turn right onto Oak Ave" are split into the current manoeuvre and the following one. The HUD alternates between the two arrows every few seconds, giving an advance view of the next turn.

## AutoMapa support

AutoMapa does not expose every item needed by the HUD in a stable structured interface. Garminuino therefore combines:

- the AutoMapa navigation notification for textual manoeuvre information;
- GPS for current vehicle speed;
- full-screen analysis for visual values not reliably present in notifications.

Screen analysis can recognize:

- distance to the next manoeuvre;
- remaining route distance;
- remaining time and arrival time;
- the speed limit displayed on the map;
- lane-assistant tiles, including layouts containing two to six lanes.

The analysis uses normalized screen regions and is not tied to one exact pixel resolution. It should therefore work on phones with different resolutions and screen densities, provided that AutoMapa uses the same portrait layout. Major AutoMapa interface changes, unusual aspect ratios, display scaling or landscape mode may require detector adjustments.

## Automatic startup and shutdown

When **Auto-start with navigation** is enabled:

1. Starting supported navigation activates the HUD and GPS services.
2. Garminuino connects to the remembered Garmin HUD automatically.
3. If the HUD is temporarily switched off or disconnected, the application periodically attempts to reconnect.
4. Closing the navigation application stops Garminuino's active services, disconnects the HUD and removes the application task after a short grace period.

When automatic startup is disabled, Garminuino can be opened and closed manually. In this mode, stopping AutoMapa or Google Maps does not independently control the lifetime of the application.

## Idle clock and automatic connection

When the phone is connected to the Garmin HUD but no navigation is active, the HUD shows the current time. The clock updates every minute and yields immediately when navigation starts. This makes the HUD useful without starting a navigation application first.

On phone boot, Garminuino starts its connection service automatically and reconnects to the previously paired HUD, so the HUD's "PAIR" indicator clears without opening the application. (The "PAIR" message itself is shown by the HUD firmware when no Bluetooth link exists and cannot be customized by any application.)

Both behaviors can be disabled in the application settings: **Auto-connect on boot** and **Idle clock**.

## First-time setup

1. Pair the Garmin HUD with the phone in Android Bluetooth settings.
2. Install and open Garminuino.
3. Grant notification access when requested. This is required to read navigation instructions.
4. Grant location permission so the application can calculate GPS speed.
5. Select and connect to the Garmin HUD.
6. If AutoMapa support or visual lane recognition is required, enable **Screen analysis**.
7. Android will display its screen-capture confirmation dialog. Select full-screen sharing and approve it.
8. Start navigation in Google Maps or AutoMapa.

Android does not allow an application to permanently grant itself screen-capture permission. For that reason, **Screen analysis** starts disabled after a fresh application process and must be explicitly authorized by the user. This avoids showing an enabled switch when capture is not actually active.

## Screen analysis behavior

- Only full-screen capture is supported.
- Capture permission is controlled by Android and cannot be granted silently.
- Locking or turning off the phone screen pauses values that can only be read visually.
- GPS and notification-based data can continue while the display is off.
- After the screen is turned on again, visual analysis resumes automatically while the capture session remains valid.
- If Android terminates the capture session, enable Screen analysis again and approve the system dialog.

Because screen recognition observes the visible navigation interface, overlays, split-screen mode, accessibility scaling and navigation-app UI updates can affect recognition quality.

## Application settings

The main screen groups every setting into cards:

### Connection

- Scan for a Garmin HUD, or disconnect the current one.
- Connection status pill and the name of the paired device.

### Navigation

- Select the Garmin HUD arrow style.
- Enable screen analysis and grant full-screen capture permission.
- Enable text lane guidance parsed from navigation notifications.
- Enable roundabout exit numbers ("take the 2nd exit" drives the HUD's exit spoke).
- Enable the rerouting indicator shown while the route recalculates.
- Time display mode: off, remaining time, arrival time, or cycling between the two every 10 seconds.

### Safety

- Enable GPS speed display.
- Enable the overspeed alert and set the tolerance above the speed limit (km/h).
- Enable speed camera alerts.

### Display

- Select automatic brightness or set the manual brightness level.
- Enable the idle clock shown when the HUD is connected but not navigating.

### Session

- Enable automatic startup with Google Maps or AutoMapa navigation.

### Advanced

- Record, export or clear navigation diagnostics.

### Appearance

- Follow the system dark theme or select dark mode manually.

The interface deliberately omits obsolete or non-functional options from the original application, including the old debug tab, notification-status indicators, manual traffic controls, notification-display controls and BMW-specific settings.

## Permissions

| Permission or Android access | Purpose |
| --- | --- |
| Bluetooth / nearby devices | Discovering and connecting to the Garmin HUD |
| Location | Reading GPS speed and supporting Bluetooth discovery where Android requires it |
| Notification access | Reading active navigation instructions from Google Maps and AutoMapa |
| Screen capture | Recognizing AutoMapa and Google Maps information that notifications do not provide |
| Foreground-service notifications | Keeping an active navigation or HUD connection visible to the user and compliant with modern Android rules |

## Privacy

Navigation data is processed locally on the phone. The application does not require a cloud account and does not upload captured screens to a server. Screen-analysis frames are processed on-device with bundled ML Kit components. Temporary diagnostic or image files may exist in the application's private cache or in an explicitly exported diagnostic archive.

Diagnostic archives can contain notification text, application identifiers, timestamps and screen-derived navigation information. Review them before sharing publicly.

## Known limitations

- Garmin HUD hardware limits the available graphics, lane count and manoeuvre vocabulary.
- AutoMapa screen recognition depends on the visible layout and may need updates after a major AutoMapa UI redesign.
- Screen-derived information is unavailable while the display is off, although notification and GPS data can remain active.
- Android screen-capture permission must be confirmed again after the capture session or application process is terminated.
- Some phone manufacturers may restrict foreground services or Bluetooth activity unless battery optimization is disabled for the application.
- Google Maps Go support is best effort and is less thoroughly tested than the standard Google Maps application.
- Sygic HUD output is not implemented in this release.

## Diagnostics

The application can create a navigation-diagnostics archive for investigating an instruction or layout that is not recognized correctly. A useful recording should include:

- the navigation application name and version;
- phone model, Android version, display resolution and display scaling;
- a short route or simulation containing the problematic manoeuvre;
- the diagnostic ZIP;
- matching screenshots showing what was visible at that time.

Diagnostic capture recognizes Google Maps, Google Maps Go, AutoMapa and Sygic as possible source applications. Recording a source does not by itself mean that HUD output for that source is implemented.

## Changes from the original project

The upstream Android release was version 0.8.9 from 2021 and targeted Android 6-era behavior. This fork includes substantial platform, navigation and reliability work.

### Android platform modernization

- Raised the minimum supported system from Android 6 / API 23 to Android 13 / API 33.
- Updated compile and target SDK levels to API 36.
- Migrated the build to Java 17 and current Android Gradle tooling.
- Updated AndroidX, Google Play Services Location and related dependencies.
- Added modern runtime handling for Bluetooth, location, notifications and foreground services.
- Updated application components and lifecycle behavior for current Android background-execution rules.

### Navigation data

- Reworked Google Maps notification parsing for current notification formats.
- Added AutoMapa notification parsing.
- Added full-screen capture and on-device OCR for AutoMapa data unavailable through notifications.
- Added screen-based lane recognition for layouts containing up to six lanes.
- Added AutoMapa speed-limit, route distance, time and ETA recognition.
- Kept vehicle speed independent by reading it directly from GPS.
- Improved synchronization of manoeuvre symbols and distances when source fields update at different times.

### HUD and background behavior

- Added reliable reconnection to the last used Garmin HUD after Bluetooth interruptions.
- Repaired manual brightness control while retaining automatic brightness as the default.
- Added optional navigation-driven startup.
- Added complete shutdown when the supported navigation application closes after an automatic start.
- Restored screen-derived data automatically after the phone screen is switched back on.
- Prevented the UI from claiming that screen analysis is active before Android capture permission has been granted.

### Interface and maintenance

- Removed the BMW HUD implementation and BMW-specific settings.
- Changed all application labels and messages to English.
- Removed obsolete, duplicated and non-functional settings.
- Removed the old Debug tab while retaining purpose-built diagnostic export.
- Preserved the user's scroll position when changing options near the bottom of a settings page.
- Added tests and updated lint/build compatibility for the modernized code.

## Building from source

Requirements:

- Android Studio with Android SDK 36 installed;
- JDK 17;
- a Bluetooth-capable Android device running Android 13 or newer.

Clone the repository and build the debug APK:

```powershell
.\gradlew.bat :gmaps_hud:assembleDebug
```

Run unit tests:

```powershell
.\gradlew.bat :gmaps_hud:testDebugUnitTest
```

Run Android lint:

```powershell
.\gradlew.bat :gmaps_hud:lintDebug
```

The debug APK is generated under:

```text
gmaps_hud/build/outputs/apk/debug/
```

For a signed release build, provide the following Gradle properties through a secure local configuration rather than committing credentials:

```properties
keystore.path=...
keystore.password=...
keystore.alias=...
keystore.alias_password=...
```

## Project structure

```text
gmaps_hud/   Android application and Garmin HUD communication code
bt-spp-library/ Bluetooth Serial Port Profile support used by the application
gradle/      Gradle wrapper files
```

## Safety

Configure the application before driving. Do not interact with the phone, change settings or collect diagnostics while controlling a vehicle. Navigation instructions are supplementary and may be delayed, incomplete or mapped to a simplified HUD symbol.

## Credits

- Original Garminuino project and Garmin HUD protocol work: [skyforcetw/Garminuino](https://github.com/skyforcetw/Garminuino)
- Google Maps is a trademark of Google LLC.
- AutoMapa is a trademark of its respective owner.

This is an independent community project and is not affiliated with or endorsed by Garmin, Google, AutoMapa or BMW.

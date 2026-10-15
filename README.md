# MG4Dash

Full-screen launcher for the MG4 Standard (SWI69 firmware, A9 family) and its 1920 × 720 screen: a "dark glass" look, a three-column home screen, and Climate, Energy, Vehicle and Phone pages linked by a shared dock.

Languages: English (default), French, German, Spanish, Italian and Dutch, following the car's system language.

> Screenshots are rendered on a PC with simulated data (the OpenStreetMap map only shows on the car).

### Home
![Home](docs/screenshots/home.png?v=4)
Climate (temperature, fan, AUTO, A/C, recirculation, defrost), energy (battery, range, estimated consumption, drive mode, KERS, Charge / Start V2L button), vehicle position, Open-Meteo weather, a media card that follows the car's active audio source (DAB/FM radio, Bluetooth, USB, Android Auto) and a trip / status / weather widget.

Long press the Android Auto button in the dock to replace it with the app of your choice.

### Simple home layout
![Simple home](docs/screenshots/home_simple.png?v=4)
An alternative, calmer home screen for those who want fewer buttons and bigger text: three large panels (climate, battery, media), two configurable shortcuts and today's weather, with a reduced dock (Home, Phone, Android Auto, Settings). Choose it in the launcher settings, under "Home layout"; tap a panel to open the matching page.

### Shortcuts and Home Assistant
![Shortcuts](docs/screenshots/home_shortcuts.png?v=4)
The button at the top right of the map swaps it for 2 pages of 4 × 2 shortcuts: apps or Home Assistant actions (scripts, automations, scenes, buttons, gate, garage door…). Tap an empty slot, or long press any slot, to change it. The Home Assistant address and token are set in the launcher settings.

### Themes
![Charcoal background, orange buttons](docs/screenshots/home_theme_charcoal_orange.png?v=4)
Background (night blue, black, charcoal, dark green, dark burgundy) and button colour (blue, grey, red, green, orange) are chosen separately in the launcher settings and apply to every page. Battery green, heating orange, cooling cyan and alert red keep their meaning. Radio Dash 1.2 follows the same theme.

### Climate
![Climate](docs/screenshots/climate.png?v=4)
Temperature in 1° steps (LO → HI), fan speed, combinable air direction, AUTO, A/C, ECO, recirculation and front / rear defrost.

### Energy
![Energy](docs/screenshots/energy.png?v=4)
Charging (start / stop, charge limit enforced by MG4Dash, AC current), scheduled charging, V2L (stop threshold, start / stop), battery heating while driving (same switch as the stock Energy app) and consumption (driving, climate, accessories, regeneration, curve of the last kilometres).

### Vehicle
![Vehicle](docs/screenshots/vehicle.png?v=4)
Drive mode, KERS, driver assistance and vehicle status, read only, with a shortcut to MG4 Control.

### Phone
![Phone](docs/screenshots/phone.png?v=4)
Recent calls, contacts with a search field and a keypad, from the phone connected over Bluetooth. Calls go through the hands-free (HFP) account, like the stock phone app, whose in-call screen is kept.

### Launcher settings
![Settings](docs/screenshots/settings.png?v=4)
Home screen (replace the SAIC home / maintenance mode), battery capacity, weather city, startup, full screen, energy data recorder, Home Assistant and a built-in log.

## Project layout

- `app/src/main/java/app/mg4dash/`: the app (Kotlin)
  - `car/CarBridge.kt`: vehicle access through `com.saicmotor.caradapter` (climate code 7, EV code 4, settings code 8, general code 1)
  - `car/CarProps.kt`: BMS, charging and consumption properties through CarPropertyManager
  - `car/CarApi.kt`: binder clients written for MG4Dash (descriptors and transaction numbers observed on the car)
  - `energy/ConsumptionEstimator.kt`: kWh/100 estimated from battery % and kilometres
  - `energy/EnergyLogger.kt`: read-only CSV recorder of battery power, consumption counters and equipment states
  - `media/MediaHub.kt`: active player, chosen from the car's audio source like the stock launcher
  - `shortcuts/Shortcuts.kt`: shortcut grid and Home Assistant REST calls
  - `weather/WeatherRepo.kt`: Open-Meteo weather
  - `system/HomeSwitcher.kt`: maintenance mode (gives the home screen back to the SAIC launcher)
  - `LauncherApp.kt`: crash guard, after 3 crashes in 3 minutes the SAIC launcher becomes the home screen again
- `app/src/car/`: car manifest (`sharedUserId=android.uid.system`)
- `app/src/emulator/`: build without the system uid, with simulated data
- `i18n/`: translations (`strings.psv`) and the generator of the `values-*/strings.xml` files
- `keys/`: AOSP platform test certificate (public, the same one the car uses)

## Signing key

The private key is not in the repository. Download the AOSP platform test key (the one the SAIC apps are signed with):

```powershell
$u = "https://android.googlesource.com/platform/build/+/refs/tags/android-9.0.0_r61/target/product/security/platform.pk8?format=TEXT"
[IO.File]::WriteAllBytes("keys\platform.pk8", [Convert]::FromBase64String((Invoke-WebRequest $u -UseBasicParsing).Content))
```

## Build

```powershell
.\sign_platform.ps1                       # signed car build → dist\MG4Dash-<version>.apk
.\gradlew.bat testEmulatorDebugUnitTest   # screenshots 1920×720 → app\build\screenshots\
py -3 i18n\gen.py                         # regenerate the translations after editing i18n\strings.psv
```

## Install on the car

`adb install` is refused on this head unit; push the APK and install it as a system app update:

```
adb push MG4Dash-<version>.apk /data/local/tmp/x.apk
adb shell pm install -r -f /data/local/tmp/x.apk
adb shell rm /data/local/tmp/x.apk
```

1. Try it without changing the home screen: `adb shell am start -n app.mg4dash/.MainActivity`
2. Make it the home screen: long press "Settings" in the dock > "Replace SAIC home". This only disables the SAIC home activity, whose HOME priority of 2 cannot be beaten otherwise.

The SAIC launcher package (`com.saicmotor.launcher`) is never disabled: its service handles the camera, audio sources and some SystemUI commands.

## Going back

- Maintenance mode: long press "Settings" in the dock, then "Maintenance mode".
- With ADB: `adb shell pm enable com.saicmotor.launcher/.view.MainActivity`
- Uninstall: `adb uninstall app.mg4dash`

## Gestures

- Long press "Settings" in the dock: launcher settings
- Long press "Home" in the dock: list of installed apps
- Long press "Android Auto" in the dock: choose the app this button opens
- Tap the bottom-right widget: trip → status → weather
- Button at the top right of the map: map ↔ shortcuts; long press a shortcut to edit it

## Credits

- [MG4Control](https://github.com/SliDeeN/MG4Control) (SliDeeN): documentation of the A9 carapi path and of the climate and air-direction ranges.
- [MG4-Custom-Launcher](https://github.com/adammcdonagh/MG4-Custom-Launcher) (adammcdonagh): Bluetooth album art and the discovery of the AOSP test keys.
- Maps © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (osmdroid), weather by [Open-Meteo](https://open-meteo.com/).

Personal project, not affiliated with SAIC / MG. Use at your own risk.

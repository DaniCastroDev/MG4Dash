# MG4Dash

Full-screen launcher for the MG4 and its 1920 × 720 screen, developed on the SWI69 firmware (A9 family), with beta support for the other firmwares: a "dark glass" look, a three-column home screen, and Climate, Energy, Vehicle and Phone pages linked by a shared dock.

Languages: English (default), French, German, Spanish, Italian and Dutch, following the car's system language.

> Screenshots are rendered on a PC with simulated data (the OpenStreetMap map only shows on the car).

### Home
![Home](docs/screenshots/home.png?v=11)
Climate (temperature, fan, AUTO, A/C, recirculation, defrost), energy (battery, range, estimated consumption, drive mode, KERS, Charge / Start V2L button), vehicle position, Open-Meteo weather, a media card that follows the car's active audio source (DAB/FM radio, Bluetooth, USB, Android Auto) and a bottom-right widget with three pages (see below).

Long press the Android Auto button in the dock to make it open Android Auto, CarPlay or the app of your choice.

### Bottom-right widget
Tap it to switch between three pages, all readable at a glance while driving:
- **Trip energy**: one big bar and the share of driving, climate control and accessories since start-up (details on the Energy page).
- **Live power**: kW drawn from or recovered into the battery, refreshed every second, with the trip average.
- **Phone**: three favourite contacts chosen from the phone book (tap to call, long press to change). The phone book is resynced when you pick a contact, and the last one received is kept.

| | |
|---|---|
| ![Live power](docs/screenshots/home_widget_power.png?v=11) | ![Phone favourites](docs/screenshots/home_widget_phone.png?v=11) |

### Simple home layout
![Simple home](docs/screenshots/home_simple.png?v=11)
An alternative, calmer home screen for those who want fewer buttons and bigger text: three large panels (climate, battery, media), two configurable shortcuts and today's weather, with a reduced dock (Home, Phone, Android Auto, Settings). Choose it in the launcher settings, under "Home layout"; tap a panel to open the matching page.

### Shortcuts and Home Assistant
![Shortcuts](docs/screenshots/home_shortcuts.png?v=11)
The button at the top right of the map swaps it for 2 pages of 4 × 2 shortcuts: apps or Home Assistant actions (scripts, automations, scenes, buttons, gate, garage door…). Tap an empty slot, or long press any slot, to change it. The Home Assistant address and token are set in the launcher settings.

### Themes
![Charcoal background, orange buttons](docs/screenshots/home_theme_charcoal_orange.png?v=11)
Background (night blue, black, charcoal, dark green, dark burgundy) and button colour (blue, grey, red, green, orange) are chosen separately in the launcher settings and apply to every page. Battery green, heating orange, cooling cyan and alert red keep their meaning. Radio Dash 1.2 follows the same theme.

Light mode: dark, light or auto in the same settings. In auto, every page (and Radio Dash) switches to dark when the car's lights come on, and back to light when they go off.

![Light mode, home](docs/screenshots/home_light.png?v=11)

| | |
|---|---|
| ![Climate, light](docs/screenshots/climate_light.png?v=11) | ![Energy, light](docs/screenshots/energy_light.png?v=11) |
| ![Simple home, light](docs/screenshots/home_simple_light.png?v=11) | ![Settings, light](docs/screenshots/settings_light.png?v=11) |

### Climate
![Climate](docs/screenshots/climate.png?v=11)
Temperature in 1° steps (LO → HI), fan speed, combinable air direction, AUTO, A/C, ECO, recirculation and front / rear defrost.

### Energy
![Energy](docs/screenshots/energy.png?v=11)
Charging (start / stop, charge limit enforced by MG4Dash, AC current), scheduled charging, V2L (stop threshold, start / stop), battery heating while driving (same switch as the stock Energy app) and consumption (driving, climate, accessories, regeneration, curve of the last kilometres).

### Vehicle
![Vehicle](docs/screenshots/vehicle.png?v=11)
Drive mode, KERS, driver assistance and vehicle status, read only, with a shortcut to MG4 Control.

### Phone
![Phone](docs/screenshots/phone.png?v=11)
Recent calls, contacts with a search field and a keypad, from the phone connected over Bluetooth. Calls go through the hands-free (HFP) account, like the stock phone app, whose in-call screen is kept.

### Launcher settings
![Settings](docs/screenshots/settings.png?v=11)
Home screen (replace the SAIC home / maintenance mode), battery capacity, weather city, startup, full screen, energy data recorder, Home Assistant and a built-in log.

## Compatibility (beta)

MG4Dash detects the head unit firmware at startup (`ro.build.mt2712.version`) and picks the matching way to talk to the car, using what [MG4Control](https://github.com/SliDeeN/MG4Control) documented for each firmware:

| Firmware | Car access | Status |
|---|---|---|
| SWI69 | `caradapter` service (climate, EV, settings, general) | tested by the author |
| SWI131, SWI132 | same as SWI69 | beta, not tested |
| SWI68, SWI165, SWI133 | climate, charging and V2L through the stock launcher's SDK (`AirConditionManager`, `VehicleChargingManager` in `com.saicmotor.hmi.launcher`); battery, range, charge state, drive mode and KERS through `CarPropertyManager` | beta, not tested |

Features a firmware does not expose are hidden instead of failing (for example MG4Dash's own "auto stop" at the charge limit, which these firmwares already do by themselves). The stock home screen to replace is found automatically. Radio Dash only works on SWI69.

**Help support your firmware**: open Settings › Compatibility › Run the test, then scan the QR code with your phone. It opens a pre-filled [compatibility issue](https://github.com/Skittle6938/MG4Dash/issues/new?template=compatibility.md) with a short report (versions, which services answer, a few car readings: no position, VIN or account). Nothing is sent automatically.

## Project layout

- `app/src/main/java/app/mg4dash/`: the app (Kotlin)
  - `car/CarBridge.kt`: vehicle access through `com.saicmotor.caradapter` (climate code 7, EV code 4, settings code 8, general code 1)
  - `car/CarProps.kt`: BMS, charging and consumption properties through CarPropertyManager
  - `car/LegacyCar.kt`: climate on the SWI68 / SWI165 / SWI133 firmwares (stock launcher SDK)
  - `system/Firmware.kt`: firmware detection
  - `settings/Compat.kt`: compatibility report and QR code
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
- If MG4Dash no longer starts after an update (seen once on the car: "ClassNotFoundException" in logcat), recompile it on the car, then make it the home screen again:
  `adb shell cmd package compile -m speed -f app.mg4dash` then `adb shell cmd package set-home-activity --user 0 app.mg4dash/.MainActivity`
- Note: `adb shell pm enable com.saicmotor.launcher/.view.MainActivity` is refused on this head unit (the shell may not change component state); only MG4Dash's Maintenance mode can re-enable the SAIC home. Until then, Android offers a launcher chooser.
- Uninstall: `adb uninstall app.mg4dash`

## Gestures

- Long press "Settings" in the dock: launcher settings
- Long press "Home" in the dock: list of installed apps
- Long press "Android Auto" in the dock: Android Auto, CarPlay or another app
- Tap the bottom-right widget: trip energy → live power → phone; long press a phone favourite to change it
- Button at the top right of the map: map ↔ shortcuts; long press a shortcut to edit it
- Map "recenter" button: resume following the car; press again to switch between north up and car heading up (green)

## Credits

- [MG4Control](https://github.com/SliDeeN/MG4Control) (SliDeeN): documentation of the A9 carapi path, of the climate and air-direction ranges, and of the per-firmware car access used by the compatibility beta.
- [MG4-Custom-Launcher](https://github.com/adammcdonagh/MG4-Custom-Launcher) (adammcdonagh): Bluetooth album art, the discovery of the AOSP test keys and the stock launcher SDK on SWI68.
- [MG4_Simple_Launcher](https://github.com/Tommasov/MG4_Simple_Launcher) (Tommasov): notes on the vehicle adapter service of other firmwares.
- Maps © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (osmdroid), weather by [Open-Meteo](https://open-meteo.com/).

## Licence

MG4Dash is free software under the [GNU General Public License v3](COPYING) or later. This applies to every published version, including the releases before 0.10.0. Parts of the firmware detection and of the SWI68 / SWI165 / SWI133 climate code are adapted from [MG4Control](https://github.com/SliDeeN/MG4Control) (GPL v3; earlier MG4Control code under the MIT licence, notice kept in [licenses/MG4Control-MIT.txt](licenses/MG4Control-MIT.txt)).

Personal project, not affiliated with SAIC / MG. Use at your own risk.

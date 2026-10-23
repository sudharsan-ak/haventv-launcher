# Haven

Ad-free glass launcher for the living-room Sony BRAVIA (Android TV 12).
App ID `com.aktvhome.launcher` (fixed). Display name "Haven" (change in `app/src/main/res/values/strings.xml`).

## Build and install

```powershell
.\gradlew.bat assembleRelease
& "S:\AK (2)\ADB\platform-tools\adb.exe" install -r app\build\outputs\apk\release\app-release.apk
```

`install -r` updates in place and keeps collections and settings.

## Signing key: back it up

`keystore/haven-release.jks` and `keystore.properties` sign every update. If they are lost,
a new build can't install over the old one; Haven would have to be uninstalled first, which
wipes its layout. Keep a copy somewhere off this PC.

## Layout

- `data/` app list, HDMI inputs, collections (`LayoutStore`), settings, first-run sorting (`Defaults`)
- `ui/theme/` theme colors and the 6 AM / 6 PM day-night blend
- `ui/kingdom/`, `ui/tide/` one folder per theme; each puts the settings gear at the top right
- `ui/settings/` Quick settings card and the Personalize panel (both float over the live home)
- `ui/Icons.kt` collection icon set; `ui/WrapRow.kt` rows that wrap end to end
- `ui/Ambient.kt` frame clock for scene motion (works even with system animations set to 0)

# DualFlipClock Android

DualFlipClock Android is a native Android version of the dual flip clock app. It runs as a full-screen landscape clock, showing two independent time zones with local date and weather information.

The app is intentionally simple at runtime: it draws the clock UI directly with a custom Android `View`, keeps the screen awake, and stores the selected locations and layout mode in local preferences.

## Features

- Full-screen landscape dual flip clock.
- Two display modes: side-by-side and stacked.
- Custom flip animation for changing digits.
- Black background with a deterministic star field.
- Primary clock defaults to Monterey.
- Secondary clock supports GPS location or a selected city/school.
- City, university, and high-school picker.
- Local search from the bundled `cities.tsv` data file.
- University and high-school acronym search.
- Local date and weekday display for each selected time zone.
- Weather icon and Celsius temperature using Open-Meteo.
- Weather refresh every 30 minutes.
- Night dimming from 23:00 to 07:00 in the secondary clock time zone.
- Screen stays awake while the app is running.

## Data

Location data is bundled in:

```text
app/src/main/assets/cities.tsv
```

Each row contains:

```text
id    displayName    englishName    countryName    latitude    longitude    timeZone
```

The current Android app searches this local file only. It does not perform online city or school lookup during search.

## Permissions

The app declares:

- `INTERNET` for Open-Meteo weather requests.
- `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` for the secondary clock GPS option.

Location permission is only needed when the user chooses GPS for the secondary clock.

## Build

Requirements:

- Android Studio with Android Gradle Plugin 8.5.2 support.
- JDK 17.
- Android SDK Platform 35.

Clone the repository, then open the project root folder in Android Studio:

```text
DualFlipClockAndroid
```

Then run the `app` configuration on a connected Android device or emulator.

Project settings:

- Application ID: `com.dualflipclock.app`
- Minimum SDK: 19
- Target SDK: 35
- Compile SDK: 35
- Version: `1.0` / `versionCode 1`

Command-line debug build:

```bash
./gradlew assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install the debug APK to a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release Signing

Release signing is configured through a local `keystore.properties` file at the project root. This file must stay local and must not be committed.

You must create `keystore.properties` and the referenced signing key before running a release build.

Template:

```properties
storeFile=release-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Release build:

```bash
./gradlew assembleRelease
```

Release APK output:

```text
app/build/outputs/apk/release/app-release.apk
```

## Files That Must Stay Local

Do not commit generated files, machine-local configuration, or signing secrets:

- `local.properties`
- `keystore.properties`
- `*.jks`
- `.gradle/`
- `build/`
- `app/build/`
- `*.apk`
- `*.aab`

## Project Structure

```text
app/src/main/java/com/dualflipclock/app/MainActivity.java
app/src/main/assets/cities.tsv
app/src/main/AndroidManifest.xml
app/build.gradle
settings.gradle
```

Most app behavior currently lives in `MainActivity.java`, including the custom clock drawing, picker UI, local city search, GPS handling, weather loading, and preference storage.

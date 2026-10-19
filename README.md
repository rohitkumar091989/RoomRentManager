# Room Rent Manager

An Android app for landlords to track rooms, tenants and monthly rent collection.
Data is stored on the device with Room (SQLite).

## Features

- **Dashboard**: pick a month and see expected, collected and pending rent, plus each tenant's
  balance. Tap **Collect** to record a payment with the balance filled in.
- **Rooms**: add, edit and delete rooms with their monthly rent. Shows who lives in each room.
- **Tenants**: name, phone, assigned room and move-in date. Tenants only count towards a month's
  dues from their move-in month onwards.
- **Payments**: record full or partial payments against a rent month, with an optional note.

## Tech

Kotlin, Jetpack Compose (Material 3), Room with KSP, ViewModel + StateFlow.
`minSdk` 26, `targetSdk` 34. Gradle 8.7, Android Gradle Plugin 8.5.2, JDK 17.

## Building the APK

### Android Studio
1. Open this folder in Android Studio (Koala or newer).
2. Let Gradle sync finish.
3. **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
4. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Command line
With the Android SDK installed and `ANDROID_HOME` set (or `sdk.dir` in `local.properties`):

```sh
./gradlew assembleDebug
```

Install on a connected phone with `adb install app/build/outputs/apk/debug/app-debug.apk`.

## Project layout

```
app/src/main/java/com/roomrentmanager/
  data/   Room entities, DAO and database
  ui/     MainActivity, ViewModel, screens and dialogs
```

# Build the APK
**Android Studio:** File > Open this folder, let Gradle sync, plug in a phone (USB debugging on), press Run.
APK lands in `app/build/outputs/apk/debug/app-debug.apk`.
**No Android Studio:** push this folder to a GitHub repo; the Actions tab builds the APK (artifact "TruckGPS-debug-apk"). Copy it to your phone and install (allow unknown apps).
Accident alerts: paste a TomTom key into TOMTOM_KEY in MainActivity.kt.

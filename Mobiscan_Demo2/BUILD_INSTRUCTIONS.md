# Build Instructions for Mobiscan Demo2

## Quick Start - Building the APK

### Option 1: Build Debug APK (Recommended for Testing)

**Using Command Line (Windows PowerShell):**
```powershell
cd "D:\Mobiscan_Demo2 (2)\Mobiscan_Demo2"
.\gradlew.bat assembleDebug
```

**Using Command Line (Windows CMD):**
```cmd
cd "D:\Mobiscan_Demo2 (2)\Mobiscan_Demo2"
gradlew.bat assembleDebug
```

**Output Location:**
- Debug APK: `app\build\outputs\apk\debug\app-debug.apk`

### Option 2: Build Release APK (For Distribution)

**Using Command Line:**
```powershell
cd "D:\Mobiscan_Demo2 (2)\Mobiscan_Demo2"
.\gradlew.bat assembleRelease
```

**Output Location:**
- Release APK: `app\build\outputs\apk\release\app-release.apk`

**Note:** Release builds may require signing configuration. If you get signing errors, you can build an unsigned release APK or configure signing in `app/build.gradle.kts`.

### Option 3: Using Android Studio (Easiest)

1. Open Android Studio
2. Click **File → Open** and select the `Mobiscan_Demo2` folder
3. Wait for Gradle sync to complete
4. To build:
   - **Debug**: Click **Build → Make Project** or press `Ctrl+F9`
   - **Release**: Click **Build → Generate Signed Bundle / APK** → Select APK → Follow the wizard
5. APK will be in the same location as above

## Installing and Running the App

### Method 1: Install via ADB (Android Debug Bridge)

1. **Enable USB Debugging** on your Android device:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times to enable Developer Options
   - Go to Settings → Developer Options → Enable "USB Debugging"

2. **Connect your device** via USB

3. **Install the APK:**
   ```powershell
   adb install "app\build\outputs\apk\debug\app-debug.apk"
   ```

### Method 2: Direct Installation on Device

1. Transfer the APK file (`app-debug.apk`) to your Android device
2. On your device, open the file manager and locate the APK
3. Tap the APK file to install
4. If prompted, allow installation from "Unknown Sources"

### Method 3: Using Android Studio

1. Connect your device or start an emulator
2. Click the **Run** button (green play icon) or press `Shift+F10`
3. Select your device/emulator
4. The app will build, install, and launch automatically

## Troubleshooting

### Java Version Error (REQUIRED: Java 17+)

**IMPORTANT:** Android Gradle plugin requires **Java 17 or higher**. If you see:
```
Android Gradle plugin requires Java 17 to run. You are currently using Java 11.
```

**Solution 1: Install Java 17+**
1. Download Java 17 from:
   - [Eclipse Adoptium](https://adoptium.net/) (Recommended)
   - [Oracle JDK](https://www.oracle.com/java/technologies/downloads/)
   - [OpenJDK](https://openjdk.org/)
2. Install Java 17
3. Set JAVA_HOME environment variable:
   ```powershell
   # Temporary (current session only)
   $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot"
   
   # Or find your Java 17 installation path and use that
   ```

**Solution 2: Configure Java 17 in gradle.properties**
1. Find your Java 17 installation path
2. Open `gradle.properties`
3. Uncomment and update the Java home line:
   ```properties
   org.gradle.java.home=C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.16.8-hotspot
   ```
   (Replace with your actual Java 17 path, using double backslashes)

**Solution 3: Use Android Studio's Embedded JDK**
- Android Studio includes Java 17
- Open the project in Android Studio
- It will automatically use the correct Java version

**Verify Java Version:**
```powershell
java -version
# Should show version 17 or higher
```

### Gradle Build Failed

1. **Clean and rebuild:**
   ```powershell
   .\gradlew.bat clean
   .\gradlew.bat assembleDebug
   ```

2. **Invalidate caches in Android Studio:**
   - File → Invalidate Caches → Invalidate and Restart

### Missing Dependencies

If you see dependency errors:
```powershell
.\gradlew.bat --refresh-dependencies
```

## Current Build Status

✅ **Debug APK Already Built!**
- Location: `app\build\outputs\apk\debug\app-debug.apk`
- You can install this APK directly on your device

## Build Variants

- **Debug**: For development and testing (already built)
- **Release**: For production distribution (requires signing)

## Additional Commands

- **Clean build:** `.\gradlew.bat clean`
- **Build all variants:** `.\gradlew.bat build`
- **Install debug on connected device:** `.\gradlew.bat installDebug`
- **Check dependencies:** `.\gradlew.bat dependencies`

## Requirements

- Java JDK 17 or higher
- Android SDK (installed via Android Studio)
- Gradle 8.13 (included via wrapper)


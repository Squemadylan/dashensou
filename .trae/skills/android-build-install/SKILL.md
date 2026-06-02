---
name: "android-build-install"
description: "Build and install Android debug APK to a connected device on this Windows workstation. Invoke when user asks to build an Android project, run assembleDebug, install APK, debug Android, or troubleshoot Android build errors. Encapsulates the local Gradle/JDK/SDK paths and the proven fix patterns (KAPT→KSP, jlink workaround, common resource fixes)."
---

# Android Build & Install (Windows Workstation)

End-to-end playbook for building an Android project's **debug APK** and installing it onto a connected device, using the local toolchain on this Windows machine. Captures the environment layout, the working command sequence, and the recurring failure modes that have been verified and resolved.

## When to invoke this skill

- User says: "构建 / 编译 / 打包 Android 项目", "生成 debug 包", "install 到手机", "跑一下 Android 工程"
- An `assembleDebug` (or similar) task has failed and the cause is unclear
- A user says "用本地环境" / "本机有 Android 环境" — assume this is the workstation

## Local environment (verified, do not re-discover)

| Tool | Path | Notes |
|---|---|---|
| Android Studio | `C:\Program Files\Android\Android Studio` | JBR is in `jbr/` subfolder, fully featured (has `jlink.exe`) |
| Android Studio JBR (Java 21 + `jlink`) | `C:\Program Files\Android\Android Studio\jbr` | **Use this as `JAVA_HOME` for Gradle** — fixes the `jlink.exe does not exist` error |
| Android SDK | `C:\Users\Squema-Mini\AppData\Local\Android\Sdk` | `ANDROID_HOME` and `ANDROID_SDK_ROOT` must point here |
| adb | `C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe` | PowerShell does **not** have it on PATH; always call with full path or add to PATH first |
| Gradle 8.5 | `C:\Users\Squema-Mini\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat` | Already downloaded; reuse instead of re-downloading |
| Gradle cache of other versions | `C:\Users\Squema-Mini\.gradle\wrapper\dists\` | Contains `gradle-8.5-bin`, `gradle-8.5-all`, `gradle-8.9-all`, `gradle-8.11.1-all`, `gradle-9.0.0-bin` — pick the one matching the project's AGP requirement |
| Gradle user config | `C:\Users\Squema-Mini\.gradle\` | `caches/`, `jdks/`, `daemon/`, `wrapper/`, `native/`, `notifications/`, `kotlin-profile/` |
| JDKs in `C:\Program Files` | `Java`, `Python310`, `nodejs`, `Git`, `7-Zip`, `cursor`, `Everything` (this machine) | **No standalone JDK 17** is installed — Java 21 is the only JVM available |
| Scoop apps | `C:\Users\Squema-Mini\scoop\apps\` | Contains 7zip, dark, nodejs, python — **no gradle package installed** |
| `cmd` env | PowerShell only, no `adb`/`gradle` on `PATH` by default | Always invoke adb/gradle by absolute path or prepend `PATH` |

## Verified build command (use this, do not improvise)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "C:\Users\Squema-Mini\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = "C:\Users\Squema-Mini\AppData\Local\Android\Sdk"
& "C:\Users\Squema-Mini\.gradle\wrapper\dists\gradle-8.5-bin\5t9huq95ubn472n8rpzujfbqh\gradle-8.5\bin\gradle.bat" assembleDebug --no-daemon
```

Why each line matters:
- `JAVA_HOME = JBR` → supplies the `jlink.exe` that AGP's `JdkImageTransform` needs.
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` → AGP refuses to build without them.
- `gradle --no-daemon` → matches the `org.gradle.daemon=false` in this machine's common `gradle.properties`; avoids the "single-use Daemon" fork and the in-process JVM-args mismatch.
- Local `gradle.bat` instead of `gradlew` → most Android projects checked out from a non-IDE source have an **empty** `gradle/wrapper/` directory; invoking `gradlew` then errors with `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`. Use the absolute path to the system Gradle.

## Verified install command

```powershell
$apk = "<projectRoot>\app\build\outputs\apk\debug\app-debug.apk"
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s <deviceId> install -r -t $apk
```

- `-r` reinstall (keeps data) so iteration is fast.
- `-t` allows test packages — harmless for debug builds.
- Devices may drop off between commands; re-run `adb devices` first if a command returns `device '…' not found`.

## Idempotency / re-run strategy

- After a successful build, the APK is at `<projectRoot>\app\build\outputs\apk\debug\app-debug.apk`. Re-running the same project only needs `gradle assembleDebug` (it will be `UP-TO-DATE` for unchanged sources).
- Do **not** loop the whole "build → install" sequence on every user turn. If the user repeats the same request, check first whether the APK is newer than the last source edit and the device already lists `com.<applicationId>`; if so, just re-`install -r`.
- If a build fails partway, prefer to fix the cause rather than retrying blindly. The fixes below have all been verified to work on this machine.

## Recurring failure modes and verified fixes

### 1. `error: style attribute 'attr/colorXxx' not found` (AAPT link error)
- **Cause**: A `style` item uses a Material attribute that doesn't exist (e.g. `colorBackground` without `android:` prefix), or references a `@color/...` that wasn't defined.
- **Fix**: Prefer existing project resources. Replace `colorBackground` with `android:colorBackground` (the system attribute). For missing colors, swap the reference to the closest existing color (e.g. `text_primary` → `colorOnBackground`).

### 2. `error: attribute android:cornerRadius not found`
- **Cause**: `android:cornerRadius` requires API 31+, but the project's `minSdk` is lower (commonly 26).
- **Fix**: Remove the attribute from the XML. For rounded buttons on lower API levels, use a `<shape>` drawable as `android:background` instead.

### 3. `[kapt] 'com.sun.tools.javac.util.Context' class can't be found ('tools.jar' is absent)`
- **Cause**: KAPT requires `tools.jar` from JDK 8; this workstation has only JDK 21. KAPT is fundamentally incompatible with Java 21.
- **Fix (preferred)**: Migrate from KAPT to KSP. In `build.gradle` root, add `classpath 'com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:<version>'` (match Kotlin version: Kotlin 1.9.22 → KSP 1.9.22-1.0.17). In `app/build.gradle` swap `id 'kotlin-kapt'` for `id 'com.google.devtools.ksp'` and change `kapt 'x:y:z'` to `ksp 'x:y:z'`. Room 2.6.x supports KSP out of the box.
- **Fix (band-aid)**: Add `--add-exports` / `--add-opens` to `org.gradle.jvmargs` in `gradle.properties`. KAPT may still fail on Java 21 — the KSP migration is the real solution.

### 4. `jlink executable …\jlink.exe does not exist`
- **Cause**: The shell's default `JAVA_HOME` points at an incomplete bundled JRE (in this machine, the TRAE SOLO bundled JRE).
- **Fix**: Set `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` before running gradle. The JBR has a working `jlink.exe`.

### 5. `Unresolved reference: R` in Kotlin
- **Cause**: Either the resource referencing file (e.g. a `MainActivity` in subpackage `…ui`) is missing `import com.<applicationId>.R`, or the `R` class wasn't generated.
- **Fix**: Add `import com.<applicationId>.R` at the top of the file. If `R` is genuinely missing from the build, check that `processDebugResources` ran (look in build log) and that there are no earlier AAPT errors that aborted resource processing.

### 6. `Type mismatch: inferred type is Unit but Long was expected`
- **Cause**: A Room `@Insert`/`@Update`/`@Query` suspending function returns `Unit` but is assigned to a `Long`/`Int`/etc. Room infers the return type from the annotation; without an explicit return type, it defaults to `Unit`.
- **Fix**: Annotate explicitly: `suspend fun insertXxx(record: T): Long`. To use the returned id synchronously from a non-suspend caller, wrap the call in `runBlocking(Dispatchers.IO) { … }` (add `import kotlinx.coroutines.runBlocking`). Inside an existing `CoroutineScope.launch { … }`, the `id` cannot leak out — restructure to either use `async/await` with `runBlocking` or move the call to a `suspend` function.

### 7. `gradlew` fails with `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`
- **Cause**: The project's `gradle/wrapper/` directory is empty (no `gradle-wrapper.jar` / `gradle-wrapper.properties`).
- **Fix**: Invoke the system Gradle directly with the absolute path in **Local environment** above. After a successful build, recommend the user run **Android Studio → File → Sync with Gradle Files** to regenerate the wrapper.

### 8. Device disappears between commands
- **Cause**: ADB connection is fragile over USB; the device can drop between PowerShell calls.
- **Fix**: Re-run `adb devices` and grab the current device id before each command. Don't hard-code the id from a previous turn.

## Standard end-to-end workflow

1. **Inspect the project** with `LS`/`Read` to understand: `applicationId`, `compileSdk`/`minSdk`/`targetSdk`, AGP/Kotlin versions, plugins (KAPT vs KSP), resource files, Kotlin sources.
2. **Check the Gradle versions present** at `C:\Users\Squema-Mini\.gradle\wrapper\dists\`. Pick one whose major version matches what the project's AGP needs (Gradle 8.2–8.5 for AGP 8.2.x, 8.5+ for AGP 8.3+, etc.). Gradle 8.5 is a safe default for AGP 8.2–8.4.
3. **Pre-flight resource fixups** (search for common patterns that fail AAPT):
   - `grep -rE 'colorBackground|colorSurface|colorAccent' app/src/main/res/values/styles.xml` (must be `android:…` if Material)
   - `grep -rE 'android:cornerRadius' app/src/main/res` (must be removed on minSdk < 31)
   - `grep -rE '@color/(text_primary|brand_|accent_)' app/src/main/res` (must exist in `colors.xml`)
4. **Run the verified build command** (see above). Read the full last 80–100 lines of the log; the **first** `FAILED` or `error:` line is the cause to fix.
5. **Iterate on fixes** following the failure-mode catalog above. Do not retry the whole build blindly — apply the minimal change, then re-run.
6. **Locate the APK** at `app/build/outputs/apk/debug/app-debug.apk`.
7. **Confirm device** with `adb devices`. If empty, ask the user to enable USB debugging and reconnect.
8. **Install** with `adb -s <id> install -r -t <apk>`.
9. **Verify** with `adb -s <id> shell pm list packages <applicationId>` and `adb -s <id> shell dumpsys package <applicationId>` to read `versionName` and install timestamps.

## Environment probe commands (run only when something is missing/unverifiable)

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" -version
Get-ChildItem C:\Users\Squema-Mini\.gradle\wrapper\dists\ -Directory | Select-Object Name
& "C:\Users\Squema-Mini\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
Get-ChildItem "C:\Program Files\Android\Android Studio" -Directory | Select-Object Name
```

Use these only to **confirm** a path when in doubt. Do not re-discover the entire tree on every invocation — the table above is the source of truth.

## What this skill does NOT cover

- React Native / Flutter / Cordova / Capacitor builds (different toolchains).
- Release / signed APK builds (the project may need a keystore; ask the user).
- Native (C/C++) modules via NDK beyond what AGP invokes.
- iOS / macOS targets (this workstation is Windows-only).
- Network mirror / proxy configuration — this machine's `settings.gradle` already points at Aliyun mirrors; if the user is on a different network, the mirror URLs may need to be updated.

## Cross-project reuse

The skill is installed at `C:\Users\Squema-Mini\.trae\skills\android-build-install\SKILL.md` (user-level). It is **automatically available to any future Android project on this workstation** — invoke the skill via the Skill tool with `name: "android-build-install"` as soon as the user mentions building, installing, or debugging an Android app.

A copy of the same file is also placed at `<projectRoot>\.trae\skills\android-build-install\SKILL.md` so that:
1. The project can be shared (e.g. zipped, sent to a teammate) with the skill attached.
2. The user can audit / diff the skill as the project evolves.

# Llama RPC Android App

Runs discovery thread to ping the server on regular intervals (health + availability) + launches a child process which runs the GGML backend w/ specified number of threads (for easier interruption).

## Branches

| Branch | Target devices | Native ABI | NDK |
|--------|----------------|------------|-----|
| `main` | Android 6.0+ (API 23+) | `arm64-v8a`, `armeabi-v7a`, `x86_64` | default (AGP) |
| `jellybean` | Android 4.1+ (API 16), e.g. LG-E975 | `armeabi-v7a` only | **21.4.7075529** |

This README documents the **`jellybean`** branch. The **`main`** branch targets newer Android (API 23+, multiple ABIs, default NDK) and uses a different CI workflow.

## CI (GitHub Actions)

Pushing to **`jellybean`** runs [`.github/workflows/build.yml`](.github/workflows/build.yml), which:

1. Checks out the repo and `llama.cpp-rpc` submodule
2. Installs NDK **21.4.7075529** and applies [`patches/ggml-android-api16.patch`](patches/ggml-android-api16.patch)
3. Runs `./gradlew assembleDebug` with JDK **21**
4. Uploads a workflow artifact named **`app-jellybean-debug`** (the file inside is still `app-debug.apk`)

Download from the Actions tab → latest workflow run → **Artifacts** → `app-jellybean-debug`.

This is **not** a GitHub Release (no version tag, no release notes page). It is a per-commit debug APK for testing on API 16 devices. To ship formally, download that artifact or build locally and publish however you distribute apps.

**`main` branch:** uses its own workflow (JDK 17, NDK 25, no API 16 patch). Do not merge the Jelly Bean workflow into `main` without branch conditionals or a separate workflow file.

## Build and Install Instructions (local)

### Prerequisites

- Android Studio + JDK (**21** on `jellybean`; **17+** on `main`)
- Android SDK + platform-tools (`adb`) + **NDK 21.4.7075529** + CMake 3.22.1 (Jelly Bean)
- `llama.cpp-rpc` submodule initialized

Install Android Studio, then install SDK/NDK/CMake via the SDK Manager.

On macOS with Homebrew command-line tools only:

```bash
sdkmanager --install "ndk;21.4.7075529" "cmake;3.22.1" "platforms;android-36" "build-tools;36.0.0"
```

Gradle expects the NDK at `$ANDROID_SDK_ROOT/ndk/21.4.7075529`. If `sdkmanager` installed elsewhere, symlink it:

```bash
ln -sf /path/to/ndk/21.4.7075529 "$ANDROID_SDK_ROOT/ndk/21.4.7075529"
```

Check with:

```bash
java -version
adb version
```

**JDK 25:** If `./gradlew` fails with only `25.0.1` as the error, your default Java is too new for Android Gradle Plugin. Use JDK **21** (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` on macOS). The project `gradlew` script auto-selects JDK 21 on macOS when a newer JDK is configured.

### Submodule + ggml patches (Jelly Bean)

The `llama.cpp-rpc` fork must include small API 16 fixes (`std::filesystem` is not linkable below API 21). Until those commits are pushed to [rmcluster/llama.cpp-rpc](https://github.com/rmcluster/llama.cpp-rpc), apply the checked-in patch after updating the submodule:

```bash
git submodule update --init --recursive
git -C llama.cpp-rpc apply patches/ggml-android-api16.patch
```

CI applies the same patch automatically (see [CI](#ci-github-actions) above).

### Build + install debug APK (local)

```bash
cd android-app
git submodule update --init --recursive
git -C llama.cpp-rpc apply patches/ggml-android-api16.patch   # jellybean only
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Optional copy for Jelly Bean testing:

```bash
cp app/build/outputs/apk/debug/app-debug.apk app-jellybean-debug.apk
```

## Jelly Bean port (API 16) — what changed

### Required

**Build / native**

- **`ndkVersion = "21.4.7075529"`** and **`-DANDROID_PLATFORM=android-16`** so native code links against Jelly Bean Bionic (not API 21+ `@LIBC` relocations).
- **`armeabi-v7a` only**, `minSdk = 16`, `targetSdk = 22`, MultiDex, core library desugaring.
- **`GGML_OPENMP=OFF`** — OpenMP runtime needs symbols missing on API 16.
- **`-U_FORTIFY_SOURCE`** — API 16 has no `__*_chk` fortify symbols.
- **`android_compat`** static shims linked into ggml/llama/rpc-server:
  - `getline_compat` — `getline()` not in API 16 libc.
  - `bionic_math_compat` — `log2f`, `posix_memalign`, `clock_gettime`, etc.
  - `--wrap=_Exit` for clean subprocess teardown.
- **`patches/ggml-android-api16.patch`** on `llama.cpp-rpc`: POSIX paths instead of `std::filesystem` in RPC cache + backend registry on API &lt; 21.

**Java / manifest**

- `MultiDexApplication` (`RpcApp`) and remove merged `InitializationProvider` (startup lives in secondary dex without multidex install).
- Older AndroidX / ZXing embedded scanner (no ML Kit Code Scanner).
- `ServerService`: reflection for notification channels; numeric API checks instead of `VERSION_CODES.O`; `startForeground` 2-arg overload only; no `foregroundServiceType` (API 29+).
- Layout: `paddingLeft`/`paddingRight` instead of `paddingStart`/`paddingEnd`; `android:background` instead of `backgroundTint` where needed for API 16.
- `tab_background.xml`: simple shape selector (not `layer-list`) — layered drawables render as black on API 16 `Button` backgrounds.

### Removed (not needed after NDK 21 + API 16 link)

These were only required when native libs were built with **NDK 27 / API 21** sysroot and failed at runtime with `CANNOT LINK EXECUTABLE` (`strtof@LIBC`, locale `*_l`, `statvfs`, etc.):

- `libbionic_stdio_compat.so` + `LD_PRELOAD` + `/system/bin/sh -c` RPC launch wrapper
- `bionic_fortify_compat.c`, `bionic_locale_compat.c`, `bionic_libc.version`
- `android.ndk.suppressMinSdkVersionError=21` in `gradle.properties`

## Components

### Java-side

- `app/src/main/java/com/llama/rpcapp/MainActivity.java`
  - Owns user input UI (host/port/discovery/threads) and start/stop actions.
  - Loads saved values on screen open.
  - Persists edited values only when user taps **Start**.
  - Starts/stops `ServerService`.

- `app/src/main/java/com/llama/rpcapp/ServerConfig.java`
  - Config model shared by UI/repository/service.
  - Normalizes config values (like host and discovery IP formatting).

- `app/src/main/java/com/llama/rpcapp/SettingsRepository.java`
  - SharedPreferences-backed persistence.
  - `loadConfig()` reads app config from XML.
  - `saveConfig()` writes app config to XML.

- `app/src/main/java/com/llama/rpcapp/ServerService.java`
  - Foreground service and runtime owner for server execution.
  - Loads config from `SettingsRepository` at startup.
  - Chooses an available RPC port and writes resolved config back to storage.
  - Launches native RPC binary (`librpc-server.so`) via `ProcessBuilder`.
  - Streams native process output to logcat.
  - Runs discovery announce loop to tracker.
  - Stops the child process when the user taps **Stop Server**.

- `app/src/main/java/com/llama/rpcapp/NativeRpcServer.java`
  - JNI wrapper exposing `getMaxSize()` for native backend capacity probes; discovery now uses Android runtime memory estimates.

### C++/Native components

- `app/src/main/cpp/native-lib.cpp`
  - JNI implementation for app-process native helpers (`getMaxSize()` only right now).

- `app/src/main/cpp/rpc-server-main.cpp`
  - Standalone C++ entrypoint for the RPC server process.

- `app/src/main/cpp/CMakeLists.txt`
  - Builds both native outputs:
    - `llama-rpc` (JNI library used inside app process)
    - `rpc-server` (server binary packaged as launchable `.so`)
  - On Android, links `android_compat` into ggml/llama targets.

- `app/src/main/cpp/android_compat/`
  - API 16 Bionic/libm shims (`getline`, `log2f`, …).

## Logical Flow

1. User edits values in `MainActivity` UI and taps **Start**.
2. `MainActivity` saves config to SharedPreferences through `SettingsRepository`.
3. `ServerService` starts in foreground mode and loads saved config.
4. Service resolves an available listening port and writes resolved config back.
5. Service launches `librpc-server.so` via `ProcessBuilder`.
6. Native `rpc-server-main.cpp` initializes GGML backend and enters RPC serve loop.
7. Discovery thread sends periodic tracker announces including:
   - host/IP, service port, device model, estimated usable RAM, battery, temperature.
8. On stop/destroy, service interrupts discovery loop and destroys child process.

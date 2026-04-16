# Llama RPC Android App

Runs discovery thread to ping the server on regular intervals (health + availability) + launches a child process which runs the GGML backend w/ specified number of threads (for easier interruption).

## Build and Install Instructions

```
cd android-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

```
## Components

### Java/Kotlin-side

- `app/src/main/java/com/llama/rpcapp/MainActivity.java`
  - Owns user input UI (host/port/discovery/threads) and start/stop actions.
  - Loads saved values on screen open.
  - Persists edited values only when user taps **Start**.
  - Starts/stops `RpcServerService`.

- `app/src/main/java/com/llama/rpcapp/ServerConfig.java`
  - Config model shared by UI/repository/service.
  - Normalizes config values (like host and discovery IP formatting).

- `app/src/main/java/com/llama/rpcapp/SettingsRepository.java`
  - SharedPreferences-backed persistence.
  - `loadConfig()` reads app config from XML.
  - `saveConfig()` writes app config to XML.

- `app/src/main/java/com/llama/rpcapp/RpcServerService.java`
  - Foreground service and runtime owner for server execution.
  - Loads config from `SettingsRepository` at startup.
  - Chooses an available RPC port and writes resolved config back to storage.
  - Launches native RPC binary (`librpc-server.so`) via `ProcessBuilder`.
  - Streams native process output to logcat.
  - Runs discovery announce loop to tracker.
  - Stops the child process when the user taps **Stop Server**.

- `app/src/main/java/com/llama/rpcapp/NativeRpcServer.java`
  - JNI wrapper exposing `getMaxSize()` used by discovery announces.

### C++/Native components

- `app/src/main/cpp/native-lib.cpp`
  - JNI implementation for app-process native helpers (`getMaxSize()` only right now).

- `app/src/main/cpp/rpc-server-main.cpp`
  - Standalone C++ entrypoint for the RPC server process.

- `app/src/main/cpp/CMakeLists.txt`
  - Builds both native outputs:
    - `llama-rpc` (JNI library used inside app process)
    - `rpc-server` (server binary packaged as launchable `.so`)

## Logical Flow

1. User edits values in `MainActivity` UI and taps **Start**.
2. `MainActivity` saves config to SharedPreferences through `SettingsRepository`.
3. `RpcServerService` starts in foreground mode and loads saved config.
4. Service resolves an available listening port and writes resolved config back.
5. Service launches `librpc-server.so` via `ProcessBuilder`.
6. Native `rpc-server-main.cpp` initializes GGML backend and enters RPC serve loop.
7. Discovery thread sends periodic tracker announces including:
   - host/IP, service port, device model, max backend size, battery, temperature.
8. On stop/destroy, service interrupts discovery loop and destroys child process.

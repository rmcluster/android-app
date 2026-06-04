package com.llama.rpcapp;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import fi.iki.elonen.NanoHTTPD;
import timber.log.Timber;

public class ServerService extends Service {
    private static final String LOG_TAG = "ServerService";
    private static final String CHANNEL_ID = "RpcServerStatusChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int DEFAULT_PORT = 47671;
    private static final int HEALTH_CHECK_TIMEOUT_MS = 1500;
    private static final int HEALTH_CHECK_INTERVAL_MS = 10_000;
    private static final int HEALTH_CHECK_FAILURE_THRESHOLD = 3;
    private static final int DISCOVERY_START_DELAY_MS = 5000;
    private static final int STARTUP_GRACE_MS = 2000;
    private static final int SHUTDOWN_WAIT_MS = 1000;

    private final Object lifecycleLock = new Object();

    private Process rpcProcess;
    private Thread rpcProcessLoggerThread;
    private Thread rpcProcessWatcherThread;
    private Thread discoveryThread;
    private StorageServer storageServer;

    private File storageDir;
    private File llamaCacheDir;
    private String host = "0.0.0.0";
    private int assignedPort = DEFAULT_PORT;
    private int storagePort = DEFAULT_PORT + 1;

    private volatile boolean isRunning = false;
    private volatile boolean isShuttingDown = false;
    private volatile boolean discoveryEnabled = false;
    private Boolean lastRpcHealthy = null;
    private Boolean lastStorageHealthy = null;
    private Boolean lastAnnounceEligible = null;
    private volatile String lastHealthError = "";
    private int consecutiveStorageProbeFailures = 0;
    private int consecutiveRpcProbeFailures = 0;
    private long storageStartedAtMs = 0L;
    private long rpcStartedAtMs = 0L;

    public enum UiState { IDLE, SEARCHING, CONNECTED }
    public static volatile UiState currentState = UiState.IDLE;

    public static final class HealthSnapshot {
        public final String status;
        public final String lastError;
        public final boolean rpcHealthy;
        public final boolean storageHealthy;
        public final boolean announceEligible;

        public HealthSnapshot(String status, String lastError, boolean rpcHealthy, boolean storageHealthy, boolean announceEligible) {
            this.status = status;
            this.lastError = lastError;
            this.rpcHealthy = rpcHealthy;
            this.storageHealthy = storageHealthy;
            this.announceEligible = announceEligible;
        }
    }

    public interface HealthListener { void onHealthChanged(HealthSnapshot snapshot); }
    public static volatile HealthSnapshot currentHealth = new HealthSnapshot("idle", "", false, false, false);
    private static volatile HealthListener healthListener = null;

    public interface StateListener { void onStateChanged(UiState state); }
    private static volatile StateListener stateListener = null;

    public static void setStateListener(StateListener listener) {
        stateListener = listener;
    }

    public static void setHealthListener(HealthListener listener) {
        healthListener = listener;
    }

    private void notifyState(UiState state) {
        currentState = state;
        StateListener listener = stateListener;
        if (listener != null) {
            new Handler(Looper.getMainLooper()).post(() -> listener.onStateChanged(state));
        }
    }

    private void notifyHealth(String status, boolean rpcHealthy, boolean storageHealthy) {
        boolean announceEligible = rpcHealthy && storageHealthy;
        HealthSnapshot snapshot = new HealthSnapshot(status, lastHealthError, rpcHealthy, storageHealthy, announceEligible);
        currentHealth = snapshot;
        HealthListener listener = healthListener;
        if (listener != null) {
            new Handler(Looper.getMainLooper()).post(() -> listener.onHealthChanged(snapshot));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "RPC Server Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Background node status updates");
            serviceChannel.enableVibration(false);
            serviceChannel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (lifecycleLock) {
            if (isRunning) {
                Timber.tag(LOG_TAG).i("ServerService already running; ignoring duplicate start");
                return START_NOT_STICKY;
            }
            isRunning = true;
            isShuttingDown = false;
        }

        SettingsRepository settings = new SettingsRepository(this);
        ServerConfig baseConfig = settings.loadConfig();

        String discoveryIp = baseConfig.discoveryIp;
        int discoveryPort = baseConfig.discoveryPort;
        String discoveryToken = baseConfig.discoveryToken;
        String nickname = baseConfig.nickname;
        int threads = baseConfig.threads;
        String nodeId = baseConfig.nodeId;

        discoveryEnabled = !discoveryIp.isEmpty();
        assignedPort = findAvailablePort(DEFAULT_PORT);
        storagePort = findAvailablePort(assignedPort + 1);
        host = getLocalIpAddress();
        notifyState(UiState.IDLE);

        settings.saveConfig(new ServerConfig(
                nodeId,
                assignedPort,
                storagePort,
                discoveryIp,
                discoveryPort,
                discoveryToken,
                nickname,
                threads
        ));

        Notification notification = buildNotification("Starting on " + host + ":" + assignedPort);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        try {
            storageDir = getStorageDirectory("StorageApp");
            llamaCacheDir = ensureDirectory(new File(getCacheDir(), "llama.cpp"));
        } catch (IllegalStateException e) {
            Timber.tag(LOG_TAG).e(e, "Failed to initialize storage directory");
            setLastHealthError("Failed to initialize storage directory");
            updateRuntimeState("unavailable", false, false);
            notifyState(UiState.IDLE);
            shutdownService();
            return START_NOT_STICKY;
        }

        try {
            startStorageServer();
        } catch (Exception e) {
            Timber.tag(LOG_TAG).e(e, "Initial storage server startup failed; recovery loop will retry");
            setLastHealthError(describeError(e, "Initial storage server startup failed"));
        }

        try {
            startRpcProcess(threads);
        } catch (Exception e) {
            Timber.tag(LOG_TAG).e(e, "Initial RPC process startup failed; recovery loop will retry");
            setLastHealthError(describeError(e, "Initial RPC process startup failed"));
        }

        startDiscoveryPing(discoveryIp, discoveryPort, discoveryToken, nickname, nodeId, threads);
        synchronized (lifecycleLock) {
            updateRuntimeState("starting", rpcProcess != null, storageServer != null);
        }

        return START_NOT_STICKY;
    }

    private File getStorageDirectory(String folderName) {
        File base = getExternalFilesDir(null);
        File fallbackBase = getFilesDir();

        if (base != null) {
            File folder = new File(base, folderName);
            if ((folder.exists() || folder.mkdirs()) && folder.isDirectory()) {
                return folder;
            }
            Timber.tag(LOG_TAG).e("Failed to create storage directory at %s, falling back to internal storage", folder.getAbsolutePath());
        }

        File fallbackFolder = new File(fallbackBase, folderName);
        if ((fallbackFolder.exists() || fallbackFolder.mkdirs()) && fallbackFolder.isDirectory()) {
            return fallbackFolder;
        }

        throw new IllegalStateException("Failed to create storage directory at " + fallbackFolder.getAbsolutePath());
    }

    private File ensureDirectory(File dir) {
        if ((dir.exists() || dir.mkdirs()) && dir.isDirectory()) {
            return dir;
        }
        throw new IllegalStateException("Failed to create directory at " + dir.getAbsolutePath());
    }

    private int findAvailablePort(int requestedPort) {
        try {
            tryBindPort(requestedPort);
            return requestedPort;
        } catch (IOException e) {
            try {
                int resolvedPort = tryBindPort(0);
                Timber.tag(LOG_TAG).w("Port %d was occupied. Dynamically bound to %d", requestedPort, resolvedPort);
                return resolvedPort;
            } catch (IOException ex) {
                Timber.tag(LOG_TAG).e(ex, "Could not find a free port");
                return requestedPort;
            }
        }
    }

    private int tryBindPort(int port) throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return socket.getLocalPort();
        }
    }

    private String getLocalIpAddress() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
                 interfaces.hasMoreElements(); ) {
                java.net.NetworkInterface intf = interfaces.nextElement();
                if (!intf.getName().contains("wlan")) {
                    continue;
                }
                for (java.util.Enumeration<java.net.InetAddress> addresses = intf.getInetAddresses();
                     addresses.hasMoreElements(); ) {
                    java.net.InetAddress inetAddress = addresses.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        Timber.tag(LOG_TAG).i("Found IP via NetworkInterface (%s): %s", intf.getName(), inetAddress.getHostAddress());
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Timber.tag(LOG_TAG).e(ex, "IP Address error");
        }
        return "0.0.0.0";
    }

    private Notification buildNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RMCluster Node")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
    }

    private void updateNotificationStatus(String contentText) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(contentText));
        }
    }

    private void startDiscoveryPing(String targetIp, int targetPort, String discoveryToken, String nickname, String nodeId, int threads) {
        discoveryThread = new Thread(() -> {
            try {
                Thread.sleep(DISCOVERY_START_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (!discoveryEnabled) {
                Timber.tag(LOG_TAG).i("No discovery IP configured; running health supervisor without tracker announces");
            }

            while (isRunning) {
                try {
                    boolean servicesHealthy = ensureServicesHealthy(threads);
                    if (!servicesHealthy) {
                        notifyState(UiState.IDLE);
                        Timber.tag(LOG_TAG).w("Announce skipped, storage and/or inference unhealthy.");
                        Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
                        continue;
                    }

                    String model = Build.MODEL;
                    long maxSize = estimateUsableMemoryBytes();
                    float battery = readBatteryPercent();
                    float temperature = readBatteryTemperatureC();
                    String localIp = getLocalIpAddress();

                    if (!discoveryEnabled) {
                        notifyState(UiState.IDLE);
                        updateRuntimeState("running", true, true);
                        Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
                        continue;
                    }

                    String urlString = "http://" + targetIp + ":" + targetPort
                            + "/announce?id=" + nodeId
                            + "&port=" + assignedPort
                            + "&storage_port=" + storagePort
                            + "&ip=" + localIp
                            + "&model=" + URLEncoder.encode(model, "UTF-8")
                            + "&max_size=" + maxSize
                            + "&battery=" + battery
                            + "&temperature=" + temperature;
                    if (!discoveryToken.isEmpty()) {
                        urlString += "&token=" + URLEncoder.encode(discoveryToken, "UTF-8");
                    }
                    if (!nickname.isEmpty()) {
                        urlString += "&nickname=" + URLEncoder.encode(nickname, "UTF-8");
                    }

                    URL url = new URL(urlString);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    try {
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        int responseCode = conn.getResponseCode();
                        if (responseCode != HttpURLConnection.HTTP_OK) {
                            notifyState(UiState.SEARCHING);
                            Timber.tag(LOG_TAG).e("Failed to announce to tracker, response code: %d", responseCode);
                            setLastHealthError("Tracker responded with HTTP " + responseCode);
                            updateRuntimeState("degraded", true, true);
                            Thread.sleep(1000);
                            continue;
                        }
                        try (java.io.InputStream in = conn.getInputStream();
                             java.util.Scanner scanner = new java.util.Scanner(in).useDelimiter("\\A")) {
                            String responseBody = scanner.hasNext() ? scanner.next() : "";
                            int interval = new org.json.JSONObject(responseBody).getInt("interval");
                            notifyState(UiState.CONNECTED);
                            Timber.tag(LOG_TAG).d("Announced to tracker, reannouncing in %d seconds", interval);
                            updateRuntimeState("running", true, true);
                            Thread.sleep(interval * 1000L);
                        }
                    } finally {
                        conn.disconnect();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    notifyState(discoveryEnabled ? UiState.SEARCHING : UiState.IDLE);
                    Timber.tag(LOG_TAG).e(e, "Error in discovery thread");
                    setLastHealthError(describeError(e, "Discovery loop error"));
                    updateRuntimeState("unavailable", isRpcHealthy(), isStorageHealthy());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "service-supervisor");
        discoveryThread.start();
    }

    private boolean ensureServicesHealthy(int threads) {
        synchronized (lifecycleLock) {
            if (isRunning && !isShuttingDown && storageServer == null) {
                try {
                    startStorageServer();
                } catch (Exception e) {
                    Timber.tag(LOG_TAG).e(e, "Storage server startup failed");
                    setLastHealthError(describeError(e, "Storage server startup failed"));
                }
            }
            if (isRunning && !isShuttingDown && rpcProcess == null) {
                try {
                    startRpcProcess(threads);
                } catch (Exception e) {
                    Timber.tag(LOG_TAG).e(e, "RPC process startup failed");
                    setLastHealthError(describeError(e, "RPC process startup failed"));
                }
            }
        }

        boolean rpcProbe = isRpcHealthy();
        boolean rpcWithinGrace = isRpcWithinStartupGrace();
        boolean storageProbeSkipped = isStorageProbeSkipped();
        boolean storageProbe = storageProbeSkipped || probeStorageHttp();
        boolean storageWithinGrace = isStorageWithinStartupGrace();

        updateRpcProbeFailureCounter(rpcProbe, rpcWithinGrace);
        updateStorageProbeFailureCounter(storageProbe, storageWithinGrace, storageProbeSkipped);

        boolean rpcHealthy = isRpcConsideredHealthy(rpcProbe, rpcWithinGrace);
        boolean storageHealthy = isStorageConsideredHealthy(storageProbe, storageWithinGrace);
        boolean rpcNeedsRestart = rpcNeedsRestart(rpcWithinGrace);
        boolean storageNeedsRestart = storageNeedsRestart(storageWithinGrace);

        Timber.tag(LOG_TAG).d(
                "Health check rpc_probe=%s storage_probe=%s storage_probe_skipped=%s rpc_grace=%s storage_grace=%s rpc_failures=%d storage_failures=%d rpc_healthy=%s storage_healthy=%s",
                rpcProbe,
                storageProbe,
                storageProbeSkipped,
                rpcWithinGrace,
                storageWithinGrace,
                consecutiveRpcProbeFailures,
                consecutiveStorageProbeFailures,
                rpcHealthy,
                storageHealthy);

        if (storageNeedsRestart) {
            Timber.tag(LOG_TAG).w(
                    "Storage unhealthy after %d probe failures; attempting restart on port %d",
                    consecutiveStorageProbeFailures,
                    storagePort);
            consecutiveStorageProbeFailures = 0;
            try {
                restartStorageServer();
            } catch (Exception e) {
                Timber.tag(LOG_TAG).e(e, "Storage restart failed");
                setLastHealthError(describeError(e, "Storage restart failed"));
            }
            storageProbeSkipped = isStorageProbeSkipped();
            storageProbe = storageProbeSkipped || probeStorageHttp();
            storageWithinGrace = isStorageWithinStartupGrace();
            updateStorageProbeFailureCounter(storageProbe, storageWithinGrace, storageProbeSkipped);
            storageHealthy = isStorageConsideredHealthy(storageProbe, storageWithinGrace);
            if (storageHealthy) {
                Timber.tag(LOG_TAG).i("Storage restart succeeded");
            }
        }

        if (rpcNeedsRestart) {
            Timber.tag(LOG_TAG).w(
                    "RPC unhealthy after %d probe failures; attempting restart on port %d",
                    consecutiveRpcProbeFailures,
                    assignedPort);
            consecutiveRpcProbeFailures = 0;
            try {
                restartRpcProcess(threads);
            } catch (Exception e) {
                Timber.tag(LOG_TAG).e(e, "RPC restart failed");
                setLastHealthError(describeError(e, "RPC restart failed"));
            }
            rpcProbe = isRpcHealthy();
            rpcWithinGrace = isRpcWithinStartupGrace();
            updateRpcProbeFailureCounter(rpcProbe, rpcWithinGrace);
            rpcHealthy = isRpcConsideredHealthy(rpcProbe, rpcWithinGrace);
            if (rpcHealthy) {
                Timber.tag(LOG_TAG).i("RPC restart succeeded");
            }
        }

        updateRuntimeState(rpcHealthy && storageHealthy ? "running" : "recovering", rpcHealthy, storageHealthy);
        return rpcHealthy && storageHealthy;
    }

    private boolean isStorageProbeSkipped() {
        StorageServer server;
        synchronized (lifecycleLock) {
            server = storageServer;
        }
        return server != null && server.isBusy();
    }

    private boolean isStorageWithinStartupGrace() {
        return storageStartedAtMs > 0L
                && (System.currentTimeMillis() - storageStartedAtMs) < STARTUP_GRACE_MS;
    }

    private boolean isRpcWithinStartupGrace() {
        return rpcStartedAtMs > 0L
                && (System.currentTimeMillis() - rpcStartedAtMs) < STARTUP_GRACE_MS;
    }

    private void updateRpcProbeFailureCounter(boolean rpcProbe, boolean rpcWithinGrace) {
        Process process;
        synchronized (lifecycleLock) {
            process = rpcProcess;
        }
        if (process == null) {
            consecutiveRpcProbeFailures = 0;
            return;
        }
        if (rpcProbe || rpcWithinGrace) {
            consecutiveRpcProbeFailures = 0;
        } else {
            consecutiveRpcProbeFailures++;
        }
    }

    private boolean isRpcConsideredHealthy(boolean rpcProbe, boolean rpcWithinGrace) {
        Process process;
        synchronized (lifecycleLock) {
            process = rpcProcess;
        }
        if (process == null) {
            return false;
        }
        return rpcProbe
                || rpcWithinGrace
                || consecutiveRpcProbeFailures < HEALTH_CHECK_FAILURE_THRESHOLD;
    }

    private boolean rpcNeedsRestart(boolean rpcWithinGrace) {
        Process process;
        synchronized (lifecycleLock) {
            process = rpcProcess;
        }
        return process != null
                && !rpcWithinGrace
                && consecutiveRpcProbeFailures >= HEALTH_CHECK_FAILURE_THRESHOLD;
    }

    private void updateStorageProbeFailureCounter(boolean storageProbe, boolean storageWithinGrace, boolean storageProbeSkipped) {
        StorageServer server;
        synchronized (lifecycleLock) {
            server = storageServer;
        }
        if (server == null) {
            consecutiveStorageProbeFailures = 0;
            return;
        }
        if (storageProbe || storageWithinGrace || storageProbeSkipped) {
            consecutiveStorageProbeFailures = 0;
        } else {
            consecutiveStorageProbeFailures++;
        }
    }

    private boolean isStorageConsideredHealthy(boolean storageProbe, boolean storageWithinGrace) {
        StorageServer server;
        synchronized (lifecycleLock) {
            server = storageServer;
        }
        if (server == null) {
            return false;
        }
        return storageProbe
                || storageWithinGrace
                || consecutiveStorageProbeFailures < HEALTH_CHECK_FAILURE_THRESHOLD;
    }

    private boolean storageNeedsRestart(boolean storageWithinGrace) {
        StorageServer server;
        synchronized (lifecycleLock) {
            server = storageServer;
        }
        return server != null
                && !storageWithinGrace
                && consecutiveStorageProbeFailures >= HEALTH_CHECK_FAILURE_THRESHOLD;
    }

    private void updateRuntimeState(String state, boolean rpcHealthy, boolean storageHealthy) {
        if (lastRpcHealthy == null || lastRpcHealthy != rpcHealthy) {
            Timber.tag(LOG_TAG).i("RPC health changed: %s", rpcHealthy ? "healthy" : "unhealthy");
            lastRpcHealthy = rpcHealthy;
        }
        if (lastStorageHealthy == null || lastStorageHealthy != storageHealthy) {
            Timber.tag(LOG_TAG).i("Storage health changed: %s", storageHealthy ? "healthy" : "unhealthy");
            lastStorageHealthy = storageHealthy;
        }

        boolean announceEligible = rpcHealthy && storageHealthy;
        if (lastAnnounceEligible == null || lastAnnounceEligible != announceEligible) {
            Timber.tag(LOG_TAG).i("Tracker announce eligibility changed: %s", announceEligible ? "enabled" : "paused");
            lastAnnounceEligible = announceEligible;
        }

        if ("running".equals(state)) {
            lastHealthError = "";
        }

        String healthLabel;
        if ("degraded".equals(state)) {
            healthLabel = "Tracker unavailable";
        } else if ("starting".equals(state)) {
            healthLabel = "Starting";
        } else if ("unavailable".equals(state)) {
            healthLabel = "Unavailable";
        } else if (announceEligible) {
            healthLabel = "Healthy";
        } else if (!rpcHealthy && !storageHealthy) {
            healthLabel = "Recovering RPC + storage";
        } else if (!rpcHealthy) {
            healthLabel = "Recovering RPC";
        } else {
            healthLabel = "Recovering storage";
        }
        updateNotificationStatus(healthLabel + " on " + host + ":" + assignedPort + " (" + state + ")");
        notifyHealth(state, rpcHealthy, storageHealthy);
    }

    private void startStorageServer() throws IOException {
        synchronized (lifecycleLock) {
            if (isShuttingDown) {
                return;
            }
            stopStorageServerLocked();
            storageServer = new StorageServer(storagePort, storageDir);
            storageServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            storageStartedAtMs = System.currentTimeMillis();
            consecutiveStorageProbeFailures = 0;
            Timber.tag(LOG_TAG).i("Storage server started on port %d serving %s", storagePort, storageDir.getAbsolutePath());
        }
    }

    private void stopStorageServer() {
        synchronized (lifecycleLock) {
            stopStorageServerLocked();
        }
    }

    private void stopStorageServerLocked() {
        if (storageServer != null) {
            try {
                storageServer.stop();
                Timber.tag(LOG_TAG).i("Storage server stopped");
            } catch (Exception e) {
                Timber.tag(LOG_TAG).w(e, "Error while stopping storage server");
            } finally {
                storageServer = null;
                storageStartedAtMs = 0L;
                consecutiveStorageProbeFailures = 0;
            }
        }
    }

    private void restartStorageServer() throws IOException {
        synchronized (lifecycleLock) {
            if (!isRunning || isShuttingDown) {
                return;
            }
        }
        startStorageServer();
    }

    private void startRpcProcess(int threads) throws IOException {
        synchronized (lifecycleLock) {
            if (isShuttingDown) {
                return;
            }
            stopRpcProcessLocked();

            SettingsRepository settings = new SettingsRepository(this);
            boolean verboseRpcLogging = settings.isVerboseRpcLogging();
            Timber.tag(LOG_TAG).i(
                    "Starting RPC server process on %s:%d verbose=%s",
                    host,
                    assignedPort,
                    verboseRpcLogging);
            String executablePath = getApplicationInfo().nativeLibraryDir + "/librpc-server.so";
            ProcessBuilder pb = new ProcessBuilder(
                    executablePath,
                    "0.0.0.0",
                    String.valueOf(assignedPort),
                    String.valueOf(threads),
                    llamaCacheDir.getAbsolutePath()
            );
            pb.directory(getFilesDir());
            Map<String, String> env = pb.environment();
            env.put("HOME", getFilesDir().getAbsolutePath());
            env.put("TMPDIR", getCacheDir().getAbsolutePath());
            env.put("LLAMA_CACHE", llamaCacheDir.getAbsolutePath());
            if (verboseRpcLogging) {
                env.put("GGML_RPC_DEBUG", "1");
            }
            env.put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
            rpcStartedAtMs = System.currentTimeMillis();
            consecutiveRpcProbeFailures = 0;
            pb.redirectErrorStream(true);
            Timber.tag(LOG_TAG).i("RPC process cwd=%s cache=%s", getFilesDir().getAbsolutePath(), llamaCacheDir.getAbsolutePath());
            Timber.tag(LOG_TAG).d("RPC command: %s", pb.command());

            Process startedProcess = pb.start();
            rpcProcess = startedProcess;

            rpcProcessLoggerThread = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                String logTag = "LlamaRPC";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(startedProcess.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Timber.tag(logTag).d(line);
                    }
                } catch (IOException e) {
                    Timber.tag(logTag).e(e, "Error reading process stream");
                }
            }, "rpc-process-logger");
            rpcProcessLoggerThread.start();

            rpcProcessWatcherThread = new Thread(() -> watchRpcProcess(startedProcess), "rpc-process-watcher");
            rpcProcessWatcherThread.start();
        }
    }

    private void watchRpcProcess(Process watchedProcess) {
        try {
            int exitCode = watchedProcess.waitFor();
            Thread loggerThread = rpcProcessLoggerThread;
            if (loggerThread != null) {
                loggerThread.join(500);
            }
            synchronized (lifecycleLock) {
                if (watchedProcess != rpcProcess) {
                    Timber.tag(LOG_TAG).i("Ignoring exit from superseded RPC process with code %d", exitCode);
                    return;
                }
                rpcProcess = null;
                rpcProcessLoggerThread = null;
                rpcProcessWatcherThread = null;
                if (!isShuttingDown) {
                    notifyState(discoveryEnabled ? UiState.SEARCHING : UiState.IDLE);
                    Timber.tag(LOG_TAG).w("RPC server process exited unexpectedly with code %d", exitCode);
                    setLastHealthError("RPC server process exited unexpectedly with code " + exitCode);
                    updateRuntimeState("recovering", false, lastStorageHealthy != null && lastStorageHealthy);
                } else {
                    Timber.tag(LOG_TAG).i("RPC server process exited during shutdown with code %d", exitCode);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Timber.tag(LOG_TAG).e(t, "RPC watcher crashed");
        }
    }

    private void stopRpcProcess() {
        synchronized (lifecycleLock) {
            stopRpcProcessLocked();
        }
    }

    private void stopRpcProcessLocked() {
        Process process = rpcProcess;
        rpcProcess = null;
        rpcProcessLoggerThread = null;
        rpcProcessWatcherThread = null;
        if (process == null) {
            return;
        }

        rpcStartedAtMs = 0L;
        consecutiveRpcProbeFailures = 0;
        process.destroy();
        try {
            if (!waitForProcessExit(process, SHUTDOWN_WAIT_MS)) {
                Timber.tag(LOG_TAG).w("RPC process did not exit after destroy(); forcing termination");
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        Timber.tag(LOG_TAG).i("RPC process stopped");
    }

    private void restartRpcProcess(int threads) throws IOException {
        synchronized (lifecycleLock) {
            if (!isRunning || isShuttingDown) {
                return;
            }
        }
        startRpcProcess(threads);
    }

    /**
     * RPC liveness is based on the child {@code librpc-server.so} process only.
     * TCP connect probes are intentionally omitted: ggml-rpc serves one client at a
     * time and does not accept new connections while inference is running, so a port
     * check would falsely mark a busy worker as unhealthy.
     */
    private boolean isRpcHealthy() {
        Process process;
        synchronized (lifecycleLock) {
            process = rpcProcess;
        }
        if (process == null) {
            return false;
        }
        boolean alive = processIsAlive(process);
        if (!alive) {
            Timber.tag(LOG_TAG).w("RPC process is not running");
        }
        return alive;
    }

    private boolean probeStorageHttp() {
        URL url;
        try {
            url = new URL("http://127.0.0.1:" + storagePort + "/storage_info");
        } catch (Exception e) {
            Timber.tag(LOG_TAG).e(e, "Invalid storage health URL");
            return false;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HEALTH_CHECK_TIMEOUT_MS);
            conn.setReadTimeout(HEALTH_CHECK_TIMEOUT_MS);
            int responseCode = conn.getResponseCode();
            return responseCode == HttpURLConnection.HTTP_OK;
        } catch (IOException e) {
            Timber.tag(LOG_TAG).w(e, "Storage health check failed on port %d", storagePort);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Used when the supervisor is not running (e.g. error handlers). */
    private boolean isStorageHealthy() {
        if (isStorageProbeSkipped()) {
            return storageServer != null;
        }
        boolean probe = probeStorageHttp();
        return isStorageConsideredHealthy(probe, isStorageWithinStartupGrace());
    }

    private void shutdownService() {
        synchronized (lifecycleLock) {
            isRunning = false;
            isShuttingDown = true;
        }
        stopForeground(true);
        stopSelf();
    }

    private long estimateUsableMemoryBytes() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            Timber.tag(LOG_TAG).w("ActivityManager unavailable; advertising unknown memory capacity");
            return 0;
        }

        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        long advertisedBytes = Math.max(0L, memoryInfo.availMem);

        Timber.tag(LOG_TAG).d("Discovery memory estimate: totalMem=%d availMem=%d threshold=%d lowMemory=%s advertised=%d",
                memoryInfo.totalMem,
                memoryInfo.availMem,
                memoryInfo.threshold,
                memoryInfo.lowMemory,
                advertisedBytes);

        return advertisedBytes;
    }

    private float readBatteryPercent() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = batteryIntent != null ? batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
        int scale = batteryIntent != null ? batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
        if (level == -1 || scale <= 0) {
            return -1.0f;
        }
        return (level / (float) scale) * 100.0f;
    }

    private float readBatteryTemperatureC() {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int tempTenths = batteryIntent != null ? batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
        return tempTenths != -1 ? tempTenths / 10.0f : -1.0f;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Timber.tag(LOG_TAG).i("Service destroyed. Requesting process stop...");
        notifyState(UiState.IDLE);
        lastHealthError = "";
        HealthSnapshot idleSnapshot = new HealthSnapshot("idle", "", false, false, false);
        currentHealth = idleSnapshot;
        HealthListener listener = healthListener;
        if (listener != null) {
            new Handler(Looper.getMainLooper()).post(() -> listener.onHealthChanged(idleSnapshot));
        }
        synchronized (lifecycleLock) {
            isRunning = false;
            isShuttingDown = true;
        }
        if (discoveryThread != null) {
            discoveryThread.interrupt();
        }
        stopRpcProcess();
        stopStorageServer();
        stopForeground(true);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void setLastHealthError(String message) {
        lastHealthError = message == null ? "" : message.trim();
    }

    private boolean processIsAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    private boolean waitForProcessExit(Process process, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!processIsAlive(process)) {
                return true;
            }
            Thread.sleep(25);
        }
        return !processIsAlive(process);
    }

    private static String describeError(Exception error, String fallback) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return fallback;
        }
        return fallback + ": " + message.trim();
    }
}

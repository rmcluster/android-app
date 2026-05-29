package com.llama.rpcapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.BatteryManager;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.util.Log;
import java.io.File;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.Map;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import fi.iki.elonen.NanoHTTPD;
import timber.log.Timber;

public class ServerService extends Service {
    private static final String LOG_TAG = "ServerService";
    private static final int DEFAULT_PORT = 47671;
    private static final int DEFAULT_THREADS = 4;
    private Thread serverThread;
    private Process rpcProcess;
    private Thread discoveryThread;
    private StorageServer storageServer;
    private volatile boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    "RpcServerChannel",
                    "RPC Server Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SettingsRepository settings = new SettingsRepository(this);
        ServerConfig baseConfig = settings.loadConfig();

        String discoveryIp = baseConfig.discoveryIp;
        int discoveryPort = baseConfig.discoveryPort;
        String discoveryToken = baseConfig.discoveryToken;
        int threads = baseConfig.threads;
        String nodeId = baseConfig.nodeId;
        boolean hasDiscoveryIp = !discoveryIp.isEmpty();

        int assignedPort = findAvailablePort(DEFAULT_PORT);
        int storagePort = findAvailablePort(assignedPort + 1);
        
        settings.saveConfig(new ServerConfig(
                nodeId,
                assignedPort,
                storagePort,
                discoveryIp,
                discoveryPort,
                discoveryToken,
                threads
        ));
        File storageDir;
        try {
            storageDir = getStorageDirectory("StorageApp");
        } catch (IllegalStateException e) {
            Timber.tag(LOG_TAG).e(e, "Failed to initialize storage directory");
            stopSelf();
            return START_NOT_STICKY;
        }
        storageServer = new StorageServer(storagePort, storageDir);
        boolean advertiseStorage = false;
        try {
            //default timeout, 5 seconds
            storageServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            advertiseStorage = true;
            Timber.tag(LOG_TAG).i("Storage server started on port %d serving %s", storagePort, storageDir.getAbsolutePath());
        } catch (Exception e) {
            Timber.tag(LOG_TAG).e(e, "Failed to start storage server");
        }

        if (hasDiscoveryIp) {
            startDiscoveryPing(discoveryIp, discoveryPort, discoveryToken, assignedPort, advertiseStorage ? storagePort : 0, nodeId);
        } else {
            Timber.tag(LOG_TAG).i("No discovery IP configured, no pings");
        }

        String host = getLocalIpAddress();
        Notification notification = new NotificationCompat.Builder(this, "RpcServerChannel")
                .setContentTitle("RMCluster Node")
                .setContentText("Running on " + host + ":" + assignedPort)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }

        serverThread = new Thread(() -> {
            try {
                Timber.tag(LOG_TAG).i("Starting RPC server process on %s:%d", host, assignedPort);

                File llamaCacheDir = ensureDirectory(new File(getCacheDir(), "llama.cpp"));
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
                env.put("GGML_RPC_DEBUG", "1");
                env.put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
                pb.redirectErrorStream(true);
                Timber.tag(LOG_TAG).i("RPC process cwd=%s cache=%s", getFilesDir().getAbsolutePath(), llamaCacheDir.getAbsolutePath());
                Timber.tag(LOG_TAG).d("RPC command: %s", pb.command());
                rpcProcess = pb.start();            
                
                Thread loggingThread = new Thread(() -> {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    String logTag = "LlamaRPC";
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(rpcProcess.getInputStream()))) { 
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Timber.tag(logTag).d(line);
                        }
                    } catch (IOException e) {
                        Timber.tag(logTag).e(e, "Error reading process stream");
                    }
                }, "rpc-process-logger");
                loggingThread.start();
                int exitCode = rpcProcess.waitFor();
                loggingThread.join(500);
                Timber.tag(LOG_TAG).i("RPC server process exited with code %d", exitCode);
            } catch (Throwable t) {
                Timber.tag(LOG_TAG).e(t, "FATAL: RPC server process crashed");
            } finally {
                isRunning = false;
                stopForeground(true);
                stopSelf();
            }
        });
        serverThread.start();

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

        throw new IllegalStateException(
                "Failed to create storage directory at " + fallbackFolder.getAbsolutePath()
        );
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
        } catch (java.io.IOException e) {
            try {
                int assignedPort = tryBindPort(0);
                Timber.tag(LOG_TAG).w("Port %d was occupied. Dynamically bound to %d", requestedPort, assignedPort);
                return assignedPort;
            } catch (java.io.IOException ex) {
                Timber.tag(LOG_TAG).e(ex, "Could not find a free port");
                return requestedPort;
            }
        }
    }

    private int tryBindPort(int port) throws java.io.IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new java.net.InetSocketAddress("0.0.0.0", port));
            return socket.getLocalPort();
        }
    }

    private String getLocalIpAddress() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                java.net.NetworkInterface intf = en.nextElement();
                if (!intf.getName().contains("wlan")) continue;
                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
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

    private void startDiscoveryPing(String targetIp, int targetPort, String discoveryToken, int servicePort, int storagePort, String nodeId) {
        isRunning = true;
        discoveryThread = new Thread(() -> {
            try {
                // Give the native server a moment to bind to the port
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            while (isRunning) {
                try {
                    String model = Build.MODEL;
                    long maxSize = estimateUsableMemoryBytes();
                    float battery = readBatteryPercent();
                    float temperature = readBatteryTemperatureC();

                    String localIp = getLocalIpAddress();
                    String urlString = "http://" + targetIp + ":" + targetPort
                            + "/announce?id=" + nodeId
                            + "&port=" + servicePort
                            + "&storage_port=" + storagePort
                            + "&ip=" + localIp
                            + "&model=" + URLEncoder.encode(model, "UTF-8")
                            + "&max_size=" + maxSize
                            + "&battery=" + battery
                            + "&temperature=" + temperature;
                    if (!discoveryToken.isEmpty()) {
                        urlString += "&token=" + URLEncoder.encode(discoveryToken, "UTF-8");
                    }

                    java.net.URL url = new java.net.URL(urlString);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    try {
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        int responseCode = conn.getResponseCode();
                        if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                            Timber.tag(LOG_TAG).e("Failed to announce to tracker, response code: %d", responseCode);
                            Thread.sleep(1000);
                            continue;
                        }
                        try (java.io.InputStream in = conn.getInputStream();
                             java.util.Scanner scanner = new java.util.Scanner(in).useDelimiter("\\A")) {
                            String responseBody = scanner.hasNext() ? scanner.next() : "";
                            int interval = new org.json.JSONObject(responseBody).getInt("interval");
                            Timber.tag(LOG_TAG).d("Announced to tracker, reannouncing in %d seconds", interval);
                            Thread.sleep(interval * 1000L);
                        }
                    } finally {
                        conn.disconnect();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Timber.tag(LOG_TAG).e(e, "Error in discovery thread");
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        discoveryThread.start();
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
        isRunning = false;
        if (discoveryThread != null) {
            discoveryThread.interrupt();
        }
        if (rpcProcess != null) {
            rpcProcess.destroy();
        }
        if (storageServer != null) {
            storageServer.stop();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

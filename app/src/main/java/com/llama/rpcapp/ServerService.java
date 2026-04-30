package com.llama.rpcapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.BatteryManager;
import android.content.IntentFilter;
import android.util.Log;
import android.content.pm.ServiceInfo;
import android.os.Environment;
import java.io.File;
import java.net.URLEncoder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import fi.iki.elonen.NanoHTTPD;

public class ServerService extends Service {
    private static final String LOG_TAG = "ServerService";
    private Thread serverThread;
    private Process rpcProcess;
    private NativeRpcServer nativeServer;
    private Thread discoveryThread;
    private StorageServer storageServer;
    private volatile boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        nativeServer = new NativeRpcServer();
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

        String host = baseConfig.host;
        int requestedPort = baseConfig.port;
        String discoveryIp = baseConfig.discoveryIp;
        int discoveryPort = baseConfig.discoveryPort;
        int threads = baseConfig.threads;
        boolean hasDiscoveryIp = !discoveryIp.isEmpty();

        int assignedPort = findAvailablePort(requestedPort);
        int storagePort = findAvailablePort(assignedPort + 1);
        
        settings.saveConfig(new ServerConfig(
                host,
                assignedPort,
                storagePort,
                discoveryIp,
                discoveryPort,
                threads
        ));
        storageServer = new StorageServer(storagePort, getFilesystemPath("StorageApp")); 
        try {
            //default timeout, 5 seconds
            storageServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.i(LOG_TAG, "Storage server started on port " + storagePort);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to start storage server", e);
        }

        if (hasDiscoveryIp) {
            startDiscoveryPing(discoveryIp, discoveryPort, assignedPort, storagePort);
        } else {
            Log.i(LOG_TAG, "No discovery IP configured, no pings");
        }

        String displayHost = host.equals("0.0.0.0") ? getLocalIpAddress() : host;

        Notification notification = new NotificationCompat.Builder(this, "RpcServerChannel")
                .setContentTitle("Llama RPC Server")
                .setContentText("Running on " + displayHost + ":" + assignedPort)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }

        serverThread = new Thread(() -> {
            try {
                Log.i(LOG_TAG, "Starting RPC server process on " + host + ":" + assignedPort);

                String cacheDir = getCacheDir().getAbsolutePath(); 
                String executablePath = getApplicationInfo().nativeLibraryDir + "/librpc-server.so";
                ProcessBuilder pb = new ProcessBuilder(
                    executablePath,
                    host,
                    String.valueOf(assignedPort),
                    String.valueOf(threads),
                    cacheDir
                );
                pb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
                pb.redirectErrorStream(true);
                rpcProcess = pb.start();
                
                int exitCode = rpcProcess.waitFor();
                Log.i(LOG_TAG, "RPC server process exited with code " + exitCode);
            } catch (Throwable t) {
                Log.e(LOG_TAG, "FATAL: RPC server process crashed", t);
            } finally {
                isRunning = false;
                stopForeground(true);
                stopSelf();
            }
        });
        serverThread.start();

        return START_NOT_STICKY;
    }

    private File getFilesystemPath(String folderName) {
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), folderName);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    private int findAvailablePort(int requestedPort) {
        try {
            tryBindPort(requestedPort);
            return requestedPort;
        } catch (java.io.IOException e) {
            try {
                int assignedPort = tryBindPort(0);
                Log.w(LOG_TAG, "Port " + requestedPort + " was occupied. Dynamically bound to " + assignedPort);
                return assignedPort;
            } catch (java.io.IOException ex) {
                Log.e(LOG_TAG, "Could not find a free port", ex);
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
                        Log.i(LOG_TAG, "Found IP via NetworkInterface (" + intf.getName() + "): " + inetAddress.getHostAddress());
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(LOG_TAG, "IP Address error", ex);
        }
        return "0.0.0.0";
    }

    private void startDiscoveryPing(String targetIp, int targetPort, int servicePort, int storagePort) {
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
                    long maxSize = nativeServer != null ? nativeServer.getMaxSize() : 0;
                    float battery = readBatteryPercent();
                    float temperature = readBatteryTemperatureC();

                    String localIp = getLocalIpAddress();
                    String urlString = "http://" + targetIp + ":" + targetPort 
                            + "/announce?port=" + servicePort
                            + "&storage_port=" + storagePort
                            + "&ip=" + localIp
                            + "&model=" + URLEncoder.encode(model, "UTF-8")
                            + "&max_size=" + maxSize
                            + "&battery=" + battery
                            + "&temperature=" + temperature;

                    java.net.URL url = new java.net.URL(urlString);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    try {
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(5000);
                        int responseCode = conn.getResponseCode();
                        if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                            Log.e(LOG_TAG, "Failed to announce to tracker, response code: " + responseCode);
                            Thread.sleep(1000);
                            continue;
                        }
                        try (java.io.InputStream in = conn.getInputStream();
                             java.util.Scanner scanner = new java.util.Scanner(in).useDelimiter("\\A")) {
                            String responseBody = scanner.hasNext() ? scanner.next() : "";
                            int interval = new org.json.JSONObject(responseBody).getInt("interval");
                            Log.d(LOG_TAG, "Announced to tracker, reannouncing in " + interval + " seconds");
                            Thread.sleep(interval * 1000L);
                        }
                    } finally {
                        conn.disconnect();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error in discovery thread", e);
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
        Log.i(LOG_TAG, "Service destroyed. Requesting process stop...");
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

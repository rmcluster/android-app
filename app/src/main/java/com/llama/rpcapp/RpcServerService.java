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
import java.net.URLEncoder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class RpcServerService extends Service {
    private static final String TAG = "RpcServerService";
    private static final String CHANNEL_ID = "RpcServerChannel";
    private Thread serverThread;
    private NativeRpcServer nativeServer;

    @Override
    public void onCreate() {
        super.onCreate();
        nativeServer = new NativeRpcServer();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String host = intent.getStringExtra("host") != null ? intent.getStringExtra("host") : "0.0.0.0";
        int requestedPort = intent.getIntExtra("port", 50052);
        
        // Find a free IPv4 port if the requested one is occupied
        int assignedPort = requestedPort;
        try {
            java.net.ServerSocket s = new java.net.ServerSocket();
            s.setReuseAddress(false);
            s.bind(new java.net.InetSocketAddress("0.0.0.0", requestedPort));
            s.close();
        } catch (java.io.IOException e) {
            try {
                java.net.ServerSocket s = new java.net.ServerSocket();
                s.setReuseAddress(false);
                s.bind(new java.net.InetSocketAddress("0.0.0.0", 0));
                assignedPort = s.getLocalPort();
                s.close();
                Log.w(TAG, "Port " + requestedPort + " was occupied. Dynamically bound to " + assignedPort);
            } catch (java.io.IOException ex) {
                Log.e(TAG, "Could not find a free port", ex);
            }
        }
        
        final int finalPort = assignedPort;

        final String discoveryIp = intent.getStringExtra("discoveryIp");
        final int discoveryPort = intent.getIntExtra("discoveryPort", 50055);
        final int threads = intent.getIntExtra("threads", 4);

        if (discoveryIp == null) {
            Log.w(TAG, "No discoveryIp provided. Discovery ping will not start.");
        }

        final String displayHost = host.equals("0.0.0.0") ? getLocalIpAddress() : host;

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Llama RPC Server")
                .setContentText("Running on " + displayHost + ":" + finalPort)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }

        serverThread = new Thread(() -> {
            try {
                Log.i(TAG, "Starting RPC server thread on " + host + ":" + finalPort);
                if (discoveryIp != null) {
                    startDiscoveryPing(discoveryIp, discoveryPort, finalPort);
                }
                String cacheDir = getCacheDir().getAbsolutePath();
                nativeServer.startServer(host, finalPort, threads, cacheDir);
                Log.i(TAG, "RPC server thread finished.");
            } catch (Throwable t) {
                Log.e(TAG, "FATAL: RPC server thread crashed", t);
            } finally {
                isRunning = false;
                stopForeground(true);
                stopSelf();
            }
        });
        serverThread.start();

        return START_NOT_STICKY;
    }

    private String getLocalIpAddress() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                java.net.NetworkInterface intf = en.nextElement();
                if (!intf.getName().contains("wlan")) continue;
                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        Log.i(TAG, "Found IP via NetworkInterface (" + intf.getName() + "): " + inetAddress.getHostAddress());
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "IP Address error", ex);
        }
        return "0.0.0.0";
    }

    //discovery logic
    private Thread discoveryThread;
    private volatile boolean isRunning = false;

    private void startDiscoveryPing(String targetIp, int targetPort, int servicePort) {
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
                    
                    Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    int level = batteryIntent != null ? batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) : -1;
                    int scale = batteryIntent != null ? batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) : -1;
                    float battery = -1.0f;
                    if (level != -1 && scale != -1) {
                        battery = (level / (float)scale) * 100.0f;
                    }
                    int tempTenths = batteryIntent != null ? batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
                    float temperature = tempTenths != -1 ? tempTenths / 10.0f : -1.0f;

                    String localIp = getLocalIpAddress();
                    String urlString = "http://" + targetIp + ":" + targetPort + "/announce?port=" + servicePort
                            + "&ip=" + localIp
                            + "&model=" + URLEncoder.encode(model, "UTF-8")
                            + "&max_size=" + maxSize
                            + "&battery=" + battery
                            + "&temperature=" + temperature;

                    java.net.URL url = new java.net.URL(urlString);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);

                    int responseCode = conn.getResponseCode();
                    if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                        java.io.InputStream in = conn.getInputStream();
                        java.util.Scanner scanner = new java.util.Scanner(in).useDelimiter("\\A");
                        String responseBody = scanner.hasNext() ? scanner.next() : "";
                        org.json.JSONObject json = new org.json.JSONObject(responseBody);
                        int interval = json.getInt("interval");
                        Log.d(TAG, "Announced to tracker, reannouncing in " + interval + " seconds");
                        Thread.sleep(interval * 1000L);
                    } else {
                        Log.e(TAG, "Failed to announce to tracker, response code: " + responseCode);
                        Thread.sleep(1000);
                    }
                    conn.disconnect();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in discovery thread", e);
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service destroyed. Requesting native server stop...");
        isRunning = false;
        if (discoveryThread != null) {
            discoveryThread.interrupt();
        }
        if (nativeServer != null) {
            nativeServer.stopServer();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "RPC Server Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
}

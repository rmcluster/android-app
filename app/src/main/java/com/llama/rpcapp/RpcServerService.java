package com.llama.rpcapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

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
        final String host = intent.getStringExtra("host") != null ? intent.getStringExtra("host") : "0.0.0.0";        final int port = intent.getIntExtra("port", 50052);
        final String discoveryIp = intent.getStringExtra("discoveryIp");
        final int discoveryPort = intent.getIntExtra("discoveryPort", 50055);
        final int threads = intent.getIntExtra("threads", 4);

        if (discoveryIp == null) {
            Log.w(TAG, "No discoveryIp provided. Discovery ping will not start.");
        }

        final String displayHost = host.equals("0.0.0.0") ? getLocalIpAddress() : host;

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Llama RPC Server")
                .setContentText("Running on " + displayHost + ":" + port)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();

        startForeground(1, notification);

        serverThread = new Thread(() -> {
            try {
                Log.i(TAG, "Starting RPC server thread on " + host + ":" + port);
                if (discoveryIp != null) {
                    startDiscoveryPing(discoveryIp, discoveryPort, port);
                }
                nativeServer.startServer(host, port, threads);
                Log.i(TAG, "RPC server thread finished.");
            } catch (Throwable t) {
                Log.e(TAG, "FATAL: RPC server thread crashed", t);
            } finally {
                isRunning = false;
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
            String urlString = "http://" + targetIp + ":" + targetPort + "/announce?port=" + servicePort;
            while (isRunning) {
                try {
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
        Log.i(TAG, "Service destroyed. Note: ggml-rpc server might not stop cleanly without process kill.");
        isRunning = false;
        if (discoveryThread != null) {
            discoveryThread.interrupt();
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

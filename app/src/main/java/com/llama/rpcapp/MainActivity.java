package com.llama.rpcapp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private TextView tvIpAddress;
    private EditText etPort, etStoragePort, etThreads, etHost, etDiscoveryIp, etDiscoveryPort;
    private Button btnStart, btnStop, btnScanQr;
    private SettingsRepository settings;
    private static final String TAG = "MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new SettingsRepository(this);

        tvIpAddress = findViewById(R.id.tvIpAddress);
        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        etStoragePort = findViewById(R.id.etStoragePort);
        etDiscoveryIp = findViewById(R.id.etDiscoveryIp);
        etDiscoveryPort = findViewById(R.id.etDiscoveryPort);
        etThreads = findViewById(R.id.etThreads);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnScanQr = findViewById(R.id.btnScanQr);

        // Load saved settings
        loadSettings();

        String ip = getWifiIpAddress();
        tvIpAddress.setText(String.format("IP Address: %s", ip));

        btnStart.setOnClickListener(v -> {
            saveSettings();
            startRpcService();
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, ServerService.class));
            setServerUiState(false);
        });

        btnScanQr.setOnClickListener(v -> {
            startQrScanner();
        });


        if (getIntent().getBooleanExtra("autoStart", false)) {
            startRpcService();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ServerConfig config = settings.loadConfig();
        etPort.setText(String.valueOf(config.port));
        etStoragePort.setText(String.valueOf(config.storagePort));
    }

    private void loadSettings() {
        ServerConfig config = settings.loadConfig();
        etHost.setText(config.host);
        etPort.setText(String.valueOf(config.port));
        etStoragePort.setText(String.valueOf(config.storagePort));
        etDiscoveryIp.setText(config.discoveryIp);
        etDiscoveryPort.setText(String.valueOf(config.discoveryPort));
        etThreads.setText(String.valueOf(config.threads));
    }

    private void saveSettings() {
        try {
            ServerConfig config = new ServerConfig(
                    etHost.getText().toString(),
                    Integer.parseInt(etPort.getText().toString()),
                    Integer.parseInt(etStoragePort.getText().toString()),
                    etDiscoveryIp.getText().toString(),
                    Integer.parseInt(etDiscoveryPort.getText().toString()),
                    Integer.parseInt(etThreads.getText().toString())
            );
            settings.saveConfig(config);
        } catch (NumberFormatException e) {
            Log.e("MainActivity", "Failed to save settings: invalid number format", e);
        }
    }



    private void parseUri(Uri uri) {
        Log.d(TAG, "Parsing URI: " + uri.toString());
        if ("rmcluster".equals(uri.getScheme()) && "connect".equals(uri.getHost())) {
            String url = uri.getQueryParameter("url");
            String port = uri.getQueryParameter("port");
            if (url != null) etDiscoveryIp.setText(url);
            if (port != null) etDiscoveryPort.setText(port);
            
            Toast.makeText(this, "Connected to: " + url + ":" + port, Toast.LENGTH_SHORT).show();

            //can also parse token if we end up using it
        }
    }

    private void startQrScanner() {
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build();

        GmsBarcodeScanning.getClient(this, options).startScan().addOnSuccessListener(barcode -> {
                    if (barcode.getRawValue() != null) {
                        parseUri(Uri.parse(barcode.getRawValue()));
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Scan failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void startRpcService() {
        Intent serviceIntent = new Intent(this, ServerService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        setServerUiState(true);
    }

    private void setServerUiState(boolean running) {
        btnStart.setEnabled(!running);
        btnStart.setText(running ? "SERVER RUNNING" : "START SERVER");
        btnStop.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    private String getWifiIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ipInt = wifiInfo.getIpAddress();
        return Formatter.formatIpAddress(ipInt);
    }
}

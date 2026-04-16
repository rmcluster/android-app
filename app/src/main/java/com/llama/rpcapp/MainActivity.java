package com.llama.rpcapp;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private TextView tvIpAddress;
    private EditText etPort, etThreads, etHost, etDiscoveryIp, etDiscoveryPort;
    private Button btnStart, btnStop;
    private SettingsRepository settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new SettingsRepository(this);

        tvIpAddress = findViewById(R.id.tvIpAddress);
        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        etDiscoveryIp = findViewById(R.id.etDiscoveryIp);
        etDiscoveryPort = findViewById(R.id.etDiscoveryPort);
        etThreads = findViewById(R.id.etThreads);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        // Load saved settings
        loadSettings();

        String ip = getWifiIpAddress();
        tvIpAddress.setText(String.format("IP Address: %s", ip));

        btnStart.setOnClickListener(v -> {
            saveSettings();
            startRpcService();
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, RpcServerService.class));
            setServerUiState(false);
        });

        if (getIntent().getBooleanExtra("autoStart", false)) {
            startRpcService();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        etPort.setText(String.valueOf(settings.loadConfig().port));
    }

    private void loadSettings() {
        ServerConfig config = settings.loadConfig();
        etHost.setText(config.host);
        etPort.setText(String.valueOf(config.port));
        etDiscoveryIp.setText(config.discoveryIp);
        etDiscoveryPort.setText(String.valueOf(config.discoveryPort));
        etThreads.setText(String.valueOf(config.threads));
    }

    private void saveSettings() {
        try {
            ServerConfig config = new ServerConfig(
                    etHost.getText().toString(),
                    Integer.parseInt(etPort.getText().toString()),
                    etDiscoveryIp.getText().toString(),
                    Integer.parseInt(etDiscoveryPort.getText().toString()),
                    Integer.parseInt(etThreads.getText().toString())
            );
            settings.saveConfig(config);
        } catch (NumberFormatException e) {
            Log.e("MainActivity", "Failed to save settings: invalid number format", e);
        }
    }

    private void startRpcService() {
        Intent serviceIntent = new Intent(this, RpcServerService.class);
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

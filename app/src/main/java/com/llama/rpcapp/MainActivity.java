package com.llama.rpcapp;

import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private TextView tvIpAddress;
    private EditText etPort, etThreads, etHost, etDiscoveryIp, etDiscoveryPort;
    private Button btnStart, btnStop;
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvIpAddress = findViewById(R.id.tvIpAddress);
        etHost = findViewById(R.id.etHost);
        etPort = findViewById(R.id.etPort);
        etPort.setText("50052");
        etDiscoveryIp = findViewById(R.id.etDiscoveryIp);
        etDiscoveryPort = findViewById(R.id.etDiscoveryPort);
        etThreads = findViewById(R.id.etThreads);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        String ip = getWifiIpAddress();
        tvIpAddress.setText(String.format("IP Address: %s", ip));

        btnStart.setOnClickListener(v -> {
            startRpcService();
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, RpcServerService.class));
            btnStart.setEnabled(true);
            btnStart.setText("START");
            btnStop.setVisibility(View.GONE);
            isRunning = false;
        });

        if (getIntent().getBooleanExtra("autoStart", false)) {
            startRpcService();
        }
    }

    private void startRpcService() {
        String host = etHost.getText().toString();
        int port = Integer.parseInt(etPort.getText().toString());
        String discoveryIp = etDiscoveryIp.getText().toString();
        int discoveryPort = Integer.parseInt(etDiscoveryPort.getText().toString());
        int threads = Integer.parseInt(etThreads.getText().toString());

        Intent serviceIntent = new Intent(this, RpcServerService.class);
        serviceIntent.putExtra("host", host);
        serviceIntent.putExtra("port", port);
        serviceIntent.putExtra("discoveryIp", discoveryIp);
        serviceIntent.putExtra("discoveryPort", discoveryPort);
        serviceIntent.putExtra("threads", threads);

        ContextCompat.startForegroundService(this, serviceIntent);
        
        btnStart.setEnabled(false);
        btnStart.setText("SERVER RUNNING");
        btnStop.setVisibility(View.VISIBLE);
        isRunning = true;
    }

    private String getWifiIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        int ipInt = wifiInfo.getIpAddress();
        return Formatter.formatIpAddress(ipInt);
    }
}

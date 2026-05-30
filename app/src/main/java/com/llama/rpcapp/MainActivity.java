package com.llama.rpcapp;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import timber.log.Timber;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final long SCAN_TIMEOUT_MS = 45_000L;

    private final Handler scanTimeoutHandler = new Handler(Looper.getMainLooper());
    private final AppLogStore.Listener logListener = text ->
            runOnUiThread(() -> updateLogs(text, true));

    private TextView tvLogs;
    private ScrollView logScrollView;
    private EditText etThreads, etDiscoveryIp, etDiscoveryPort, etNickname;
    private Button btnStart, btnStop, btnScanQr;
    private SettingsRepository settings;
    private String discoveryToken = "";
    private Runnable scanTimeoutRunnable;
    private boolean scanInProgress = false;
    private boolean scanTimedOut = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new SettingsRepository(this);

        tvLogs = findViewById(R.id.logTextView);
        logScrollView = findViewById(R.id.logScrollView);
        etDiscoveryIp = findViewById(R.id.etDiscoveryIp);
        etDiscoveryPort = findViewById(R.id.etDiscoveryPort);
        etNickname = findViewById(R.id.etNickname);
        etThreads = findViewById(R.id.etThreads);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnScanQr = findViewById(R.id.btnScanQr);

        loadSettings();
        btnStart.setOnClickListener(v -> {
            saveSettings();
            startRpcService();
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, ServerService.class));
            setServerUiState(false);
        });

        btnScanQr.setOnClickListener(v -> startQrScanner());
        updateLogs(AppLogStore.getInstance().snapshotText(), false);

        if (getIntent().getBooleanExtra("autoStart", false)) {
            startRpcService();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ServerConfig config = settings.loadConfig();
        etDiscoveryIp.setText(config.discoveryIp);
        etDiscoveryPort.setText(String.valueOf(config.discoveryPort));
        etNickname.setText(config.nickname);
        etThreads.setText(String.valueOf(config.threads));
        AppLogStore.getInstance().addListener(logListener);
        updateLogs(AppLogStore.getInstance().snapshotText(), false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            clearScanTimeout();
            if (scanTimedOut) {
                scanTimedOut = false;
                Toast.makeText(this, "Scan failed", Toast.LENGTH_LONG).show();
            } else if (result.getContents() == null) {
                Toast.makeText(this, "Scan failed", Toast.LENGTH_LONG).show();
            } else {
                parseUri(Uri.parse(result.getContents()));
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        clearScanTimeout();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        AppLogStore.getInstance().removeListener(logListener);
        super.onPause();
    }

    private void loadSettings() {
        ServerConfig config = settings.loadConfig();
        etDiscoveryIp.setText(config.discoveryIp);
        etDiscoveryPort.setText(String.valueOf(config.discoveryPort));
        discoveryToken = config.discoveryToken;
        etNickname.setText(config.nickname);
        etThreads.setText(String.valueOf(config.threads));
    }

    private void saveSettings() {
        try {
            ServerConfig oldConfig = settings.loadConfig();
            ServerConfig config = new ServerConfig(
                    oldConfig.nodeId,
                    oldConfig.port,
                    oldConfig.storagePort,
                    etDiscoveryIp.getText().toString(),
                    Integer.parseInt(etDiscoveryPort.getText().toString()),
                    discoveryToken,
                    etNickname.getText().toString(),
                    Integer.parseInt(etThreads.getText().toString())
            );
            settings.saveConfig(config);
        } catch (NumberFormatException e) {
            Timber.tag(TAG).e(e, "Failed to save settings: invalid number format");
        }
    }

    private void parseUri(Uri uri) {
        Timber.tag(TAG).d("Parsing URI: %s", uri);
        if (!"rmcluster".equals(uri.getScheme()) || !"connect".equals(uri.getHost())) {
            Toast.makeText(this, "QR code is not a cluster connection code", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String url = uri.getQueryParameter("url");
            String port = uri.getQueryParameter("port");
            String token = uri.getQueryParameter("token");
            if (url != null) etDiscoveryIp.setText(url);
            if (port != null) etDiscoveryPort.setText(port);
            discoveryToken = token != null ? token : "";

            Toast.makeText(this, "Connected to: " + url + ":" + port, Toast.LENGTH_SHORT).show();
            saveSettings();
        } catch (Exception e) {
            Toast.makeText(this, "Scanned QR code is invalid", Toast.LENGTH_LONG).show();
        }
    }

    private void startQrScanner() {
        clearScanTimeout();
        scanTimedOut = false;

        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build();

        GmsBarcodeScanning.getClient(this, options)
                .startScan()
                .addOnSuccessListener(barcode -> {
                    if (scanTimedOut) {
                        return;
                    }

                    clearScanTimeout();
                    if (barcode.getRawValue() != null) {
                        parseUri(Uri.parse(barcode.getRawValue()));
                    }
                })
                .addOnCanceledListener(this::clearScanTimeout)
                .addOnFailureListener(e -> {
                    if (scanTimedOut) {
                        return;
                    }

                    Timber.tag(TAG).w(e, "Play Services scanner unavailable, falling back to ZXing");
                    Toast.makeText(this, "Play Services scanner unavailable, using fallback scanner", Toast.LENGTH_LONG).show();
                    startZxingScanner();
                });
    }

    private void startZxingScanner() {
        beginScanTimeout();
        Toast.makeText(this, "Using fallback scanner", Toast.LENGTH_SHORT).show();
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Scan a cluster QR code");
        integrator.setCameraId(0);
        integrator.setBeepEnabled(true);
        integrator.setBarcodeImageEnabled(false);
        integrator.initiateScan();
    }

    private void beginScanTimeout() {
        clearScanTimeout();
        scanInProgress = true;
        scanTimedOut = false;
        scanTimeoutRunnable = () -> {
            if (!scanInProgress) {
                return;
            }

            scanTimedOut = true;
            scanInProgress = false;
            finishActivity(IntentIntegrator.REQUEST_CODE);
        };
        scanTimeoutHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private void clearScanTimeout() {
        scanInProgress = false;
        if (scanTimeoutRunnable != null) {
            scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
    }

    private void startRpcService() {
        Intent serviceIntent = new Intent(this, ServerService.class);
        ContextCompat.startForegroundService(this, serviceIntent);
        setServerUiState(true);
    }

    private void setServerUiState(boolean running) {
        btnStart.setEnabled(!running);
        btnStart.setVisibility(running ? View.GONE : View.VISIBLE);
        btnStop.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    private void updateLogs(String text, boolean scrollToBottom) {
        tvLogs.setText(text);
        if (scrollToBottom) {
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }
}

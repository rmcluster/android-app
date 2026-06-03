package com.llama.rpcapp;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;

import timber.log.Timber;
import android.os.PowerManager;

public class MainActivity extends AppCompatActivity {
    private PowerManager.WakeLock wakeLock;
    private static final String TAG = "MainActivity";
    private static final long SCAN_TIMEOUT_MS = 45_000L;

    private final Handler scanTimeoutHandler = new Handler(Looper.getMainLooper());
    private final AppLogStore.Listener logListener = text ->
            runOnUiThread(() -> {
                fullLogsText = text;
                renderLogs();
            });
    private final ServerService.HealthListener healthListener = snapshot ->
            runOnUiThread(() -> updateHealthSummary(snapshot));

    private TextView tvLogs, tvConnectionStatus, tvLogHealthError;
    private ScrollView logScrollView;
    private View formScroll;
    private View logsPanel;
    private EditText etThreads, etDiscoveryIp, etDiscoveryPort, etNickname, etConnectionString;
    private Button btnStart, btnStop, btnScanQr, btnTabControl, btnTabLogs;
    private Button btnFilterRpc, btnFilterStorage, btnFilterGeneral;
    private SettingsRepository settings;
    private String discoveryToken = "";
    private Runnable scanTimeoutRunnable;
    private boolean scanInProgress = false;
    private boolean scanTimedOut = false;
    private UiTab currentTab = UiTab.CONTROL;
    private String fullLogsText = "";
    private boolean shouldAutoScrollLogs = true;
    private ServerService.HealthSnapshot currentHealth = ServerService.currentHealth;
    private final EnumSet<LogCategory> activeLogFilters = EnumSet.allOf(LogCategory.class);
    private final EnumMap<LogCategory, Button> logFilterButtons = new EnumMap<>(LogCategory.class);

    private enum UiTab {
        CONTROL,
        LOGS
    }

    private enum LogCategory {
        RPC("RPC", 0xFF2563EB),
        STORAGE("Storage", 0xFF16A34A),
        GENERAL("General", 0xFFEA580C);

        final String title;
        final int accentColor;

        LogCategory(String title, int accentColor) {
            this.title = title;
            this.accentColor = accentColor;
        }

        static LogCategory fromLine(String line) {
            String normalized = line.toLowerCase(Locale.US);
            if (normalized.contains("[storage]")) {
                return STORAGE;
            }
            if (normalized.contains("[rpc]")) {
                return RPC;
            }
            if (normalized.contains("[general]")) {
                return GENERAL;
            }
            return GENERAL;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new SettingsRepository(this);

        formScroll = findViewById(R.id.formScroll);
        int basePaddingTop = formScroll.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(formScroll, (v, insets) -> {
            int sysTop = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            v.setPadding(v.getPaddingLeft(), basePaddingTop + sysTop, v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });

        tvLogs = findViewById(R.id.logTextView);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        tvLogHealthError = findViewById(R.id.tvLogHealthError);
        logScrollView = findViewById(R.id.logScrollView);
        logsPanel = findViewById(R.id.logsPanel);
        etDiscoveryIp = findViewById(R.id.etDiscoveryIp);
        etDiscoveryPort = findViewById(R.id.etDiscoveryPort);
        etNickname = findViewById(R.id.etNickname);
        etThreads = findViewById(R.id.etThreads);
        etConnectionString = findViewById(R.id.etConnectionString);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnScanQr = findViewById(R.id.btnScanQr);
        btnTabControl = findViewById(R.id.btnTabControl);
        btnTabLogs = findViewById(R.id.btnTabLogs);
        btnFilterRpc = findViewById(R.id.btnFilterRpc);
        btnFilterStorage = findViewById(R.id.btnFilterStorage);
        btnFilterGeneral = findViewById(R.id.btnFilterGeneral);

        btnTabControl.setOnClickListener(v -> showTab(UiTab.CONTROL));
        btnTabLogs.setOnClickListener(v -> showTab(UiTab.LOGS));
        logFilterButtons.put(LogCategory.RPC, btnFilterRpc);
        logFilterButtons.put(LogCategory.STORAGE, btnFilterStorage);
        logFilterButtons.put(LogCategory.GENERAL, btnFilterGeneral);
        configureLogFilterButton(btnFilterRpc, LogCategory.RPC);
        configureLogFilterButton(btnFilterStorage, LogCategory.STORAGE);
        configureLogFilterButton(btnFilterGeneral, LogCategory.GENERAL);
        updateFilterButtons();
        updateHealthSummary(currentHealth);

        logScrollView.getViewTreeObserver().addOnScrollChangedListener(this::updateAutoScrollState);

        etConnectionString.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String text = s.toString().trim();
                if (text.startsWith("rmcluster://")) {
                    applyConnectionLink(Uri.parse(text));
                }
            }
        });

        loadSettings();
        btnStart.setOnClickListener(v -> {
            String connectionStr = etConnectionString.getText().toString().trim();
            if (!connectionStr.isEmpty()) {
                if (!applyConnectionLink(Uri.parse(connectionStr))) return;
            }
            saveSettings();
            startRpcService();
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, ServerService.class));
            setServerUiState(ServerService.UiState.IDLE);
        });

        btnScanQr.setOnClickListener(v -> startQrScanner());
        fullLogsText = AppLogStore.getInstance().snapshotText();
        renderLogs();
        showTab(UiTab.CONTROL);

        if (getIntent().getBooleanExtra("autoStart", false)) {
            startRpcService();
        }

        Uri deepLink = getIntent().getData();
        if (deepLink != null) {
            parseUri(deepLink);
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RMCLUSTER::GlobalWakeLockTag");
            wakeLock.acquire(); 
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri deepLink = intent.getData();
        if (deepLink != null) {
            parseUri(deepLink);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppLogStore.getInstance().addListener(logListener);
        fullLogsText = AppLogStore.getInstance().snapshotText();
        renderLogs();
        ServerService.setStateListener(state -> setServerUiState(state));
        ServerService.setHealthListener(healthListener);
        setServerUiState(ServerService.currentState);
        updateHealthSummary(ServerService.currentHealth);
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
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    protected void onPause() {
        ServerService.setStateListener(null);
        ServerService.setHealthListener(null);
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

    // Populates coordinator fields from a link without starting the connection.
    // Used by the paste text field so the user can review before connecting.
    private boolean applyConnectionLink(Uri uri) {
        if (!"rmcluster".equals(uri.getScheme()) || !"connect".equals(uri.getHost())) {
            showConnectionStatus("Not a valid rmcluster:// link", false);
            return false;
        }
        try {
            String url = uri.getQueryParameter("url");
            String port = uri.getQueryParameter("port");
            String token = uri.getQueryParameter("token");
            if (url == null || url.isEmpty()) {
                showConnectionStatus("Link is missing server address", false);
                return false;
            }
            etDiscoveryIp.setText(url);
            if (port != null) etDiscoveryPort.setText(port);
            discoveryToken = token != null ? token : "";
            showConnectionStatus("Coordinator: " + url + (port != null ? ":" + port : ""), true);
            return true;
        } catch (Exception e) {
            showConnectionStatus("Invalid connection link", false);
            return false;
        }
    }

    // Parses a link and immediately starts the connection. Used by QR scan and deep links.
    private void parseUri(Uri uri) {
        Timber.tag(TAG).d("Parsing URI: %s", uri);
        if (!applyConnectionLink(uri)) return;
        saveSettings();
        startRpcService();
    }

    private void showConnectionStatus(String message, boolean success) {
        tvConnectionStatus.setText(message);
        tvConnectionStatus.setTextColor(success ? 0xFF4CAF50 : 0xFFE53935);
        tvConnectionStatus.setVisibility(View.VISIBLE);
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
        setServerUiState(ServerService.UiState.CONNECTING);
    }

    private void setServerUiState(ServerService.UiState state) {
        switch (state) {
            case IDLE:
                btnStart.setVisibility(View.VISIBLE);
                btnStop.setVisibility(View.GONE);
                break;
            case CONNECTING:
                btnStart.setVisibility(View.GONE);
                btnStop.setVisibility(View.VISIBLE);
                btnStop.setText("CANCEL CONNECTION");
                btnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF6D00));
                break;
            case CONNECTED:
                btnStart.setVisibility(View.GONE);
                btnStop.setVisibility(View.VISIBLE);
                btnStop.setText("DISCONNECT");
                btnStop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF5252));
                break;
        }
    }

    private void showTab(UiTab tab) {
        currentTab = tab;
        boolean showingControl = tab == UiTab.CONTROL;

        formScroll.setVisibility(showingControl ? View.VISIBLE : View.GONE);
        logsPanel.setVisibility(showingControl ? View.GONE : View.VISIBLE);

        btnTabControl.setSelected(showingControl);
        btnTabLogs.setSelected(!showingControl);

        if (!showingControl) {
            shouldAutoScrollLogs = true;
            renderLogs();
        }
    }

    private void configureLogFilterButton(Button button, LogCategory category) {
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setOnClickListener(v -> {
            if (activeLogFilters.contains(category)) {
                activeLogFilters.remove(category);
            } else {
                activeLogFilters.add(category);
            }
            updateFilterButtons();
            renderLogs();
        });
    }

    private void updateFilterButtons() {
        for (LogCategory category : LogCategory.values()) {
            Button button = logFilterButtons.get(category);
            if (button != null) {
                boolean isActive = activeLogFilters.contains(category);
                int outlineColor = healthOutlineColor(category, currentHealth);
                styleFilterButton(button, category, isActive, outlineColor);
            }
        }
    }

    private void renderLogs() {
        String filteredLogs = buildFilteredLogs(fullLogsText);
        tvLogs.setText(filteredLogs);
        if (currentTab == UiTab.LOGS && shouldAutoScrollLogs) {
            scrollLogsToBottom();
        }
    }

    private String buildFilteredLogs(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String[] lines = text.split("\\r?\\n");
        StringBuilder filtered = new StringBuilder();
        boolean firstLine = true;
        for (String line : lines) {
            if (!activeLogFilters.contains(LogCategory.fromLine(line))) {
                continue;
            }
            if (!firstLine) {
                filtered.append('\n');
            }
            filtered.append(line);
            firstLine = false;
        }
        return filtered.toString();
    }

    private void updateHealthSummary(ServerService.HealthSnapshot snapshot) {
        currentHealth = snapshot;
        String lastError = snapshot.lastError == null ? "" : snapshot.lastError.trim();
        if (lastError.isEmpty()) {
            tvLogHealthError.setVisibility(View.GONE);
        } else {
            tvLogHealthError.setText(lastError);
            tvLogHealthError.setVisibility(View.VISIBLE);
        }

        updateFilterButtons();
    }

    private void styleFilterButton(Button button, LogCategory category, boolean isActive, int outlineColor) {
        int fillColor = isActive ? 0xFF1F2937 : 0x1F6B7280;
        int textColor = isActive ? 0xFFF9FAFB : 0xFF6B7280;
        button.setText(category.title);
        button.setTextColor(textColor);
        button.setBackground(createRoundedBackground(fillColor, outlineColor, outlineColor == Color.TRANSPARENT ? dpToPx(1) : dpToPx(3)));
        button.setBackgroundTintList(null);
    }

    private int healthOutlineColor(LogCategory category, ServerService.HealthSnapshot snapshot) {
        String status = snapshot.status == null ? "idle" : snapshot.status;
        if (!showsHealthOutline(status)) {
            return Color.TRANSPARENT;
        }

        boolean isHealthy;
        switch (category) {
            case RPC:
                isHealthy = snapshot.rpcHealthy;
                break;
            case STORAGE:
                isHealthy = snapshot.storageHealthy;
                break;
            case GENERAL:
            default:
                isHealthy = snapshot.rpcHealthy && snapshot.storageHealthy;
                break;
        }
        return isHealthy ? 0xFF16A34A : 0xFFDC2626;
    }

    private boolean showsHealthOutline(String status) {
        return "running".equals(status)
                || "recovering".equals(status)
                || "degraded".equals(status)
                || "unavailable".equals(status);
    }

    private void scrollLogsToBottom() {
        logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void updateAutoScrollState() {
        View content = logScrollView.getChildAt(0);
        if (content == null) {
            shouldAutoScrollLogs = true;
            return;
        }
        int distanceFromBottom = content.getBottom() - (logScrollView.getHeight() + logScrollView.getScrollY());
        shouldAutoScrollLogs = distanceFromBottom < dpToPx(80);
    }

    private GradientDrawable createRoundedBackground(int fillColor, int strokeColor, int strokeWidthPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dpToPx(999));
        drawable.setColor(fillColor);
        drawable.setStroke(strokeWidthPx, strokeColor);
        return drawable;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

}

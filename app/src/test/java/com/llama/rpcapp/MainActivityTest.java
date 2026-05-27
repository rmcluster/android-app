package com.llama.rpcapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.google.zxing.integration.android.IntentIntegrator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTest {
    private SettingsRepository repository;

    @Before
    public void setUp() {
        ApplicationProvider.getApplicationContext()
                .getSharedPreferences("rpc_server_settings", Activity.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        repository = new SettingsRepository(ApplicationProvider.getApplicationContext());
    }

    @Test
    public void onCreate_loadsSavedSettingsIntoViews() {
        repository.saveConfig(new ServerConfig("node-a", "192.168.0.4", 7000, 7001, "tracker", 7002, "token", 9));

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        assertEquals("192.168.0.4", text(activity, R.id.etHost));
        assertEquals("7000", text(activity, R.id.etPort));
        assertEquals("7001", text(activity, R.id.etStoragePort));
        assertEquals("tracker", text(activity, R.id.etDiscoveryIp));
        assertEquals("7002", text(activity, R.id.etDiscoveryPort));
        assertEquals("9", text(activity, R.id.etThreads));
        assertTrue(((TextView) activity.findViewById(R.id.tvIpAddress)).getText().toString().startsWith("IP Address: "));
    }

    @Test
    public void onResume_refreshesPortsFromSavedConfig() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        repository.saveConfig(new ServerConfig("node-b", "0.0.0.0", 8000, 8001, "", 4917, "", 4));

        controller.pause().resume();

        assertEquals("8000", text(activity, R.id.etPort));
        assertEquals("8001", text(activity, R.id.etStoragePort));
    }

    @Test
    public void parseUri_withValidConnection_updatesFieldsAndPersistsToken() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        invoke(activity, "parseUri", new Class<?>[]{Uri.class}, Uri.parse("rmcluster://connect?url=tracker.local&port=4917&token=abc123"));

        assertEquals("tracker.local", text(activity, R.id.etDiscoveryIp));
        assertEquals("4917", text(activity, R.id.etDiscoveryPort));
        assertEquals("Connected to: tracker.local:4917", ShadowToast.getTextOfLatestToast());
        assertEquals("abc123", repository.loadConfig().discoveryToken);
    }

    @Test
    public void parseUri_withInvalidConnectionShowsToast() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        invoke(activity, "parseUri", new Class<?>[]{Uri.class}, Uri.parse("https://example.com"));

        assertEquals("QR code is not a cluster connection code", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void saveSettings_withInvalidNumbersLeavesPreviousConfigIntact() throws Exception {
        repository.saveConfig(new ServerConfig("node-c", "1.1.1.1", 1234, 1235, "tracker", 4917, "token", 2));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((EditText) activity.findViewById(R.id.etPort)).setText("abc");

        invoke(activity, "saveSettings", new Class<?>[0]);

        assertEquals(1234, repository.loadConfig().port);
    }

    @Test
    public void startButtonStartsServiceAndUpdatesUiState() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        ((Button) activity.findViewById(R.id.btnStart)).performClick();

        Button start = activity.findViewById(R.id.btnStart);
        Button stop = activity.findViewById(R.id.btnStop);
        assertFalse(start.isEnabled());
        assertEquals("SERVER RUNNING", start.getText().toString());
        assertEquals(View.VISIBLE, stop.getVisibility());
    }

    @Test
    public void setServerUiState_falseRestoresStoppedUi() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        invoke(activity, "setServerUiState", new Class<?>[]{boolean.class}, false);

        Button start = activity.findViewById(R.id.btnStart);
        Button stop = activity.findViewById(R.id.btnStop);
        assertTrue(start.isEnabled());
        assertEquals("START SERVER", start.getText().toString());
        assertEquals(View.GONE, stop.getVisibility());
    }

    @Test
    public void beginAndClearScanTimeout_updatesTrackingFlags() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        invoke(activity, "beginScanTimeout", new Class<?>[0]);
        ShadowLooper.idleMainLooper(45, TimeUnit.SECONDS);

        assertTrue((Boolean) field(activity, "scanTimedOut"));
        assertFalse((Boolean) field(activity, "scanInProgress"));

        invoke(activity, "clearScanTimeout", new Class<?>[0]);
        assertFalse((Boolean) field(activity, "scanInProgress"));
        assertEquals(null, field(activity, "scanTimeoutRunnable"));
    }

    @Test
    public void onActivityResult_withCancelledScanShowsFailureToast() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        activity.onActivityResult(IntentIntegrator.REQUEST_CODE, Activity.RESULT_CANCELED, new android.content.Intent());

        assertEquals("Scan failed", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void onDestroy_clearsOutstandingScanTimeout() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        invoke(activity, "beginScanTimeout", new Class<?>[0]);

        activity.onDestroy();

        assertEquals(null, field(activity, "scanTimeoutRunnable"));
    }

    private static String text(MainActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}

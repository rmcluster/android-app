package com.llama.rpcapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        ServerService.currentState = ServerService.UiState.IDLE;
        ApplicationProvider.getApplicationContext()
                .getSharedPreferences("rpc_server_settings", Activity.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        repository = new SettingsRepository(ApplicationProvider.getApplicationContext());
    }

    @Test
    public void onCreate_loadsSavedSettingsIntoViews() {
        repository.saveConfig(new ServerConfig("node-a", 7000, 7001, "tracker", 7002, "token", "worker-a", 9));

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        assertEquals("tracker", text(activity, R.id.etDiscoveryIp));
        assertEquals("7002", text(activity, R.id.etDiscoveryPort));
        assertEquals("worker-a", text(activity, R.id.etNickname));
        assertEquals("9", text(activity, R.id.etThreads));
    }

    @Test
    public void onResume_appliesCurrentServerState() {
        ServerService.currentState = ServerService.UiState.CONNECTED;
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();

        controller.pause().resume();

        MainActivity activity = controller.get();
        assertEquals(View.GONE, activity.findViewById(R.id.btnStart).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.btnStop).getVisibility());
        assertEquals("DISCONNECT", ((Button) activity.findViewById(R.id.btnStop)).getText().toString());
    }

    @Test
    public void applyConnectionLink_withValidConnection_updatesFieldsAndPersistsToken() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        boolean applied = (Boolean) invoke(
                activity,
                "applyConnectionLink",
                new Class<?>[]{Uri.class},
                Uri.parse("rmcluster://connect?url=tracker.local&port=4917&token=abc123")
        );

        assertTrue(applied);
        assertEquals("tracker.local", text(activity, R.id.etDiscoveryIp));
        assertEquals("4917", text(activity, R.id.etDiscoveryPort));
        assertEquals("Coordinator: tracker.local:4917", text(activity, R.id.tvConnectionStatus));
    }

    @Test
    public void applyConnectionLink_withInvalidConnectionShowsStatus() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        boolean applied = (Boolean) invoke(
                activity,
                "applyConnectionLink",
                new Class<?>[]{Uri.class},
                Uri.parse("https://example.com")
        );

        assertFalse(applied);
        assertEquals("Not a valid rmcluster:// link", text(activity, R.id.tvConnectionStatus));
    }

    @Test
    public void saveSettings_withInvalidNumbersLeavesPreviousConfigIntact() throws Exception {
        repository.saveConfig(new ServerConfig("node-c", 1234, 1235, "tracker", 4917, "token", "nick", 2));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        ((EditText) activity.findViewById(R.id.etThreads)).setText("abc");

        invoke(activity, "saveSettings", new Class<?>[0]);

        assertEquals(2, repository.loadConfig().threads);
    }

    @Test
    public void startButtonStartsServiceAndUpdatesUiState() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        ((Button) activity.findViewById(R.id.btnStart)).performClick();

        Button start = activity.findViewById(R.id.btnStart);
        Button stop = activity.findViewById(R.id.btnStop);
        assertEquals(View.GONE, start.getVisibility());
        assertEquals(View.VISIBLE, stop.getVisibility());
        assertEquals("CANCEL CONNECTION", stop.getText().toString());
    }

    @Test
    public void setServerUiState_idleRestoresStoppedUi() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        invoke(activity, "setServerUiState", new Class<?>[]{ServerService.UiState.class}, ServerService.UiState.IDLE);

        Button start = activity.findViewById(R.id.btnStart);
        Button stop = activity.findViewById(R.id.btnStop);
        assertEquals(View.VISIBLE, start.getVisibility());
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

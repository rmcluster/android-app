package com.llama.rpcapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class SettingsRepositoryTest {
    private Context context;
    private SharedPreferences preferences;
    private SharedPreferences.Editor editor;
    private SettingsRepository repository;
    private Map<String, Object> allPrefs;

    @Before
    public void setUp() {
        context = mock(Context.class);
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.flags = ApplicationInfo.FLAG_DEBUGGABLE;
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getApplicationInfo()).thenReturn(applicationInfo);
        preferences = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class);
        allPrefs = new HashMap<>();

        when(context.getSharedPreferences("rpc_server_settings", Context.MODE_PRIVATE)).thenReturn(preferences);
        when(preferences.edit()).thenReturn(editor);
        when(preferences.getAll()).thenAnswer(invocation -> allPrefs);
        when(preferences.contains(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation ->
                allPrefs.containsKey(invocation.getArgument(0)));
        when(preferences.getBoolean(eq("verbose_rpc_logging"), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> (Boolean) allPrefs.getOrDefault("verbose_rpc_logging", invocation.getArgument(1)));
        when(editor.putString(anyString(), anyString())).thenReturn(editor);
        when(editor.putInt(anyString(), anyInt())).thenReturn(editor);
        when(editor.putBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(editor);

        repository = new SettingsRepository(context);
    }

    @Test
    public void loadConfig_returnsDefaultsWhenPrefsAreEmpty() {
        ServerConfig config = repository.loadConfig();

        assertFalse(config.nodeId.isEmpty());
        assertEquals(47671, config.port);
        assertEquals(47672, config.storagePort);
        assertEquals("", config.discoveryIp);
        assertEquals(4917, config.discoveryPort);
        assertEquals("", config.discoveryToken);
        assertEquals("", config.nickname);
        assertEquals(4, config.threads);
    }

    @Test
    public void loadConfig_readsIntegerLongFloatAndStringValues() {
        allPrefs.put("node_id", "node-1");
        allPrefs.put("port", 8080);
        allPrefs.put("storage_port", 8081L);
        allPrefs.put("discovery_ip", "tracker");
        allPrefs.put("discovery_port", 4917.0f);
        allPrefs.put("discovery_token", "token");
        allPrefs.put("nickname", "worker");
        allPrefs.put("threads", "6");

        ServerConfig config = repository.loadConfig();

        assertEquals("node-1", config.nodeId);
        assertEquals(8080, config.port);
        assertEquals(8081, config.storagePort);
        assertEquals("tracker", config.discoveryIp);
        assertEquals(4917, config.discoveryPort);
        assertEquals("token", config.discoveryToken);
        assertEquals("worker", config.nickname);
        assertEquals(6, config.threads);
        verify(editor).putInt("threads", 6);
    }

    @Test
    public void loadConfig_parsesStringIntsAndFallsBackOnInvalidValues() {
        allPrefs.put("node_id", "node-2");
        allPrefs.put("port", " 9000 ");
        allPrefs.put("storage_port", "not-a-number");
        allPrefs.put("discovery_ip", 12345);
        allPrefs.put("discovery_port", true);
        allPrefs.put("discovery_token", "token");
        allPrefs.put("nickname", "nick");
        allPrefs.put("threads", 8);

        ServerConfig config = repository.loadConfig();

        assertEquals(9000, config.port);
        assertEquals(47672, config.storagePort);
        assertEquals("12345", config.discoveryIp);
        assertEquals(4917, config.discoveryPort);
        assertEquals(8, config.threads);
        verify(editor).putInt("port", 9000);
        verify(editor).putString("discovery_ip", "12345");
    }

    @Test
    public void saveConfig_writesEveryFieldAndAppliesEdit() {
        ServerConfig config = new ServerConfig(
                "node-xyz",
                5555,
                5556,
                "tracker.local",
                6000,
                "token-123",
                "worker-1",
                8
        );

        repository.saveConfig(config);

        verify(editor).putString("node_id", "node-xyz");
        verify(editor).putInt("port", 5555);
        verify(editor).putInt("storage_port", 5556);
        verify(editor).putString("discovery_ip", "tracker.local");
        verify(editor).putInt("discovery_port", 6000);
        verify(editor).putString("discovery_token", "token-123");
        verify(editor).putString("nickname", "worker-1");
        verify(editor).putInt("threads", 8);
        verify(editor).apply();
    }

    @Test
    public void verboseRpcLogging_defaultsToTrueForDebuggableBuildWhenUnset() {
        assertTrue(repository.isVerboseRpcLogging());
    }

    @Test
    public void verboseRpcLogging_persistsExplicitValue() {
        repository.setVerboseRpcLogging(true);
        allPrefs.put("verbose_rpc_logging", true);

        assertTrue(repository.isVerboseRpcLogging());
        verify(editor).putBoolean("verbose_rpc_logging", true);
    }
}

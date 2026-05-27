package com.llama.rpcapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;

public class SettingsRepositoryTest {
    private Context context;
    private SharedPreferences preferences;
    private SharedPreferences.Editor editor;
    private SettingsRepository repository;

    @Before
    public void setUp() {
        context = mock(Context.class);
        preferences = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class);

        when(context.getSharedPreferences("rpc_server_settings", Context.MODE_PRIVATE)).thenReturn(preferences);
        when(preferences.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenReturn(editor);
        when(editor.putInt(anyString(), anyInt())).thenReturn(editor);

        repository = new SettingsRepository(context);
    }

    @Test
    public void loadConfig_returnsDefaultsWhenPrefsAreEmpty() {
        when(preferences.getString(eq("node_id"), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(preferences.getString(eq("host"), eq("0.0.0.0"))).thenReturn("0.0.0.0");
        when(preferences.getString(eq("discovery_ip"), eq(""))).thenReturn("");
        when(preferences.getString(eq("discovery_token"), eq(""))).thenReturn("");
        when(preferences.getInt("port", 47671)).thenReturn(47671);
        when(preferences.getInt("storage_port", 47672)).thenReturn(47672);
        when(preferences.getInt("discovery_port", 4917)).thenReturn(4917);
        when(preferences.getInt("threads", 4)).thenReturn(4);

        ServerConfig config = repository.loadConfig();

        assertFalse(config.nodeId.isEmpty());
        assertEquals("0.0.0.0", config.host);
        assertEquals(47671, config.port);
        assertEquals(47672, config.storagePort);
        assertEquals("", config.discoveryIp);
        assertEquals(4917, config.discoveryPort);
        assertEquals("", config.discoveryToken);
        assertEquals(4, config.threads);
    }

    @Test
    public void saveConfig_writesEveryFieldAndAppliesEdit() {
        ServerConfig config = new ServerConfig(
                "node-xyz",
                "192.168.1.10",
                5555,
                5556,
                "tracker.local",
                6000,
                "token-123",
                8
        );

        repository.saveConfig(config);

        verify(editor).putString("node_id", "node-xyz");
        verify(editor).putString("host", "192.168.1.10");
        verify(editor).putInt("port", 5555);
        verify(editor).putInt("storage_port", 5556);
        verify(editor).putString("discovery_ip", "tracker.local");
        verify(editor).putInt("discovery_port", 6000);
        verify(editor).putString("discovery_token", "token-123");
        verify(editor).putInt("threads", 8);
        verify(editor).apply();
    }
}

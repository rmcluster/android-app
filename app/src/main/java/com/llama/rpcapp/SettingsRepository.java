package com.llama.rpcapp;

import android.content.Context;
import android.content.SharedPreferences; //apply() writes to disk (xml file)

import java.util.Map;
import java.util.UUID;

public class SettingsRepository {
    private static final String PREF_NAME = "rpc_server_settings";
    private static final String KEY_THREADS = "threads";
    private static final String KEY_DISCOVERY_IP = "discovery_ip";
    private static final String KEY_DISCOVERY_PORT = "discovery_port";
    private static final String KEY_DISCOVERY_TOKEN = "discovery_token";
    private static final String KEY_NICKNAME = "nickname";
    private static final String KEY_PORT = "port";
    private static final String KEY_STORAGE_PORT = "storage_port";
    private static final String NODE_ID = "node_id";

    private final SharedPreferences prefs;

    public SettingsRepository(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public ServerConfig loadConfig() {
        return new ServerConfig(
                getStringCompat(NODE_ID, UUID.randomUUID().toString()),
                getIntCompat(KEY_PORT, 47671),
                getIntCompat(KEY_STORAGE_PORT, 47672),
                getStringCompat(KEY_DISCOVERY_IP, ""),
                getIntCompat(KEY_DISCOVERY_PORT, 4917),
                getStringCompat(KEY_DISCOVERY_TOKEN, ""),
                getStringCompat(KEY_NICKNAME, ""),
                getIntCompat(KEY_THREADS, 4)
        );
    }

    private int getIntCompat(String key, int defaultValue) {
        Map<String, ?> all = prefs.getAll();
        Object value = all.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Long) {
            return (int) ((Long) value).longValue();
        }
        if (value instanceof Float) {
            return Math.round((Float) value);
        }
        if (value instanceof String) {
            try {
                int parsed = Integer.parseInt(((String) value).trim());
                prefs.edit().putInt(key, parsed).apply();
                return parsed;
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String getStringCompat(String key, String defaultValue) {
        Map<String, ?> all = prefs.getAll();
        Object value = all.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String) {
            return (String) value;
        }
        String coerced = String.valueOf(value);
        prefs.edit().putString(key, coerced).apply();
        return coerced;
    }

    public void saveConfig(ServerConfig config) {
        prefs.edit()
                .putString(NODE_ID, config.nodeId)
                .putInt(KEY_PORT, config.port)
                .putInt(KEY_STORAGE_PORT, config.storagePort)
                .putString(KEY_DISCOVERY_IP, config.discoveryIp)
                .putInt(KEY_DISCOVERY_PORT, config.discoveryPort)
                .putString(KEY_DISCOVERY_TOKEN, config.discoveryToken)
                .putString(KEY_NICKNAME, config.nickname)
                .putInt(KEY_THREADS, config.threads)
                .apply();
    }
}

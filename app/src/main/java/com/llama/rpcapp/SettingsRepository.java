package com.llama.rpcapp;

import android.content.Context;
import android.content.SharedPreferences; //apply() writes to disk (xml file)
import java.util.UUID;



public class SettingsRepository {
    private static final String PREF_NAME = "rpc_server_settings";
    private static final String KEY_THREADS = "threads";
    private static final String KEY_DISCOVERY_IP = "discovery_ip";
    private static final String KEY_DISCOVERY_PORT = "discovery_port";
    private static final String KEY_DISCOVERY_TOKEN = "discovery_token";
    private static final String KEY_PORT = "port";
    private static final String KEY_STORAGE_PORT = "storage_port";
    private static final String NODE_ID = "node_id";

    private final SharedPreferences prefs;

    public SettingsRepository(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public ServerConfig loadConfig() {
        return new ServerConfig(
                prefs.getString(NODE_ID, UUID.randomUUID().toString()),
                prefs.getInt(KEY_PORT, 47671),
                prefs.getInt(KEY_STORAGE_PORT, 47672),
                prefs.getString(KEY_DISCOVERY_IP, ""),
                prefs.getInt(KEY_DISCOVERY_PORT, 4917),
                prefs.getString(KEY_DISCOVERY_TOKEN, ""),
                prefs.getInt(KEY_THREADS, 4)
        );
    }

    public void saveConfig(ServerConfig config) {
        prefs.edit()
                .putString(NODE_ID, config.nodeId)
                .putInt(KEY_PORT, config.port)
                .putInt(KEY_STORAGE_PORT, config.storagePort)
                .putString(KEY_DISCOVERY_IP, config.discoveryIp)
                .putInt(KEY_DISCOVERY_PORT, config.discoveryPort)
                .putString(KEY_DISCOVERY_TOKEN, config.discoveryToken)
                .putInt(KEY_THREADS, config.threads)
                .apply();
    }
}

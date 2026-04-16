package com.llama.rpcapp;

import android.content.Context;
import android.content.SharedPreferences; //apply() writes to disk (xml file)

public class SettingsRepository {
    private static final String PREF_NAME = "rpc_server_settings";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_THREADS = "threads";
    private static final String KEY_DISCOVERY_IP = "discovery_ip";
    private static final String KEY_DISCOVERY_PORT = "discovery_port";

    private final SharedPreferences prefs;

    public SettingsRepository(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public ServerConfig loadConfig() {
        return new ServerConfig(
                prefs.getString(KEY_HOST, "0.0.0.0"),
                prefs.getInt(KEY_PORT, 47671),
                prefs.getString(KEY_DISCOVERY_IP, ""),
                prefs.getInt(KEY_DISCOVERY_PORT, 4917),
                prefs.getInt(KEY_THREADS, 4)
        );
    }

    public void saveConfig(ServerConfig config) {
        prefs.edit()
                .putString(KEY_HOST, config.host)
                .putInt(KEY_PORT, config.port)
                .putString(KEY_DISCOVERY_IP, config.discoveryIp)
                .putInt(KEY_DISCOVERY_PORT, config.discoveryPort)
                .putInt(KEY_THREADS, config.threads)
                .apply();
    }
}

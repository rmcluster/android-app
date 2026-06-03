package com.llama.rpcapp;

import androidx.multidex.MultiDexApplication;

import timber.log.Timber;

public final class RpcApp extends MultiDexApplication {
    @Override
    public void onCreate() {
        super.onCreate();
        Timber.plant(new AppLogTree());
    }
}

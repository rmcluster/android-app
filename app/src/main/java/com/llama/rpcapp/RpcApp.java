package com.llama.rpcapp;

import android.app.Application;

import timber.log.Timber;

public final class RpcApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Timber.plant(new AppLogTree());
    }
}

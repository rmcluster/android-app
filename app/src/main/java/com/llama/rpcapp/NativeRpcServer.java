package com.llama.rpcapp;

public class NativeRpcServer {
    static {
        System.loadLibrary("llama-rpc");
    }

    public native void startServer(String host, int port, int n_threads, String cacheDir);
    public native void stopServer();
    public native long getMaxSize();
}

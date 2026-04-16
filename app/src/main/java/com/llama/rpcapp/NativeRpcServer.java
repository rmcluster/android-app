package com.llama.rpcapp;

public class NativeRpcServer {
    static {
        System.loadLibrary("llama-rpc");
    }

    public native long getMaxSize();
}

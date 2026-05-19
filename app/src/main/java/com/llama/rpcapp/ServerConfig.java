package com.llama.rpcapp;

public final class ServerConfig {
    public final String host;
    public final int port;
    public final int storagePort;
    public final String discoveryIp;
    public final int discoveryPort;
    public final String discoveryToken;
    public final int threads;
    public final String nodeId;

    public ServerConfig(String nodeId, String host, int port, int storagePort, String discoveryIp, int discoveryPort, String discoveryToken, int threads) {
        this.nodeId = nodeId;
        this.host = normalize(host);
        this.port = port;
        this.storagePort = storagePort;
        this.discoveryIp = discoveryIp == null ? "" : discoveryIp.trim();
        this.discoveryPort = discoveryPort;
        this.discoveryToken = discoveryToken == null ? "" : discoveryToken.trim();
        this.threads = threads;
    }

    private static String normalize(String rawHost) {
        return rawHost == null || rawHost.trim().isEmpty() ? "0.0.0.0" : rawHost.trim();
    }
}

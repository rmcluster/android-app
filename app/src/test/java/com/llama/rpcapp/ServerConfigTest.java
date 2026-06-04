package com.llama.rpcapp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ServerConfigTest {
    @Test
    public void constructor_normalizesAndTrimsFields() {
        ServerConfig config = new ServerConfig(
                "node-1",
                8080,
                8081,
                " 10.0.0.8 ",
                4917,
                " secret ",
                " my-node ",
                6
        );

        assertEquals("node-1", config.nodeId);
        assertEquals(8080, config.port);
        assertEquals(8081, config.storagePort);
        assertEquals("10.0.0.8", config.discoveryIp);
        assertEquals(4917, config.discoveryPort);
        assertEquals("secret", config.discoveryToken);
        assertEquals("my-node", config.nickname);
        assertEquals(6, config.threads);
    }

    @Test
    public void constructor_defaultsBlankValues() {
        ServerConfig config = new ServerConfig(
                "node-2",
                1,
                2,
                null,
                3,
                null,
                null,
                4
        );

        assertEquals("", config.discoveryIp);
        assertEquals("", config.discoveryToken);
        assertEquals("", config.nickname);
    }

    @Test
    public void constructor_trimsWhitespaceNickname() {
        ServerConfig config = new ServerConfig("node-3", 1, 2, "", 3, "", "   ", 4);

        assertEquals("", config.nickname);
    }
}

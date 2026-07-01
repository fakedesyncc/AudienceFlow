package io.audienceflow.desktop.api;

import java.net.http.WebSocket;
import java.util.concurrent.TimeUnit;

public final class LiveConnection implements AutoCloseable {
    private final WebSocket webSocket;

    LiveConnection(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    @Override
    public void close() {
        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client closed")
                .orTimeout(3, TimeUnit.SECONDS)
                .whenComplete((v, err) -> {
                    if (err != null) {
                        webSocket.abort();
                    }
                });
    }
}

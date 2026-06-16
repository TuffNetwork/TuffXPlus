package tf.tuff;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;

public class ServerRegistry {
    private final TuffX p;
    private final String wsUrl;
    private final String server;
    private WebSocketClient client;
    private boolean running = true;

    public ServerRegistry(TuffX pl, String registryUrl, String serverAddr) {
        p = pl;
        wsUrl = registryUrl;
        server = serverAddr;
    }

    public void connect() {
        p.foliaLib.getScheduler().runAsync(t -> doConnect());
    }

    private void doConnect() {
        if (!running)
            return;

        try {
            client = new WebSocketClient(new URI(wsUrl)) {
                @Override
                public void onOpen(ServerHandshake h) {
                    send("{\"type\":\"register\",\"server\":\"" + server + "\"}");
                }

                @Override
                public void onMessage(String msg) {
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (running) {
                        p.foliaLib.getScheduler().runLaterAsync(t -> doConnect(), 100L);
                    }
                }

                @Override
                public void onError(Exception e) {
                }
            };
            client.setConnectionLostTimeout(30);
            client.connect();
        } catch (Exception e) {
            if (running) {
                p.foliaLib.getScheduler().runLaterAsync(t -> doConnect(), 100L);
            }
        }
    }

    public void disconnect() {
        running = false;
        if (client != null) {
            client.close();
        }
    }
}

package tf.tuff;

import org.bukkit.plugin.java.JavaPlugin;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import java.net.URI;

import tf.tuff.util.SchedulerCompat;

public class ServerRegistry {
	private final JavaPlugin p;
	private final String wsUrl;
	private final String server;
	private WebSocketClient client;
	private volatile boolean running = true;

	public ServerRegistry(JavaPlugin pl, String registryUrl, String serverAddr) {
		p = pl;
		wsUrl = registryUrl;
		server = serverAddr;
	}

	public void connect() {
		SchedulerCompat.runAsync(p, this::doConnect);
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
						SchedulerCompat.runAsyncLater(p, ServerRegistry.this::doConnect, 100L);
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
				SchedulerCompat.runAsyncLater(p, this::doConnect, 100L);
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

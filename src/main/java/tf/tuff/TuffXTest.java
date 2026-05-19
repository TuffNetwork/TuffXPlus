package tf.tuff;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class TuffXTest {

    private static ServerMock server;
    private static TuffX plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(TuffX.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginEnablesSuccessfully() {
        assertTrue(plugin.isEnabled(), "Plugin should be enabled after load");
    }

    @Test
    void latestVersionIsNullOnStartup() {
        assertNull(plugin.latestAvailableVersion,
            "No update should be detected synchronously at startup");
    }

    @Test
    void reloadDoesNotThrow() {
        assertDoesNotThrow(() -> plugin.reloadTuffX(),
            "reloadTuffX() should not throw");
    }

    @Test
    void opReceivesUpdateMessageWhenUpdateAvailable() {
        plugin.latestAvailableVersion = "2.0.0";

        PlayerMock player = server.addPlayer();
        player.setOp(true);
        player.assertNoMoreSaid();

        player.disconnect();
        server.addPlayer(player.getName());

        player.assertSaid("§e[TuffX] §fA new version is available: §a2.0.0 §f(running §c1.0.0-patch§f)");
    }

    @Test
    void nonOpDoesNotReceiveUpdateMessage() {
        plugin.latestAvailableVersion = "2.0.0";

        PlayerMock player = server.addPlayer();
        player.setOp(false);

        player.assertNoMoreSaid();
    }
}
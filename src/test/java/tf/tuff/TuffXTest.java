package tf.tuff;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
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
	void reloadDoesNotThrow() {
		assertDoesNotThrow(() -> plugin.reloadTuffX(),
			"reloadTuffX() should not throw");
	}
}

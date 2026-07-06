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


	@Test
	void findsMappingFile() {
		assertTrue(plugin.y0Plugin.viaIds.findMappingFile("26.1.1").version().equals("1.21.11"), "Mapping file for 26.1.1 should be 1.21.11"); // check jump from 26.x to 1.21.x
		assertTrue(plugin.y0Plugin.viaIds.findMappingFile("1.21.11").version().equals("1.21.11"), "Mapping file for 1.21.11 should be 1.21.11"); // check direct match
		assertTrue(plugin.y0Plugin.viaIds.findMappingFile("1.21.10").version().equals("1.21.9"), "Mapping file for 1.21.10 should be 1.21.9"); // check fallback to one patch version lower
		assertTrue(plugin.y0Plugin.viaIds.findMappingFile("1.21.1").version().equals("1.21"), "Mapping file for 1.21.1 should be 1.21"); // check one that won't have a patch version
	}
}

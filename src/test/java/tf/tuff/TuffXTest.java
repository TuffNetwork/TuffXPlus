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
		assertEquals("26.1", plugin.y0Plugin.viaIds.findMappingFile("26.1.2").version(), "Mapping file for 26.1.2 should be 26.1"); // check 26.x
		assertEquals("1.21.11", plugin.y0Plugin.viaIds.findMappingFile("1.21.11").version(), "Mapping file for 1.21.11 should be 1.21.11"); // check direct match
		assertEquals("1.21.9", plugin.y0Plugin.viaIds.findMappingFile("1.21.10").version(), "Mapping file for 1.21.10 should be 1.21.9"); // check fallback to one patch version lower
		assertEquals("1.21", plugin.y0Plugin.viaIds.findMappingFile("1.21.1").version(), "Mapping file for 1.21.1 should be 1.21"); // check one that won't have a patch version
	}
}

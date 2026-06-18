package tf.tuff.tuffactions;

import java.util.logging.Level;

import tf.tuff.TuffX;

public abstract class TuffActionBase {

	private boolean enabled = false;
	private boolean debugMode = false;

	private final String name;
	private final String configPath;

	protected final TuffActions actsPlugin;
	protected final TuffX plugin;

	public TuffActionBase(TuffActions actsPlugin, String name, String configPath, boolean defaultEnabled) {
		this.actsPlugin = actsPlugin;
		this.plugin = actsPlugin.plugin;
		this.name = name;
		this.configPath = configPath;
		plugin.getConfig().addDefault(configPath+".enabled", defaultEnabled);
		plugin.getConfig().addDefault(configPath+".debug", false);
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void onConfigLoad() {
		boolean wasEnabled = this.enabled;
		this.enabled = plugin.getConfig().getBoolean(this.configPath+".enabled");
		if (enabled) {
			this.enable(wasEnabled);
		} else if (wasEnabled) {
			this.disable();
		}
		this.debugMode = plugin.getConfig().getBoolean(this.configPath+".debug");
	}
	protected void enable(boolean wasEnabled) {
		actsPlugin.info(name+" enabled.");
	}
	protected void disable() {
		actsPlugin.info(name+" is now disabled.");
	}

	protected boolean isDebug() {
		return debugMode;
	}
	protected void debug(String msg) {
		if (debugMode) actsPlugin.log(Level.INFO, msg);
	}
	protected void debug(String msg, Exception e) {
		if (debugMode) actsPlugin.log(Level.INFO, msg, e);
	}
}

package net.opendonut.velocity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.opendonut.common.config.SeedStore;
import net.opendonut.common.model.ServerDefinition;

/**
 * The {@code /opendonut} command on the proxy.
 *
 * <pre>
 *   /opendonut                  summary: seed, channel, regions, backends, transfers
 *   /opendonut regions          the routing table, with each backend's verification state
 *   /opendonut backends         what each backend reported about itself
 *   /opendonut route &lt;x&gt; &lt;z&gt;    which server owns a global coordinate, per the proxy's table
 *   /opendonut seed             the authoritative world seed and the server.properties line for it
 *   /opendonut playerdata       the player-data synchronisation state (store, settings)
 *   /opendonut reload           re-read the configuration without restarting the proxy
 * </pre>
 *
 * <p>This is the proxy-side half of the same diagnostics the Fabric mod exposes: the two sides answer the
 * same questions from their own copies of the configuration, which is exactly what makes a disagreement
 * visible. If {@code /opendonut route} gives different answers on a backend and on the proxy, those two
 * machines are not loading the same file.
 *
 * <p>Requires the {@code opendonut.admin} permission for players. The console is always allowed.
 */
final class OpenDonutProxyCommand implements SimpleCommand {

	private final OpenDonutVelocityPlugin plugin;

	OpenDonutProxyCommand(OpenDonutVelocityPlugin plugin) {
		this.plugin = plugin;
	}

	@Override
	public void execute(Invocation invocation) {
		CommandSource source = invocation.source();
		String[] arguments = invocation.arguments();

		if (arguments.length == 0) {
			status(source);
			return;
		}

		String subcommand = arguments[0].toLowerCase(Locale.ROOT);
		switch (subcommand) {
			case "status" -> status(source);
			case "regions" -> regions(source);
			case "backends" -> backends(source);
			case "seed" -> seed(source);
			case "route" -> route(source, arguments);
			case "playerdata" -> playerData(source);
			case "reload" -> reload(source);
			case "help" -> help(source);
			default -> {
				source.sendPlainMessage("[OpenDonut] unknown subcommand '" + arguments[0] + "'");
				help(source);
			}
		}
	}

	@Override
	public boolean hasPermission(Invocation invocation) {
		// The console can always ask; a player needs the permission.
		if (!(invocation.source() instanceof Player)) {
			return true;
		}
		return invocation.source().hasPermission("opendonut.admin");
	}

	@Override
	public List<String> suggest(Invocation invocation) {
		List<String> options = List.of(
				"status", "regions", "backends", "seed", "route", "playerdata", "reload", "help");
		String[] arguments = invocation.arguments();
		if (arguments.length <= 1) {
			String prefix = arguments.length == 1 ? arguments[0].toLowerCase(Locale.ROOT) : "";
			List<String> matches = new ArrayList<>();
			for (String option : options) {
				if (option.startsWith(prefix)) {
					matches.add(option);
				}
			}
			return matches;
		}
		// Coordinates: suggest a couple of illustrative values rather than nothing.
		if (arguments[0].equalsIgnoreCase("route") && arguments.length == 2) {
			return List.of("-100000", "-1", "0", "1", "99999");
		}
		return List.of();
	}

	/**
	 * Prints the summary.
	 *
	 * @param source the command source
	 */
	private void status(CommandSource source) {
		if (!plugin.isActive()) {
			source.sendPlainMessage("[OpenDonut] The plugin is not active: the configuration could not be "
					+ "used. See the proxy log for the validation report, or run "
					+ "'java -jar opendonut-tools.jar check " + plugin.dataDirectory().resolve("opendonut.yml")
					+ "'.");
			return;
		}
		source.sendPlainMessage("[OpenDonut] OpenDonut is active.");
		source.sendPlainMessage("[OpenDonut]   data directory : " + plugin.dataDirectory());
		source.sendPlainMessage("[OpenDonut]   world          : " + plugin.config().world().name()
				+ ", border " + plugin.config().border().describe());
		source.sendPlainMessage("[OpenDonut]   seed           : " + plugin.resolvedSeed()
				+ "  (" + SeedStore.levelSeedLine(plugin.resolvedSeed()) + " in every server.properties)");
		source.sendPlainMessage("[OpenDonut]   bridge         : channel " + plugin.config().bridge().channel()
				+ ", secret fingerprint " + plugin.secretFingerprint());
		source.sendPlainMessage("[OpenDonut]   regions        : " + plugin.config().servers().size()
				+ " configured, " + plugin.server().getAllServers().size() + " registered with the proxy");
		source.sendPlainMessage("[OpenDonut]   backends       : " + plugin.routing().backends().size()
				+ " have announced themselves, " + plugin.routing().pendingCount() + " transfer(s) in flight");
	}

	/**
	 * Prints the routing table.
	 *
	 * @param source the command source
	 */
	private void regions(CommandSource source) {
		if (!requireActive(source)) {
			return;
		}
		List<String> registered = new ArrayList<>();
		for (RegisteredServer server : plugin.server().getAllServers()) {
			registered.add(server.getServerInfo().getName());
		}
		for (String line : plugin.routing().describeRegions(registered).split("\\R")) {
			source.sendPlainMessage("[OpenDonut] " + line);
		}
	}

	/**
	 * Prints what each backend reported.
	 *
	 * @param source the command source
	 */
	private void backends(CommandSource source) {
		if (!requireActive(source)) {
			return;
		}
		for (String line : plugin.routing().describeBackends().split("\\R")) {
			source.sendPlainMessage("[OpenDonut] " + line);
		}
	}

	/**
	 * Prints the authoritative seed and the line to put in {@code server.properties}.
	 *
	 * @param source the command source
	 */
	private void seed(CommandSource source) {
		if (!requireActive(source)) {
			return;
		}
		source.sendPlainMessage("[OpenDonut] The authoritative world seed is " + plugin.resolvedSeed() + ".");
		source.sendPlainMessage("[OpenDonut] Put this in every backend's server.properties BEFORE its world is "
				+ "created:");
		source.sendPlainMessage("[OpenDonut]   " + SeedStore.levelSeedLine(plugin.resolvedSeed()));
		source.sendPlainMessage("[OpenDonut] Changing the seed of a world that already exists does not "
				+ "regenerate its chunks; see docs/troubleshooting.md.");
	}

	/**
	 * Answers a coordinate lookup.
	 *
	 * @param source the command source
	 * @param arguments the command arguments
	 */
	private void route(CommandSource source, String[] arguments) {
		if (!requireActive(source)) {
			return;
		}
		if (arguments.length < 3) {
			source.sendPlainMessage("[OpenDonut] usage: /opendonut route <x> <z>");
			return;
		}
		int x;
		int z;
		try {
			x = Integer.parseInt(arguments[1]);
			z = Integer.parseInt(arguments[2]);
		} catch (NumberFormatException e) {
			source.sendPlainMessage("[OpenDonut] coordinates must be whole numbers of blocks");
			return;
		}

		Optional<ServerDefinition> owner = plugin.routing().owner(x, z);
		if (owner.isEmpty()) {
			source.sendPlainMessage("[OpenDonut] (" + x + ", " + z + ") chunk (" + (x >> 4) + ", " + (z >> 4)
					+ ") is owned by no server, so a player there cannot be routed anywhere.");
			return;
		}
		ServerDefinition target = owner.get();
		source.sendPlainMessage("[OpenDonut] (" + x + ", " + z + ") chunk (" + (x >> 4) + ", " + (z >> 4)
				+ ") is owned by '" + target.id() + "' (" + target.address() + "), region "
				+ target.bounds().describe() + ".");
	}

	/**
	 * Prints the player-data synchronisation state: the settings and what the store holds.
	 *
	 * @param source the command source
	 */
	private void playerData(CommandSource source) {
		if (!requireActive(source)) {
			return;
		}
		if (plugin.playerData() == null) {
			source.sendPlainMessage("[OpenDonut] player-data is not enabled (player-data.enabled: false), "
					+ "or the store could not be opened. See the proxy log.");
			return;
		}
		for (String line : plugin.playerData().describe().split("\\R")) {
			source.sendPlainMessage("[OpenDonut] " + line);
		}
	}

	/**
	 * Re-reads the configuration.
	 *
	 * @param source the command source
	 */
	private void reload(CommandSource source) {
		boolean ok = plugin.reload();
		if (ok) {
			source.sendPlainMessage("[OpenDonut] configuration reloaded: "
					+ plugin.config().servers().size() + " region(s), seed " + plugin.resolvedSeed());
		} else {
			source.sendPlainMessage("[OpenDonut] reload failed; the plugin is now INACTIVE. Fix the "
					+ "configuration and run /opendonut reload again. The proxy log has the report.");
		}
	}

	/**
	 * Prints the command list.
	 *
	 * @param source the command source
	 */
	private void help(CommandSource source) {
		source.sendPlainMessage("[OpenDonut] /opendonut status|regions|backends|seed|route <x> <z>|playerdata|reload");
	}

	/**
	 * Guards a subcommand that needs a loaded configuration.
	 *
	 * @param source the command source
	 * @return {@code true} when the plugin is active
	 */
	private boolean requireActive(CommandSource source) {
		if (plugin.isActive()) {
			return true;
		}
		source.sendPlainMessage("[OpenDonut] not active: no configuration is loaded. See the proxy log.");
		return false;
	}
}

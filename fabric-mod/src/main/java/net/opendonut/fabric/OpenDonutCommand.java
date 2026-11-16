package net.opendonut.fabric;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import net.opendonut.common.model.ServerDefinition;

/**
 * The {@code /opendonut} command.
 *
 * <h2>Why a command exists</h2>
 * A multi-server world fails in ways that are invisible from inside any single server: a port that does not
 * match the configuration, an asymmetric secret, a coordinate that resolves to a different backend than the
 * administrator expects. Each of those is a one-line question, so the answers are one command away instead
 * of a restart with debug logging enabled.
 *
 * <pre>
 *   /opendonut                 summary of this backend
 *   /opendonut status          the same summary, explicitly
 *   /opendonut region          the rectangle this backend owns
 *   /opendonut players         transfers currently in flight
 *   /opendonut route &lt;x&gt; &lt;z&gt;   which server owns a global coordinate
 * </pre>
 *
 * <p>Every subcommand requires administrator permissions. The answers are useful to an operator and
 * irrelevant to a player — beyond mildly revealing the shape of the network.
 *
 * <h2>Registration timing</h2>
 * The command tree is built once, when the server loads its datapacks, but the backend is created slightly
 * earlier and may be absent if the configuration was unusable. The tree therefore reads the backend from a
 * supplier at <em>execution</em> time, so the command exists either way and says plainly when OpenDonut is
 * not active.
 */
final class OpenDonutCommand {

	private OpenDonutCommand() {
	}

	/**
	 * Builds the command tree.
	 *
	 * @param backend supplies the running backend, or {@code null} when OpenDonut is not active
	 * @return the command builder
	 */
	static LiteralArgumentBuilder<CommandSourceStack> build(Supplier<OpenDonutBackend> backend) {
		return Commands.literal("opendonut")
				.requires(source -> Commands.LEVEL_ADMINS.check(source.permissions()))
				.executes(context -> status(context, backend))
				.then(Commands.literal("status").executes(context -> status(context, backend)))
				.then(Commands.literal("region").executes(context -> region(context, backend)))
				.then(Commands.literal("border").executes(context -> border(context, backend)))
				.then(Commands.literal("players").executes(context -> players(context, backend)))
				.then(Commands.literal("route")
						.then(Commands.argument("x", IntegerArgumentType.integer())
								.then(Commands.argument("z", IntegerArgumentType.integer())
										.executes(context -> route(context, backend)))));
	}

	/**
	 * Prints the backend's state.
	 *
	 * @param context the command context
	 * @param backend supplies the backend
	 * @return 1 when the command ran, 0 when OpenDonut is not active
	 */
	private static int status(CommandContext<CommandSourceStack> context, Supplier<OpenDonutBackend> backend) {
		OpenDonutBackend active = backend.get();
		if (active == null) {
			return reportInactive(context);
		}
		send(context, active.describeStatus());
		return 1;
	}

	/**
	 * Prints the region this backend owns.
	 *
	 * @param context the command context
	 * @param backend supplies the backend
	 * @return 1 when the command ran
	 */
	private static int region(CommandContext<CommandSourceStack> context, Supplier<OpenDonutBackend> backend) {
		OpenDonutBackend active = backend.get();
		if (active == null) {
			return reportInactive(context);
		}
		ServerDefinition self = active.self();
		long chunks = (long) (self.bounds().width() / 16) * (self.bounds().depth() / 16);
		send(context, List.of(
				"This server is '" + self.id() + "' at " + self.address(),
				"owns " + self.bounds().describe(),
				"which is " + self.bounds().width() + " x " + self.bounds().depth() + " blocks ("
						+ chunks + " chunks)"));
		return 1;
	}

	/**
	 * Reports what the border-particle wall looks like from where the player is standing.
	 *
	 * <p>The wall is the one part of OpenDonut that cannot be checked from a log line, so it gets a command:
	 * the particle count rises as the player walks towards the boundary and falls to zero in the middle of the
	 * region, which is the whole behaviour in one number.
	 *
	 * @param context the command context
	 * @param backend supplies the backend
	 * @return 1 when the command ran
	 */
	private static int border(CommandContext<CommandSourceStack> context, Supplier<OpenDonutBackend> backend) {
		OpenDonutBackend active = backend.get();
		if (active == null) {
			return reportInactive(context);
		}
		Optional<net.minecraft.server.level.ServerPlayer> player = player(context);
		if (player.isEmpty()) {
			send(context, "Run this in game: the report is about the position of the player asking.");
			send(context, "Settings: " + active.config().borderParticles().describe());
			return 1;
		}
		send(context, active.describeBorderFor(player.get()));
		return 1;
	}

	/**
	 * Lists the transfers currently in flight.
	 *
	 * @param context the command context
	 * @param backend supplies the backend
	 * @return 1 when the command ran
	 */
	private static int players(CommandContext<CommandSourceStack> context, Supplier<OpenDonutBackend> backend) {
		OpenDonutBackend active = backend.get();
		if (active == null) {
			return reportInactive(context);
		}
		var pending = active.tracker().pendingTransfers();
		if (pending.isEmpty()) {
			send(context, "No transfers are in flight. " + active.tracker().describe());
			return 1;
		}
		send(context, "Transfers in flight:");
		for (var transfer : pending) {
			send(context, "  " + transfer.playerId() + " -> '" + transfer.targetServerId() + "' from global ("
					+ transfer.globalX() + ", " + transfer.globalY() + ", " + transfer.globalZ() + ")");
		}
		return 1;
	}

	/**
	 * Answers "who owns this coordinate?" using the same engine the transfer logic uses.
	 *
	 * @param context the command context
	 * @param backend supplies the backend
	 * @return 1 when the command ran
	 */
	private static int route(CommandContext<CommandSourceStack> context, Supplier<OpenDonutBackend> backend) {
		OpenDonutBackend active = backend.get();
		if (active == null) {
			return reportInactive(context);
		}
		int x = IntegerArgumentType.getInteger(context, "x");
		int z = IntegerArgumentType.getInteger(context, "z");

		String header = "(" + x + ", " + z + ") chunk (" + (x >> 4) + ", " + (z >> 4) + "): ";
		List<ServerDefinition> owners = active.index().findRegions(x, z);

		if (owners.isEmpty()) {
			send(context, header + "no server owns this coordinate, so a player standing here cannot be "
					+ "transferred. Either they are inside a gap between regions, or outside the world border.");
			return 1;
		}
		if (owners.size() > 1) {
			send(context, header + owners.size() + " regions claim this coordinate, which is a configuration "
					+ "error: " + owners.stream().map(ServerDefinition::id).toList());
			return 1;
		}

		ServerDefinition owner = owners.get(0);
		String verdict = owner.id().equals(active.self().id())
				? "this server ('" + owner.id() + "'), so a player here stays put"
				: "server '" + owner.id() + "' (" + owner.address() + "), so a player here is moved there";
		send(context, header + verdict);
		return 1;
	}

	/**
	 * Explains that OpenDonut is not running, which is the answer an administrator needs most often.
	 *
	 * @param context the command context
	 * @return 0, because no work was done
	 */
	private static int reportInactive(CommandContext<CommandSourceStack> context) {
		send(context, "OpenDonut is not active on this server. The reason was logged at startup; check for "
				+ "'OpenDonut' in the server log, and validate the configuration with 'java -jar "
				+ "opendonut-tools.jar check config/opendonut.yml'.");
		return 0;
	}

	/**
	 * Sends messages to the command source.
	 *
	 * @param context the command context
	 * @param lines the lines to send
	 */
	private static void send(CommandContext<CommandSourceStack> context, List<String> lines) {
		for (String line : lines) {
			send(context, line);
		}
	}

	/**
	 * Sends a multi-line message to the command source, one Minecraft message per line.
	 *
	 * <p>Splitting matters: a {@code \n} inside a single component is rendered literally by the client, which
	 * makes a report unreadable.
	 *
	 * @param context the command context
	 * @param message the message, possibly containing line breaks
	 */
	private static void send(CommandContext<CommandSourceStack> context, String message) {
		CommandSourceStack source = context.getSource();
		for (String line : message.split("\\R")) {
			source.sendSuccess(() -> Component.literal("[OpenDonut] " + line), false);
		}
	}

	/**
	 * The player who ran the command, for messages that need a name.
	 *
	 * @param context the command context
	 * @return the player, or empty when run from the console
	 */
	private static Optional<net.minecraft.server.level.ServerPlayer> player(
			CommandContext<CommandSourceStack> context) {
		return Optional.ofNullable(context.getSource().getPlayer());
	}
}

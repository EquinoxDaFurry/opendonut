package net.opendonut.velocity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.model.ServerDefinition;
import net.opendonut.common.region.RegionIndex;

/**
 * The proxy's authoritative view of the network: who owns which coordinates, which backends have proven who
 * they are, and which transfers are in flight.
 *
 * <h2>Why the proxy re-derives everything</h2>
 * A backend's {@code TRANSFER_REQUEST} carries the destination it believes is correct, and this class
 * ignores that hint for the decision: it looks the coordinate up in its own copy of the same configuration.
 * Plugin messages are delivered through a player's connection, so a modified client can send one. Even with
 * a valid HMAC — which an attacker who has tricked a backend into forwarding their bytes would hold — the
 * routing decision is only ever made from the proxy's own table.
 *
 * <h2>Verification, not assumption</h2>
 * A backend has to prove, at runtime, that it is the server it claims and that it is generating the same
 * world: same id, same region, same level name, same seed. Until it has, {@code proxy.require-backend-hello}
 * keeps players away from it, because routing into a backend whose terrain differs means a player walks
 * across a boundary into a different landscape.
 *
 * <h2>Thread safety</h2>
 * Velocity dispatches events on more than one thread, so the state is held in concurrent maps of immutable
 * records. No additional locking is needed.
 */
final class ProxyRoutingTable {

	/**
	 * What a backend told the proxy about itself.
	 *
	 * @param serverId the id the backend claims, which must match the proxy's own name for that connection
	 * @param worldName the level name the backend has loaded
	 * @param seed the seed the backend's world was created with
	 * @param bounds the region the backend claims to own
	 * @param minecraftVersion the Minecraft version the backend runs
	 * @param openDonutVersion the OpenDonut build the backend runs
	 * @param verified whether every checked claim matched the central configuration
	 * @param detail the explanation, whether accepted or rejected
	 * @param warnings non-fatal differences worth reporting
	 * @param heardAtMillis when the HELLO arrived
	 */
	record BackendState(
			String serverId,
			String worldName,
			long seed,
			RegionBounds bounds,
			String minecraftVersion,
			String openDonutVersion,
			boolean verified,
			String detail,
			List<String> warnings,
			long heardAtMillis) {

		/**
		 * Renders the state for {@code /opendonut backends}.
		 *
		 * @return a one-line description
		 */
		String describe() {
			StringBuilder out = new StringBuilder(String.format("%-16s %-24s seed=%-20s mc=%-6s ",
					serverId, bounds.describe(), seed, minecraftVersion));
			out.append(verified ? "verified" : "REJECTED");
			if (!verified) {
				out.append(": ").append(detail);
			}
			for (String warning : warnings) {
				out.append(System.lineSeparator()).append("      note: ").append(warning);
			}
			return out.toString();
		}
	}

	/**
	 * A transfer the proxy has started for a player.
	 *
	 * @param playerId the player being moved
	 * @param fromServerId the server they are leaving
	 * @param toServerId the server that owns their new coordinates
	 * @param globalX the global block X to place them at
	 * @param globalY the global Y to place them at
	 * @param globalZ the global block Z to place them at
	 * @param yaw the yaw to preserve
	 * @param pitch the pitch to preserve
	 * @param reason the backend's explanation, for the log
	 * @param issuedAtMillis when the proxy accepted the request
	 * @param dimension the dimension the player was in when they left, reported by the source in a
	 *                  {@code TRANSFER_DIMENSION}; empty when the source did not say (an older build), in
	 *                  which case the destination keeps its own idea of the dimension
	 */
	record PendingTransfer(
			UUID playerId,
			String fromServerId,
			String toServerId,
			int globalX,
			int globalY,
			int globalZ,
			float yaw,
			float pitch,
			String reason,
			long issuedAtMillis,
			String dimension) {
	}

	/**
	 * The verdict on a HELLO.
	 *
	 * @param accepted whether the backend may receive players
	 * @param detail the explanation to send back and to log
	 * @param state the recorded state
	 */
	record Verdict(boolean accepted, String detail, BackendState state) {
	}

	private final OpenDonutConfig config;
	private final RegionIndex index;
	private final Map<String, BackendState> backends = new ConcurrentHashMap<>();
	private final Map<UUID, PendingTransfer> pending = new ConcurrentHashMap<>();

	/**
	 * The last dimension each backend reported for a player, via {@code TRANSFER_DIMENSION}.
	 *
	 * <p>The report arrives on the same connection, immediately before the {@code TRANSFER_REQUEST}, and is
	 * consumed into the transfer when that request is accepted. Kept separate from the transfer map so that
	 * the two messages cannot race each other.
	 */
	private final Map<UUID, String> notedDimensions = new ConcurrentHashMap<>();

	/**
	 * Creates the table.
	 *
	 * @param config the validated configuration
	 */
	ProxyRoutingTable(OpenDonutConfig config) {
		this.config = config;
		this.index = config.regionIndex();
	}

	/** @return the configuration this table was built from */
	OpenDonutConfig config() {
		return config;
	}

	/** @return the region lookup engine */
	RegionIndex index() {
		return index;
	}

	/**
	 * Looks a coordinate up in the proxy's own table.
	 *
	 * @param x global block X
	 * @param z global block Z
	 * @return the owning server, or empty when the coordinate is unassigned
	 */
	Optional<ServerDefinition> owner(int x, int z) {
		return index.findRegion(x, z);
	}

	/**
	 * Records a backend's HELLO and decides whether it may receive players.
	 *
	 * @param connectionServerName the name the proxy uses for the connection the HELLO arrived through
	 * @param claimedId the id the backend claims
	 * @param worldName the level name the backend has loaded
	 * @param seed the backend's seed, or {@link BridgeMessage.Hello#UNKNOWN_SEED}
	 * @param bounds the region the backend claims
	 * @param minecraftVersion the Minecraft version the backend runs
	 * @param openDonutVersion the OpenDonut build the backend runs
	 * @param resolvedSeed the seed the proxy considers authoritative
	 * @param expectedMinecraftVersion the version this build was compiled for
	 * @return the verdict
	 */
	Verdict acceptHello(
			String connectionServerName,
			String claimedId,
			String worldName,
			long seed,
			RegionBounds bounds,
			String minecraftVersion,
			String openDonutVersion,
			long resolvedSeed,
			String expectedMinecraftVersion) {

		List<String> problems = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		if (!connectionServerName.equals(claimedId)) {
			problems.add("it says it is '" + claimedId + "' but the proxy knows that connection as '"
					+ connectionServerName + "'. Either backend.server-id is wrong on that machine, or the "
					+ "servers list in opendonut.yml does not match the names in velocity.toml.");
		}

		Optional<ServerDefinition> configured = config.server(claimedId);
		if (configured.isEmpty()) {
			problems.add("'" + claimedId + "' is not defined in the servers list of "
					+ config.sourceName());
		} else {
			ServerDefinition expected = configured.get();
			if (!expected.bounds().equals(bounds)) {
				// Naming the server that *does* own the claimed rectangle turns "your bounds are wrong" into
				// "you are running with another server's copy of the configuration", which is the actual
				// mistake in the overwhelming majority of cases.
				String belongsTo = config.servers().stream()
						.filter(other -> other.bounds().equals(bounds))
						.map(other -> "; that rectangle belongs to '" + other.id() + "' in this configuration")
						.findFirst()
						.orElse("");
				problems.add("its region is " + bounds.describe() + " but the configuration gives '"
						+ expected.id() + "' " + expected.bounds().describe() + belongsTo);
			}
			if (!worldName.equals(config.world().name())) {
				problems.add("it has loaded level '" + worldName + "' but the configuration says '"
						+ config.world().name() + "'");
			}
			if (seed == BridgeMessage.Hello.UNKNOWN_SEED) {
				warnings.add("it could not report its world seed, so the seed could not be checked");
			} else if (seed != resolvedSeed) {
				problems.add("its world seed is " + seed + " but the network resolved " + resolvedSeed
						+ "; terrain would not line up across the boundary");
			}
		}

		if (!minecraftVersion.equals(expectedMinecraftVersion)) {
			// Not fatal: a proxy can serve several Minecraft versions, and this build's constant is only what
			// it was compiled against.
			warnings.add("it runs Minecraft " + minecraftVersion + " and this build was made for "
					+ expectedMinecraftVersion);
		}

		boolean verified = problems.isEmpty();
		String detail = verified
				? "world, seed and region match the central configuration"
						+ (warnings.isEmpty() ? "" : " (" + String.join("; ", warnings) + ")")
				: String.join("; ", problems);

		BackendState state = new BackendState(claimedId, worldName, seed, bounds, minecraftVersion,
				openDonutVersion, verified, detail, List.copyOf(warnings), System.currentTimeMillis());
		backends.put(claimedId, state);
		return new Verdict(verified, detail, state);
	}

	/**
	 * The last HELLO received from a backend.
	 *
	 * @param serverId the backend's id
	 * @return the state, or empty when nothing has been heard from it
	 */
	Optional<BackendState> backend(String serverId) {
		return Optional.ofNullable(backends.get(serverId));
	}

	/** @return every backend that has announced itself */
	Collection<BackendState> backends() {
		return List.copyOf(backends.values());
	}

	/**
	 * Whether a backend may receive a player, and on what terms.
	 *
	 * <h2>Why there are three answers and not two</h2>
	 * A backend announces itself with a {@code HELLO}, and the only channel it has is a player's connection —
	 * Velocity has no server-to-server plugin message path. So a backend that has never had a player
	 * <em>cannot</em> have announced itself, and a rule of "only transfer to a verified backend" would deadlock
	 * the first transfer to every backend: the transfer is what would give it its first player.
	 *
	 * <p>Hence three states rather than a boolean:
	 * <ul>
	 *   <li>{@link Admission#ADMITTED} — verified, or verification is switched off. Place the player
	 *       immediately.</li>
	 *   <li>{@link Admission#PROVISIONAL} — nothing has been heard from this backend. Move the player, which
	 *       makes it announce itself, but <em>withhold the arrival coordinates</em> until it verifies. If it
	 *       does not verify within the configured window, the player is sent back where they came from.</li>
	 *   <li>{@link Admission#REFUSED} — the backend announced itself and did <em>not</em> match: wrong world,
	 *       seed or region. A player is never moved onto it, because that is the case the verification exists
	 *       to catch. It can be brought back into service by moving a player to it explicitly
	 *       ({@code /server <id> <player>} on the proxy console), which lets it announce itself again.</li>
	 * </ul>
	 */
	enum Admission {

		/** Verified, or verification is disabled: the player may be placed immediately. */
		ADMITTED,

		/** Nothing heard from this backend: admit, but verify during the arrival and withhold the position. */
		PROVISIONAL,

		/** This backend has announced itself and does not match: never send a player to it. */
		REFUSED
	}

	/**
	 * Decides how a backend may receive a player.
	 *
	 * @param serverId the backend's id
	 * @return the admission state
	 */
	Admission admissionFor(String serverId) {
		if (!config.proxy().requireBackendHello()) {
			return Admission.ADMITTED;
		}
		Optional<BackendState> state = backend(serverId);
		if (state.isEmpty()) {
			return Admission.PROVISIONAL;
		}
		return state.get().verified() ? Admission.ADMITTED : Admission.REFUSED;
	}

	/**
	 * The explanation for a refused backend, for a log line or a failure report.
	 *
	 * @param serverId the backend's id
	 * @return the reason the backend reported, or a generic message when nothing has been heard from it
	 */
	String refusalReason(String serverId) {
		return backend(serverId)
				.map(state -> state.verified()
						? "it verifies, so it should not have been refused"
						: state.detail())
				.orElse("nothing has been heard from it");
	}

	/**
	 * Records a transfer the proxy has just started.
	 *
	 * @param transfer the transfer
	 */
	void markPending(PendingTransfer transfer) {
		pending.put(transfer.playerId(), transfer);
	}

	/**
	 * The transfer in flight for a player, if any.
	 *
	 * @param playerId the player
	 * @return the transfer, or empty
	 */
	Optional<PendingTransfer> pending(UUID playerId) {
		return Optional.ofNullable(pending.get(playerId));
	}

	/**
	 * Removes a player's transfer, whatever its outcome.
	 *
	 * @param playerId the player
	 * @return the transfer that was removed, or empty when there was none
	 */
	Optional<PendingTransfer> clearPending(UUID playerId) {
		notedDimensions.remove(playerId);
		return Optional.ofNullable(pending.remove(playerId));
	}

	/**
	 * Drops transfers that are too old to still be meaningful.
	 *
	 * <p>An entry can be left behind when a player disconnects in the middle of a transfer. A stale entry must
	 * never be applied to a later session, which would teleport a player to a place they left long ago.
	 *
	 * @param maxAgeMillis the age after which an entry is abandoned
	 * @return the number of entries removed
	 */
	int pruneStale(long maxAgeMillis) {
		long now = System.currentTimeMillis();
		int removed = 0;
		for (Map.Entry<UUID, PendingTransfer> entry : pending.entrySet()) {
			if (now - entry.getValue().issuedAtMillis() > maxAgeMillis) {
				pending.remove(entry.getKey());
				removed++;
			}
		}
		return removed;
	}

	/** @return how many transfers are in flight */
	int pendingCount() {
		return pending.size();
	}

	/**
	 * Records the dimension a backend just reported for a player.
	 *
	 * @param playerId the player
	 * @param dimension the registry id of the level the player is in, for example {@code minecraft:the_nether}
	 */
	void noteDimension(UUID playerId, String dimension) {
		notedDimensions.put(playerId, dimension);
	}

	/**
	 * Takes the last reported dimension for a player, removing it, so a stale report can never be attached to
	 * a later, unrelated transfer.
	 *
	 * @param playerId the player
	 * @return the dimension, or empty when the source never reported one (an older build)
	 */
	Optional<String> consumeDimension(UUID playerId) {
		return Optional.ofNullable(notedDimensions.remove(playerId));
	}

	/**
	 * Builds the placement message for a transfer.
	 *
	 * @param transfer the transfer
	 * @return the message, addressed to the destination backend
	 */
	BridgeMessage.PlacePlayer placementFor(PendingTransfer transfer) {
		return new BridgeMessage.PlacePlayer(
				transfer.playerId(),
				transfer.fromServerId(),
				transfer.toServerId(),
				transfer.globalX(),
				transfer.globalY(),
				transfer.globalZ(),
				transfer.yaw(),
				transfer.pitch(),
				transfer.issuedAtMillis());
	}

	/**
	 * Builds the dimension instruction for a transfer, to be sent right after the {@code PLACE_PLAYER}.
	 *
	 * @param transfer the transfer
	 * @return the message, or empty when the transfer carries no dimension (the source was an older build),
	 *         in which case the destination keeps its own idea of the dimension, as it always has
	 */
	Optional<BridgeMessage.PlaceDimension> placementDimensionFor(PendingTransfer transfer) {
		if (transfer.dimension().isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new BridgeMessage.PlaceDimension(transfer.playerId(), transfer.dimension()));
	}

	/**
	 * Renders the routing table for {@code /opendonut regions}.
	 *
	 * @param registrationNames the server names the proxy currently has registered, used to highlight a
	 *                          configuration entry that the proxy does not know about
	 * @return a multi-line description
	 */
	String describeRegions(Collection<String> registrationNames) {
		StringBuilder out = new StringBuilder();
		out.append("Routing table (").append(config.servers().size()).append(" region(s), ")
				.append(index.bucketCount()).append(" lookup bucket(s))");
		for (ServerDefinition server : config.servers()) {
			out.append(System.lineSeparator()).append("  ");
			out.append(String.format("%-16s %-24s %s", server.id(), server.address(), server.bounds().describe()));
			if (!registrationNames.contains(server.id())) {
				out.append("  [not registered with the proxy]");
			}
			switch (admissionFor(server.id())) {
				case ADMITTED -> out.append(backend(server.id()).isPresent() ? "  [verified]" : "  [unverified]");
				case PROVISIONAL -> out.append("  [not yet announced — a transfer will verify it on arrival]");
				case REFUSED -> out.append("  [REJECTED]");
			}
		}
		return out.toString();
	}

	/**
	 * Renders the backend states for {@code /opendonut backends}.
	 *
	 * @return a multi-line description, or a placeholder when nothing has been heard yet
	 */
	String describeBackends() {
		Collection<BackendState> states = backends();
		if (states.isEmpty()) {
			return "No backend has announced itself yet. A backend sends its HELLO when its first player "
					+ "connects, because plugin messages travel through a player's connection — so a backend "
					+ "that has never had a player is normal, not broken. Transfers to it are verified while the "
					+ "player arrives.";
		}
		StringBuilder out = new StringBuilder("Backends that have announced themselves:");
		for (BackendState state : states) {
			out.append(System.lineSeparator()).append("  ").append(state.describe());
		}
		return out.toString();
	}
}

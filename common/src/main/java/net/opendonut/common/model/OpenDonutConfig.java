package net.opendonut.common.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.opendonut.common.region.LinearRegionIndex;
import net.opendonut.common.region.RegionIndex;
import net.opendonut.common.region.SlabRegionIndex;

/**
 * An immutable, validated OpenDonut configuration.
 *
 * <p>A single YAML file describes the whole network: the shared world, every backend and the
 * rectangle of the global coordinate space each backend owns. The proxy and every backend load the
 * same file, so adding a server never requires touching Java code — only the YAML, and only in one
 * place.
 *
 * <h2>Immutability and thread safety</h2>
 * Instances are immutable and therefore safe to share between the Velocity event threads and the
 * Minecraft server thread. The region index is built lazily on first use and published safely, so a
 * backend that never performs a lookup does not pay for building it.
 *
 * <h2>How instances are produced</h2>
 * Always through {@link net.opendonut.common.config.ConfigLoader}, never by hand: the loader is what
 * attaches validation issues, and the validator is what guarantees the invariants the rest of the
 * code relies on (no overlaps, ranges not inverted, ids unique).
 */
public final class OpenDonutConfig {

	private final String sourceName;
	private final WorldDefinition world;
	private final List<ServerDefinition> servers;
	private final ValidationOptions validation;
	private final BridgeSettings bridge;
	private final ProxySettings proxy;
	private final TransferSettings transfer;
	private final BackendSettings backend;
	private final BorderParticleSettings borderParticles;

	/**
	 * The lazily built region index. {@code volatile} so that publishing it from whichever thread
	 * happens to perform the first lookup is visible to every other thread.
	 */
	private volatile RegionIndex regionIndex;

	/**
	 * Creates a configuration.
	 *
	 * <p>Prefer {@link net.opendonut.common.config.ConfigLoader#load}; this constructor does not run
	 * semantic validation and will happily accept overlapping regions, which the rest of the code is
	 * not prepared for.
	 *
	 * @param sourceName human readable origin of the configuration, used in messages, usually the file
	 *                   name
	 * @param world the shared world definition
	 * @param servers every backend, in configuration order
	 * @param validation validator behaviour
	 * @param bridge bridge protocol settings
	 * @param proxy proxy-only settings (ignored by backends)
	 * @param transfer backend transfer behaviour
	 * @param backend identity of the instance reading this file
	 * @param borderParticles the cosmetic boundary wall
	 */
	public OpenDonutConfig(
			String sourceName,
			WorldDefinition world,
			List<ServerDefinition> servers,
			ValidationOptions validation,
			BridgeSettings bridge,
			ProxySettings proxy,
			TransferSettings transfer,
			BackendSettings backend,
			BorderParticleSettings borderParticles) {
		this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
		this.world = Objects.requireNonNull(world, "world");
		this.servers = List.copyOf(Objects.requireNonNull(servers, "servers"));
		this.validation = Objects.requireNonNull(validation, "validation");
		this.bridge = Objects.requireNonNull(bridge, "bridge");
		this.proxy = Objects.requireNonNull(proxy, "proxy");
		this.transfer = Objects.requireNonNull(transfer, "transfer");
		this.backend = Objects.requireNonNull(backend, "backend");
		this.borderParticles = Objects.requireNonNull(borderParticles, "borderParticles");
	}

	/** @return the origin of this configuration, usually a file name */
	public String sourceName() {
		return sourceName;
	}

	/** @return the shared world definition */
	public WorldDefinition world() {
		return world;
	}

	/** @return every configured backend, in configuration order */
	public List<ServerDefinition> servers() {
		return servers;
	}

	/** @return validator behaviour */
	public ValidationOptions validation() {
		return validation;
	}

	/** @return bridge protocol settings */
	public BridgeSettings bridge() {
		return bridge;
	}

	/** @return proxy-only settings */
	public ProxySettings proxy() {
		return proxy;
	}

	/** @return backend transfer behaviour */
	public TransferSettings transfer() {
		return transfer;
	}

	/** @return the identity of the instance that loaded this file */
	public BackendSettings backend() {
		return backend;
	}

	/** @return the cosmetic boundary wall a player sees when approaching their region's edge */
	public BorderParticleSettings borderParticles() {
		return borderParticles;
	}

	/** @return the world border */
	public WorldBorderDefinition border() {
		return world.border();
	}

	/**
	 * Looks a backend up by identifier.
	 *
	 * @param id the server id, matched case-sensitively
	 * @return the backend, or empty when no server uses that id
	 */
	public Optional<ServerDefinition> server(String id) {
		if (id == null) {
			return Optional.empty();
		}
		for (ServerDefinition server : servers) {
			if (server.id().equals(id)) {
				return Optional.of(server);
			}
		}
		return Optional.empty();
	}

	/**
	 * Looks a backend up by the socket it listens on.
	 *
	 * <p>This is how a Fabric backend discovers which entry of the central configuration describes
	 * itself when {@code backend.server-id} is not set: it compares its own listening port with the
	 * configured addresses. Loopback spellings are treated as equivalent, see
	 * {@link ServerAddress#isSameSocketAs(ServerAddress)}.
	 *
	 * @param address the listening address to match
	 * @return every backend whose address matches; more than one means the configuration is
	 *         ambiguous and the administrator must set {@code backend.server-id} explicitly
	 */
	public List<ServerDefinition> serversListeningOn(ServerAddress address) {
		List<ServerDefinition> matches = new java.util.ArrayList<>();
		for (ServerDefinition server : servers) {
			if (server.address().isSameSocketAs(address)) {
				matches.add(server);
			}
		}
		return List.copyOf(matches);
	}

	/**
	 * Looks a backend up by TCP port only, ignoring the host.
	 *
	 * <p>Used as a fallback when the backend binds {@code 0.0.0.0} while the configuration names a
	 * specific host. Ports are still required to be unique across the network, which the validator
	 * enforces.
	 *
	 * @param port the listening port
	 * @return every backend configured on that port
	 */
	public List<ServerDefinition> serversOnPort(int port) {
		List<ServerDefinition> matches = new java.util.ArrayList<>();
		for (ServerDefinition server : servers) {
			if (server.address().port() == port) {
				matches.add(server);
			}
		}
		return List.copyOf(matches);
	}

	/**
	 * The region lookup engine for this configuration.
	 *
	 * <p>Built once, lazily, and cached. The returned index is immutable and thread safe.
	 *
	 * @return a lookup index over every configured region
	 */
	public RegionIndex regionIndex() {
		RegionIndex local = regionIndex;
		if (local == null) {
			local = SlabRegionIndex.build(servers);
			regionIndex = local;
		}
		return local;
	}

	/**
	 * Finds the backend that owns a global block coordinate.
	 *
	 * @param x global block X
	 * @param z global block Z
	 * @return the owning backend, or empty when the coordinate is unassigned
	 */
	public Optional<ServerDefinition> findOwner(int x, int z) {
		return regionIndex().findRegion(x, z);
	}

	/**
	 * Builds a {@link LinearRegionIndex}, which scans every region in configuration order.
	 *
	 * <p>Used by the unit tests as an independent reference implementation to cross-check the
	 * production {@link SlabRegionIndex}, and available to administrators who want to prove that a
	 * surprising routing decision is not an artefact of the index structure.
	 *
	 * @return a linear scan index over the same regions
	 */
	public RegionIndex linearIndex() {
		return LinearRegionIndex.build(servers);
	}

	/**
	 * Renders the routing table, one backend per line, in configuration order.
	 *
	 * @return a multi-line description of every region
	 */
	public String describeRoutingTable() {
		StringBuilder builder = new StringBuilder();
		builder.append("OpenDonut routing table (").append(servers.size()).append(" region")
				.append(servers.size() == 1 ? "" : "s").append(")");
		for (int i = 0; i < servers.size(); i++) {
			builder.append(System.lineSeparator()).append("  [").append(i).append("] ")
					.append(servers.get(i).describe());
		}
		if (servers.isEmpty()) {
			builder.append(System.lineSeparator()).append("  (no servers configured)");
		}
		return builder.toString();
	}

	@Override
	public String toString() {
		return "OpenDonutConfig[" + sourceName + ", " + servers.size() + " regions, world " + world.describe() + "]";
	}
}

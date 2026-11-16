package net.opendonut.common.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.SequenceNode;

import net.opendonut.common.model.BackendSettings;
import net.opendonut.common.model.BridgeSettings;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.ProxySettings;
import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.model.SeedSpec;
import net.opendonut.common.model.ServerAddress;
import net.opendonut.common.model.ServerDefinition;
import net.opendonut.common.model.TransferFailurePolicy;
import net.opendonut.common.model.TransferSettings;
import net.opendonut.common.model.UnassignedAreaPolicy;
import net.opendonut.common.model.UnknownFieldPolicy;
import net.opendonut.common.model.ValidationOptions;
import net.opendonut.common.model.WorldBorderDefinition;
import net.opendonut.common.model.WorldDefinition;

/**
 * Turns a composed YAML document into an {@link OpenDonutConfig}.
 *
 * <p>The parser is total: it reports what is wrong and keeps going, so an administrator sees every
 * mistake in one build instead of fixing them one at a time. It only gives up — returning
 * {@link Optional#empty()} — when something required is missing or unreadable, because there is no
 * sensible default for "which world" or "which servers".
 *
 * <p>Defaults are applied for every optional section and are documented in
 * {@code config/opendonut.example.yml} and {@code docs/configuration.md}. The general rule is that
 * OpenDonut never invents a value that changes world data: anything that affects generation, ownership
 * or routing must be written down explicitly.
 *
 * <p>Note on ordering: the {@code validation} section is parsed first because
 * {@code validation.unknown-fields} decides how unknown keys elsewhere are reported.
 */
final class ConfigParser {

	/** The newest schema revision this build understands. */
	static final int SUPPORTED_CONFIG_VERSION = 1;

	private static final Set<String> ROOT_KEYS = Set.of(
			"config-version", "world", "servers", "validation", "bridge", "proxy", "transfer", "backend");
	private static final Set<String> WORLD_KEYS = Set.of("name", "seed", "border");
	private static final Set<String> BORDER_KEYS = Set.of("center-x", "center-z", "size");
	private static final Set<String> SERVER_KEYS = Set.of("id", "address", "bounds");
	private static final Set<String> BOUNDS_KEYS = Set.of("min-x", "max-x", "min-z", "max-z");
	private static final Set<String> VALIDATION_KEYS =
			Set.of("require-full-coverage", "strict-chunk-alignment", "unknown-fields");
	private static final Set<String> BRIDGE_KEYS =
			Set.of("enabled", "channel", "secret-file", "timestamp-tolerance-seconds");
	private static final Set<String> PROXY_KEYS =
			Set.of("auto-register-backends", "require-backend-hello", "disconnect-unrouted-players");
	private static final Set<String> TRANSFER_KEYS = Set.of(
			"boundary-margin-blocks", "join-grace-ticks", "cooldown-seconds", "max-pending-seconds",
			"on-transfer-failure", "unassigned-area-policy");
	private static final Set<String> BACKEND_KEYS = Set.of("server-id", "enabled");

	private final ValidationResult.Collector issues;
	private final YamlAccess yaml;
	private final SourceMap.Builder sourceMap;

	ConfigParser(ValidationResult.Collector issues, SourceMap.Builder sourceMap) {
		this.issues = issues;
		this.yaml = new YamlAccess(issues, sourceMap);
		this.sourceMap = sourceMap;
	}

	/**
	 * Parses a composed document.
	 *
	 * @param root the root node of the document, may be {@code null}
	 * @param sourceName the origin of the document, used in messages
	 * @return the configuration, or empty when required parts are missing
	 */
	Optional<OpenDonutConfig> parse(Node root, String sourceName) {
		MappingNode document = yaml.asMapping(root, "", true);
		if (document == null) {
			return Optional.empty();
		}

		Map<String, Node> rootEntries = yaml.entries(document, "");

		if (!checkConfigVersion(rootEntries)) {
			return Optional.empty();
		}

		// validation first: its unknown-field policy governs the rest of the document.
		ValidationOptions validation = parseValidation(rootEntries.get("validation"));
		UnknownFieldPolicy policy = validation.unknownFields();

		WorldDefinition world = parseWorld(rootEntries.get("world"), policy);
		List<ServerDefinition> servers = parseServers(rootEntries.get("servers"), policy);
		BridgeSettings bridge = parseBridge(rootEntries.get("bridge"), policy);
		ProxySettings proxy = parseProxy(rootEntries.get("proxy"), policy);
		TransferSettings transfer = parseTransfer(rootEntries.get("transfer"), policy);
		BackendSettings backend = parseBackend(rootEntries.get("backend"), policy);

		yaml.reportUnknownKeys(rootEntries, "", ROOT_KEYS, policy);

		if (world == null || servers == null) {
			// The errors have already been reported; without a world and a server list there is nothing
			// meaningful to validate further.
			return Optional.empty();
		}

		return Optional.of(new OpenDonutConfig(
				sourceName, world, servers, validation, bridge, proxy, transfer, backend));
	}

	/**
	 * Validates {@code config-version}.
	 *
	 * @param rootEntries the document entries
	 * @return {@code false} when the document must be rejected
	 */
	private boolean checkConfigVersion(Map<String, Node> rootEntries) {
		Integer declared = yaml.readInt(rootEntries, "config-version", "", SUPPORTED_CONFIG_VERSION, false);
		if (declared == null) {
			return true;
		}
		if (declared > SUPPORTED_CONFIG_VERSION) {
			yaml.report(Severity.ERROR, IssueCode.UNSUPPORTED_CONFIG_VERSION, "config-version",
					YamlAccess.lineOf(rootEntries.get("config-version")),
					"configuration version " + declared + " is newer than this build understands ("
							+ SUPPORTED_CONFIG_VERSION + "); upgrade OpenDonut or lower the version");
			return false;
		}
		if (declared < 1) {
			yaml.report(IssueCode.INVALID_VALUE, "config-version",
					YamlAccess.lineOf(rootEntries.get("config-version")),
					"configuration version must be at least 1");
			return false;
		}
		return true;
	}

	private WorldDefinition parseWorld(Node node, UnknownFieldPolicy policy) {
		MappingNode mapping = yaml.asMapping(node, "world", true);
		if (mapping == null) {
			return null;
		}
		sourceMap.put("world", YamlAccess.lineOf(node));
		Map<String, Node> entries = yaml.entries(mapping, "world");
		sourceMap.put("world.seed", YamlAccess.lineOf(entries.get("seed")));
		sourceMap.put("world.name", YamlAccess.lineOf(entries.get("name")));

		String name = yaml.readString(entries, "name", "world", "world", true);
		if (name != null && name.isBlank()) {
			yaml.report(IssueCode.WORLD_NAME_INVALID, "world.name", YamlAccess.lineOf(entries.get("name")),
					"world name must not be blank");
			name = "world";
		}

		SeedSpec seed = SeedSpec.random();
		String rawSeed = yaml.readRawScalar(entries, "seed", "world", false);
		if (rawSeed != null) {
			try {
				seed = SeedSpec.parse(rawSeed);
			} catch (IllegalArgumentException e) {
				yaml.report(IssueCode.SEED_INVALID, "world.seed", YamlAccess.lineOf(entries.get("seed")),
						e.getMessage());
			}
		}

		WorldBorderDefinition border = parseBorder(entries.get("border"), policy);
		yaml.reportUnknownKeys(entries, "world", WORLD_KEYS, policy);

		if (border == null) {
			return null;
		}
		return new WorldDefinition(name == null ? "world" : name, seed, border);
	}

	private WorldBorderDefinition parseBorder(Node node, UnknownFieldPolicy policy) {
		MappingNode mapping = yaml.asMapping(node, "world.border", true);
		if (mapping == null) {
			return null;
		}
		sourceMap.put("world.border", YamlAccess.lineOf(node));
		Map<String, Node> entries = yaml.entries(mapping, "world.border");
		sourceMap.put("world.border.size", YamlAccess.lineOf(entries.get("size")));

		Integer size = yaml.readInt(entries, "size", "world.border", null, true);
		Integer centerX = yaml.readInt(entries, "center-x", "world.border", 0, false);
		Integer centerZ = yaml.readInt(entries, "center-z", "world.border", 0, false);
		yaml.reportUnknownKeys(entries, "world.border", BORDER_KEYS, policy);

		if (size == null) {
			return null;
		}
		try {
			return new WorldBorderDefinition(centerX == null ? 0 : centerX, centerZ == null ? 0 : centerZ, size);
		} catch (IllegalArgumentException e) {
			yaml.report(IssueCode.BORDER_INVALID, "world.border.size", YamlAccess.lineOf(entries.get("size")),
					e.getMessage());
			return null;
		}
	}

	private List<ServerDefinition> parseServers(Node node, UnknownFieldPolicy policy) {
		SequenceNode sequence = yaml.asSequence(node, "servers", true);
		if (sequence == null) {
			return null;
		}
		sourceMap.put("servers", YamlAccess.lineOf(node));
		List<ServerDefinition> servers = new ArrayList<>();
		int index = 0;
		for (Node element : sequence.getValue()) {
			String path = YamlAccess.index("servers", index);
			ServerDefinition server = parseServer(element, path, policy);
			if (server != null) {
				servers.add(server);
			}
			index++;
		}
		return servers;
	}

	private ServerDefinition parseServer(Node node, String path, UnknownFieldPolicy policy) {
		MappingNode mapping = yaml.asMapping(node, path, true);
		if (mapping == null) {
			return null;
		}
		Map<String, Node> entries = yaml.entries(mapping, path);

		sourceMap.put(path, YamlAccess.lineOf(node));
		sourceMap.put(YamlAccess.child(path, "id"), YamlAccess.lineOf(entries.get("id")));
		sourceMap.put(YamlAccess.child(path, "address"), YamlAccess.lineOf(entries.get("address")));

		String id = yaml.readString(entries, "id", path, null, true);
		String rawAddress = yaml.readString(entries, "address", path, null, true);
		RegionBounds bounds = parseBounds(entries.get("bounds"), path, policy);
		yaml.reportUnknownKeys(entries, path, SERVER_KEYS, policy);

		if (id == null || rawAddress == null || bounds == null) {
			return null;
		}

		ServerAddress address;
		try {
			address = ServerAddress.parse(rawAddress);
		} catch (IllegalArgumentException e) {
			yaml.report(IssueCode.ADDRESS_INVALID, YamlAccess.child(path, "address"),
					YamlAccess.lineOf(entries.get("address")), e.getMessage());
			return null;
		}

		try {
			return new ServerDefinition(id, address, bounds);
		} catch (IllegalArgumentException e) {
			yaml.report(IssueCode.SERVER_ID_INVALID, YamlAccess.child(path, "id"),
					YamlAccess.lineOf(entries.get("id")), e.getMessage());
			return null;
		}
	}

	private RegionBounds parseBounds(Node node, String parentPath, UnknownFieldPolicy policy) {
		String path = YamlAccess.child(parentPath, "bounds");
		MappingNode mapping = yaml.asMapping(node, path, true);
		if (mapping == null) {
			return null;
		}
		sourceMap.put(path, YamlAccess.lineOf(node));
		Map<String, Node> entries = yaml.entries(mapping, path);

		Integer minX = yaml.readInt(entries, "min-x", path, null, true);
		Integer maxX = yaml.readInt(entries, "max-x", path, null, true);
		Integer minZ = yaml.readInt(entries, "min-z", path, null, true);
		Integer maxZ = yaml.readInt(entries, "max-z", path, null, true);
		yaml.reportUnknownKeys(entries, path, BOUNDS_KEYS, policy);

		if (minX == null || maxX == null || minZ == null || maxZ == null) {
			return null;
		}
		// Deliberately unboxed into primitives before any comparison. `Integer` values outside the JVM's
		// small-value cache (-128..127) are *different objects* for equal numbers, so `maxX == minX` would
		// silently be false for real-world coordinates and an empty region would slip through.
		int minXValue = minX;
		int maxXValue = maxX;
		int minZValue = minZ;
		int maxZValue = maxZ;

		if (maxXValue < minXValue || maxZValue < minZValue) {
			yaml.report(IssueCode.BOUNDS_INVALID, path, YamlAccess.lineOf(mapping),
					"region bounds are inverted: max must be greater than min (got "
							+ new RegionBounds(Math.min(minXValue, maxXValue), Math.max(minXValue, maxXValue),
									Math.min(minZValue, maxZValue), Math.max(minZValue, maxZValue)).describe()
							+ ")");
			return null;
		}
		if (maxXValue == minXValue || maxZValue == minZValue) {
			yaml.report(IssueCode.BOUNDS_INVALID, path, YamlAccess.lineOf(mapping),
					"region covers no block: X=[" + minXValue + "," + maxXValue + ") Z=[" + minZValue + ","
							+ maxZValue + "). A region needs a positive width and depth, otherwise it owns "
							+ "nothing and no player can ever be routed to it.");
			return null;
		}
		return new RegionBounds(minXValue, maxXValue, minZValue, maxZValue);
	}

	private ValidationOptions parseValidation(Node node) {
		MappingNode mapping = yaml.asMapping(node, "validation", false);
		ValidationOptions options = ValidationOptions.DEFAULT;
		if (mapping == null) {
			return options;
		}
		sourceMap.put("validation", YamlAccess.lineOf(node));
		Map<String, Node> entries = yaml.entries(mapping, "validation");

		Boolean fullCoverage = yaml.readBoolean(entries, "require-full-coverage", "validation", null);
		Boolean strictAlignment = yaml.readBoolean(entries, "strict-chunk-alignment", "validation", null);
		UnknownFieldPolicy policy = yaml.readEnum(entries, "unknown-fields", "validation",
				options.unknownFields(), UnknownFieldPolicy::parse, "ignore, warn, error");

		yaml.reportUnknownKeys(entries, "validation", VALIDATION_KEYS, policy);

		if (fullCoverage != null) {
			options = options.withRequireFullCoverage(fullCoverage);
		}
		if (strictAlignment != null) {
			options = options.withStrictChunkAlignment(strictAlignment);
		}
		return options.withUnknownFields(policy);
	}

	private BridgeSettings parseBridge(Node node, UnknownFieldPolicy policy) {
		MappingNode mapping = yaml.asMapping(node, "bridge", false);
		BridgeSettings settings = BridgeSettings.DEFAULT;
		if (mapping == null) {
			return settings;
		}
		sourceMap.put("bridge", YamlAccess.lineOf(node));
		Map<String, Node> entries = yaml.entries(mapping, "bridge");

		Boolean enabled = yaml.readBoolean(entries, "enabled", "bridge", settings.enabled());
		String channel = yaml.readString(entries, "channel", "bridge", settings.channel(), false);
		String secretFile = yaml.readString(entries, "secret-file", "bridge", settings.secretFile(), false);
		Integer tolerance = yaml.readInt(entries, "timestamp-tolerance-seconds", "bridge",
				settings.timestampToleranceSeconds(), false);
		yaml.reportUnknownKeys(entries, "bridge", BRIDGE_KEYS, policy);

		try {
			return new BridgeSettings(enabled, channel, secretFile, tolerance);
		} catch (IllegalArgumentException e) {
			yaml.report(IssueCode.INVALID_VALUE, "bridge", YamlAccess.lineOf(mapping), e.getMessage());
			return settings;
		}
	}

	private ProxySettings parseProxy(Node node, UnknownFieldPolicy policy) {
		MappingNode mapping = yaml.asMapping(node, "proxy", false);
		ProxySettings settings = ProxySettings.DEFAULT;
		if (mapping == null) {
			return settings;
		}
		sourceMap.put("proxy", YamlAccess.lineOf(node));
		Map<String, Node> entries = yaml.entries(mapping, "proxy");

		Boolean autoRegister = yaml.readBoolean(entries, "auto-register-backends", "proxy",
				settings.autoRegisterBackends());
		Boolean requireHello = yaml.readBoolean(entries, "require-backend-hello", "proxy",
				settings.requireBackendHello());
		Boolean disconnect = yaml.readBoolean(entries, "disconnect-unrouted-players", "proxy",
				settings.disconnectUnroutedPlayers());
		yaml.reportUnknownKeys(entries, "proxy", PROXY_KEYS, policy);

		return new ProxySettings(autoRegister, requireHello, disconnect);
	}

	private TransferSettings parseTransfer(Node node, UnknownFieldPolicy policy) {
		MappingNode mapping = yaml.asMapping(node, "transfer", false);
		TransferSettings settings = TransferSettings.DEFAULT;
		if (mapping == null) {
			return settings;
		}
		sourceMap.put("transfer", YamlAccess.lineOf(node));
		Map<String, Node> entries = yaml.entries(mapping, "transfer");

		Integer margin = yaml.readInt(entries, "boundary-margin-blocks", "transfer",
				settings.boundaryMarginBlocks(), false);
		Integer grace = yaml.readInt(entries, "join-grace-ticks", "transfer", settings.joinGraceTicks(), false);
		Integer cooldown = yaml.readInt(entries, "cooldown-seconds", "transfer", settings.cooldownSeconds(), false);
		Integer maxPending = yaml.readInt(entries, "max-pending-seconds", "transfer",
				settings.maxPendingSeconds(), false);
		TransferFailurePolicy failure = yaml.readEnum(entries, "on-transfer-failure", "transfer",
				settings.onTransferFailure(), TransferFailurePolicy::parse, "revert, log-only");
		UnassignedAreaPolicy unassigned = yaml.readEnum(entries, "unassigned-area-policy", "transfer",
				settings.unassignedAreaPolicy(), UnassignedAreaPolicy::parse, "allow, return");
		yaml.reportUnknownKeys(entries, "transfer", TRANSFER_KEYS, policy);

		try {
			return new TransferSettings(margin, grace, cooldown, maxPending, failure, unassigned);
		} catch (IllegalArgumentException e) {
			yaml.report(IssueCode.INVALID_VALUE, "transfer", YamlAccess.lineOf(mapping), e.getMessage());
			return settings;
		}
	}

	private BackendSettings parseBackend(Node node, UnknownFieldPolicy policy) {
		MappingNode mapping = yaml.asMapping(node, "backend", false);
		BackendSettings settings = BackendSettings.DEFAULT;
		if (mapping == null) {
			return settings;
		}
		sourceMap.put("backend", YamlAccess.lineOf(node));
		Map<String, Node> entries = yaml.entries(mapping, "backend");

		String serverId = yaml.readString(entries, "server-id", "backend", null, false);
		Boolean enabled = yaml.readBoolean(entries, "enabled", "backend", settings.enabled());
		yaml.reportUnknownKeys(entries, "backend", BACKEND_KEYS, policy);

		if (serverId == null || serverId.isBlank()) {
			return new BackendSettings(Optional.empty(), enabled);
		}
		try {
			return new BackendSettings(Optional.of(serverId.trim()), enabled);
		} catch (IllegalArgumentException e) {
			yaml.report(IssueCode.SERVER_ID_INVALID, "backend.server-id",
					YamlAccess.lineOf(entries.get("server-id")), e.getMessage());
			return new BackendSettings(Optional.empty(), enabled);
		}
	}

	/**
	 * The key names accepted in the root of the document, exposed for documentation tooling.
	 *
	 * @return an immutable, ordered set of accepted root keys
	 */
	static Set<String> rootKeys() {
		return new LinkedHashSet<>(ROOT_KEYS);
	}
}

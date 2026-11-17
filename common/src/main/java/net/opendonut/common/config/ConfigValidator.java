package net.opendonut.common.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.opendonut.common.model.BorderParticleSettings;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.RegionBounds;
import net.opendonut.common.model.ServerDefinition;
import net.opendonut.common.model.ValidationOptions;
import net.opendonut.common.model.WorldBorderDefinition;
import net.opendonut.common.region.RegionCoverage;

/**
 * Semantic validation: everything the YAML parser cannot know.
 *
 * <h2>What is checked</h2>
 * <ol>
 *   <li>the world border is chunk aligned, otherwise no region can be;</li>
 *   <li>at least one server is configured;</li>
 *   <li>server ids are unique, and server sockets are unique;</li>
 *   <li>every region is non-empty (checked while parsing) and chunk aligned;</li>
 *   <li>no region reaches outside the world border;</li>
 *   <li>no two regions claim the same block, which is the one condition that corrupts worlds;</li>
 *   <li>the whole border is covered, or the gap is reported as a warning — and as an error when
 *       {@code validation.require-full-coverage} is set;</li>
 *   <li>the identity of the reading instance can be resolved (backends only);</li>
 *   <li>advisory findings: disabled bridge, a cooldown that has no effect, coverage statistics.</li>
 * </ol>
 *
 * <h2>Errors versus warnings, and why</h2>
 * Anything that makes two servers write the same chunk, that routes a player to the wrong place, or
 * that makes the world generation inconsistent between backends is an {@link Severity#ERROR}. Anything
 * that is a deliberate design choice — leaving wilderness unassigned, disabling the bridge while
 * setting up — is a {@link Severity#WARNING} or {@link Severity#INFO}, gated by the options in
 * {@link ValidationOptions}. The validator never rejects a configuration merely because it describes a
 * network that is still being built: it reports precisely what is missing.
 */
public final class ConfigValidator {

	/** How many unassigned rectangles a report should sample. */
	private static final int GAP_SAMPLE_LIMIT = 8;

	private final ValidationResult.Collector issues;
	private final SourceMap sourceMap;

	/**
	 * Creates a validator.
	 *
	 * @param issues the collector that receives findings
	 * @param sourceMap the locations recorded while parsing, used to point at lines
	 */
	public ConfigValidator(ValidationResult.Collector issues, SourceMap sourceMap) {
		this.issues = issues;
		this.sourceMap = sourceMap == null ? SourceMap.EMPTY : sourceMap;
	}

	/**
	 * Validates a configuration.
	 *
	 * @param config the configuration to check
	 * @return the same collector, for chaining
	 */
	public ValidationResult.Collector validate(OpenDonutConfig config) {
		WorldBorderDefinition border = config.border();
		ValidationOptions options = config.validation();

		validateBorder(border, options);
		validateServers(config, options);
		validateCoverage(config, options);
		validateIdentity(config);
		validateTime(config);
		validateAdvisories(config);
		return issues;
	}

	private void validateBorder(WorldBorderDefinition border, ValidationOptions options) {
		int line = sourceMap.line("world.border");
		RegionBounds bounds = border.asBounds();

		if (bounds.isEmpty()) {
			issues.add(IssueCode.BORDER_INVALID, "world.border", line,
					"the world border covers no block: " + bounds.describe());
		}
		if (!border.isChunkAligned()) {
			Severity severity = options.strictChunkAlignment() ? Severity.ERROR : Severity.WARNING;
			issues.add(severity, IssueCode.BORDER_NOT_CHUNK_ALIGNED, "world.border", line,
					"the world border is not aligned to 16-block chunks (" + bounds.describe()
							+ "); every server region inside it can then only be misaligned too. "
							+ "Use a size that is a multiple of 16 and a centre that is a multiple of 16.");
		}
		issues.add(IssueCode.DERIVED_VALUE, "world.border", line,
				"world border is " + bounds.describe() + " (" + bounds.area() + " blocks, "
						+ (bounds.width() / 16) + " x " + (bounds.depth() / 16) + " chunks)");
	}

	private void validateServers(OpenDonutConfig config, ValidationOptions options) {
		List<ServerDefinition> servers = config.servers();

		if (servers.isEmpty()) {
			issues.add(IssueCode.NO_SERVERS, "servers", sourceMap.line("servers"),
					"no servers are configured, so no coordinate can be routed");
			return;
		}

		// --- unique ids and unique sockets -----------------------------------
		Map<String, Integer> firstIndexById = new LinkedHashMap<>();
		Map<String, Integer> firstIndexBySocket = new LinkedHashMap<>();

		for (int i = 0; i < servers.size(); i++) {
			ServerDefinition server = servers.get(i);
			String path = "servers[" + i + "]";
			int line = sourceMap.line(path);

			Integer previous = firstIndexById.putIfAbsent(server.id(), i);
			if (previous != null) {
				issues.add(IssueCode.DUPLICATE_SERVER_ID, path + ".id", sourceMap.line(path + ".id"),
						"server id '" + server.id() + "' is already used by servers[" + previous
								+ "]; ids must be unique because they are the routing key");
			}

			String socketKey = server.address().toString().toLowerCase(java.util.Locale.ROOT);
			Integer previousSocket = firstIndexBySocket.putIfAbsent(socketKey, i);
			if (previousSocket == null) {
				// Loopback spellings can differ textually while naming the same socket, so compare
				// against everything seen so far as well.
				for (int j = 0; j < i; j++) {
					if (servers.get(j).address().isSameSocketAs(server.address())) {
						previousSocket = j;
						break;
					}
				}
			}
			if (previousSocket != null && previousSocket != i) {
				issues.add(IssueCode.DUPLICATE_SERVER_ADDRESS, path + ".address",
						sourceMap.line(path + ".address"),
						"address " + server.address() + " is already used by servers[" + previousSocket
								+ "]");
			}

			validateRegion(server, path, line, config, options);
		}

		// --- ports must be unique, or backend auto-detection becomes ambiguous -
		Map<Integer, List<String>> byPort = new LinkedHashMap<>();
		for (ServerDefinition server : servers) {
			byPort.computeIfAbsent(server.address().port(), key -> new ArrayList<>()).add(server.id());
		}
		for (Map.Entry<Integer, List<String>> entry : byPort.entrySet()) {
			if (entry.getValue().size() > 1) {
				issues.add(IssueCode.BACKEND_IDENTITY_AMBIGUOUS, "servers", sourceMap.line("servers"),
						"port " + entry.getKey() + " is used by more than one server ("
								+ String.join(", ", entry.getValue())
								+ "), so a backend cannot discover its own entry by port; "
								+ "set backend.server-id explicitly on those machines");
			}
		}
	}

	private void validateRegion(
			ServerDefinition server,
			String path,
			int line,
			OpenDonutConfig config,
			ValidationOptions options) {

		RegionBounds bounds = server.bounds();
		RegionBounds border = config.border().asBounds();

		if (!bounds.isChunkAligned()) {
			Severity severity = options.strictChunkAlignment() ? Severity.ERROR : Severity.WARNING;
			issues.add(severity, IssueCode.BOUNDS_NOT_CHUNK_ALIGNED, path + ".bounds",
					sourceMap.line(path + ".bounds"),
					"region '" + server.id() + "' is not aligned to 16-block chunks: " + bounds.describe()
							+ ". A chunk that straddles the boundary would be generated and written by two "
							+ "servers. Snap each edge to a multiple of 16, for example "
							+ snapToChunks(bounds).describe() + ".");
		}

		if (!border.contains(bounds.minX(), bounds.minZ()) || !containsExclusiveMax(border, bounds)) {
			StringBuilder outside = new StringBuilder();
			if (bounds.minX() < border.minX()) {
				outside.append(" min-x is ").append(border.minX() - bounds.minX()).append(" blocks outside;");
			}
			if (bounds.maxX() > border.maxX()) {
				outside.append(" max-x is ").append(bounds.maxX() - border.maxX()).append(" blocks outside;");
			}
			if (bounds.minZ() < border.minZ()) {
				outside.append(" min-z is ").append(border.minZ() - bounds.minZ()).append(" blocks outside;");
			}
			if (bounds.maxZ() > border.maxZ()) {
				outside.append(" max-z is ").append(bounds.maxZ() - border.maxZ()).append(" blocks outside;");
			}
			if (outside.isEmpty()) {
				outside.append(" the region is not fully inside the border;");
			}
			issues.add(IssueCode.REGION_OUTSIDE_BORDER, path + ".bounds", sourceMap.line(path + ".bounds"),
					"region '" + server.id() + "' (" + bounds.describe() + ") is not inside the world border ("
							+ border.describe() + "):" + outside
							+ " players cannot reach the part outside the border, so it can never be owned");
		}

		issues.add(IssueCode.DERIVED_VALUE, path, line,
				"region '" + server.id() + "' " + bounds.describe() + " (" + bounds.area() + " blocks, "
						+ (bounds.width() / 16) + " x " + (bounds.depth() / 16) + " chunks) at "
						+ server.address());
	}

	/**
	 * Tests whether a region's exclusive maximum edges stay inside the border.
	 *
	 * <p>{@code RegionBounds.contains} is inclusive of its minimum and exclusive of its maximum, so the
	 * maximum edge needs a dedicated test: a region whose {@code maxX} equals the border's {@code maxX}
	 * is exactly inside, while a larger value sticks out.
	 *
	 * @param border the border
	 * @param bounds the region
	 * @return {@code true} if both maximum edges are within the border
	 */
	private static boolean containsExclusiveMax(RegionBounds border, RegionBounds bounds) {
		return bounds.maxX() <= border.maxX() && bounds.maxZ() <= border.maxZ();
	}

	/**
	 * Suggests chunk-aligned bounds for a misaligned region.
	 *
	 * @param bounds the offending region
	 * @return the suggested region
	 */
	private static RegionBounds snapToChunks(RegionBounds bounds) {
		return new RegionBounds(
				net.opendonut.common.model.ChunkMath.floorToChunk(bounds.minX()),
				net.opendonut.common.model.ChunkMath.ceilToChunk(bounds.maxX()),
				net.opendonut.common.model.ChunkMath.floorToChunk(bounds.minZ()),
				net.opendonut.common.model.ChunkMath.ceilToChunk(bounds.maxZ()));
	}

	private void validateCoverage(OpenDonutConfig config, ValidationOptions options) {
		if (config.servers().isEmpty()) {
			return;
		}
		RegionCoverage coverage = RegionCoverage.analyze(config.servers(), config.border().asBounds(),
				GAP_SAMPLE_LIMIT);

		for (RegionCoverage.Overlap overlap : coverage.overlaps()) {
			issues.add(IssueCode.REGION_OVERLAP, "servers", sourceMap.line("servers"),
					"regions '" + overlap.firstId() + "' and '" + overlap.secondId() + "' both claim "
							+ overlap.sharedArea() + " blocks (" + overlap.example().describe()
							+ "). Two servers writing the same chunk corrupts the world; make the shared edge "
							+ "exclusive on exactly one side.");
		}

		if (!coverage.isFullyCovered()) {
			Severity severity = options.requireFullCoverage() ? Severity.ERROR : Severity.WARNING;
			String hint = coverage.overlaps().isEmpty()
					? " A common cause is a region whose max-x or max-z is one block short."
					: " Overlaps are also present, so fix those first: an overlap can hide a gap.";
			issues.add(severity, IssueCode.WORLD_UNCOVERED, "servers", sourceMap.line("servers"),
					String.format("%d of %d blocks (%.3f%%) inside the world border are owned by no server",
							coverage.unassignedArea(), coverage.areaInsideBorder(), coverage.unassignedPercent())
							+ hint + System.lineSeparator()
							+ sampleGaps(coverage.gaps(), coverage.gapsTruncated())
							+ (options.requireFullCoverage()
									? ""
									: System.lineSeparator()
											+ "    Set validation.require-full-coverage: false (the default) to "
											+ "accept unassigned areas, or transfer.unassigned-area-policy: return "
											+ "to push players back."));
		}

		issues.add(IssueCode.DERIVED_VALUE, "servers", sourceMap.line("servers"),
				String.format("coverage: %.3f%% of the world border is assigned (%d of %d blocks), "
						+ "using %d lookup buckets", coverage.coveredPercent(), coverage.coveredArea(),
						coverage.areaInsideBorder(), coverage.buckets()));
	}

	private static String sampleGaps(List<RegionBounds> gaps, boolean truncated) {
		StringBuilder out = new StringBuilder();
		out.append("    unassigned areas (largest first");
		out.append(truncated ? ", list truncated" : "");
		out.append("):");
		for (RegionBounds gap : gaps) {
			out.append(System.lineSeparator()).append("      ").append(gap.describe())
					.append(" (").append(gap.area()).append(" blocks)");
		}
		return out.toString();
	}

	private void validateIdentity(OpenDonutConfig config) {
		if (config.backend().serverId().isEmpty()) {
			return;
		}
		String wanted = config.backend().serverId().get();
		if (config.server(wanted).isEmpty()) {
			List<String> known = new ArrayList<>();
			for (ServerDefinition server : config.servers()) {
				known.add("'" + server.id() + "'");
			}
			issues.add(IssueCode.BACKEND_IDENTITY_UNRESOLVED, "backend.server-id",
					sourceMap.line("backend"),
					"backend.server-id is '" + wanted + "' but no server has that id; known ids: "
							+ (known.isEmpty() ? "(none configured)" : String.join(", ", known)));
		}
	}

	/**
	 * Validates the {@code time} section against the server list.
	 *
	 * <p>An explicit {@code time.authority} must name a configured server: a typo there would silently leave the
	 * network without an authority, and every backend's clock would stay its own. {@code auto} needs no check —
	 * it resolves to the first backend the proxy verifies.
	 *
	 * @param config the configuration to check
	 */
	private void validateTime(OpenDonutConfig config) {
		net.opendonut.common.model.TimeSettings time = config.time();
		if (!time.enabled() || time.authorityIsAuto()) {
			return;
		}
		String wanted = time.explicitAuthority();
		if (config.server(wanted).isEmpty()) {
			List<String> known = new ArrayList<>();
			for (ServerDefinition server : config.servers()) {
				known.add("'" + server.id() + "'");
			}
			issues.add(IssueCode.TIME_AUTHORITY_UNKNOWN, "time.authority", sourceMap.line("time"),
					"time.authority is '" + wanted + "' but no server has that id; known ids: "
							+ (known.isEmpty() ? "(none configured)" : String.join(", ", known))
							+ ". Use '" + net.opendonut.common.model.TimeSettings.AUTHORITY_AUTO
							+ "' to let the proxy pick the first verified backend.");
			return;
		}
		issues.add(IssueCode.DERIVED_VALUE, "time", sourceMap.line("time"),
				"world time sync: " + time.describe() + "; the authority is '" + wanted + "'");
	}

	private void validateAdvisories(OpenDonutConfig config) {
		if (!config.bridge().enabled()) {
			issues.add(IssueCode.BRIDGE_DISABLED, "bridge", sourceMap.line("bridge"),
					"the bridge is disabled: players will never be transferred between servers, and the "
							+ "coordinate system will not behave as one continuous world");
		}
		if (config.transfer().cooldownIsShadowedByPendingTimeout()) {
			issues.add(IssueCode.DERIVED_VALUE, "transfer", sourceMap.line("transfer"),
					"transfer.max-pending-seconds (" + config.transfer().maxPendingSeconds()
							+ ") is larger than transfer.cooldown-seconds ("
							+ config.transfer().cooldownSeconds()
							+ "), so the pending timeout is what actually controls how often a failed "
							+ "transfer is retried");
		}
		if (config.servers().size() == 1) {
			issues.add(IssueCode.DERIVED_VALUE, "servers", sourceMap.line("servers"),
					"only one server is configured: OpenDonut will never transfer a player, which is a "
							+ "valid way to stage a rollout");
		}

		// The border-particle wall is cosmetic, but two of its settings interact: the wall is a disc, so the
		// number of particles needed grows with the square of the radius, and a budget below that silently
		// trims the wall a player sees at close range. Worth saying, since the symptom (a sparse wall) looks
		// like a bug rather than a setting.
		BorderParticleSettings particles = config.borderParticles();
		if (particles.enabled()) {
			long discBlocks = Math.round(Math.PI * particles.radius() * particles.radius());
			issues.add(IssueCode.DERIVED_VALUE, "border-particles", sourceMap.line("border-particles"),
					"border particles: " + particles.describe() + "; the wall is about " + discBlocks
							+ " block(s) across at its widest");
			if (particles.maxPerSide() < discBlocks) {
				issues.add(IssueCode.DERIVED_VALUE, "border-particles", sourceMap.line("border-particles"),
						"border-particles.max-per-side (" + particles.maxPerSide()
								+ ") is smaller than the wall a radius of " + particles.radius()
								+ " produces (" + discBlocks + " block(s) per side), so the nearest particles "
								+ "are kept and the wall is trimmed at close range. Raise it to "
								+ discBlocks + " for an untrimmed wall.");
			}
		} else {
			issues.add(IssueCode.DERIVED_VALUE, "border-particles", sourceMap.line("border-particles"),
					"border particles are disabled: players will not see their region's edge");
		}

		// The proxy holds an arriving player on a backend that has not announced itself yet, and the backend
		// gives up on the whole transfer after transfer.max-pending-seconds. If the proxy's window is the
		// longer of the two, the backend reverts the player while the proxy is still deciding, and the two
		// sides race for the same player. Not fatal - the player ends up somewhere safe either way - but it
		// makes failures confusing, so it is worth saying out loud.
		long proxyWindow = config.proxy().arrivalVerificationTimeoutMillis();
		long backendWindow = config.transfer().maxPendingSeconds() * 1000L;
		if (proxyWindow >= backendWindow) {
			issues.add(IssueCode.DERIVED_VALUE, "proxy", sourceMap.line("proxy"),
					"proxy.arrival-verification-timeout-seconds (" + proxyWindow / 1000
							+ " s) is not shorter than transfer.max-pending-seconds ("
							+ config.transfer().maxPendingSeconds()
							+ " s), so a backend can give up on a transfer before the proxy has finished "
							+ "verifying the destination. Lower the proxy timeout, or raise the backend one.");
		}
	}

	/**
	 * Counts the unique server ids in a configuration, used by tests and the CLI report.
	 *
	 * @param config the configuration
	 * @return the number of distinct ids
	 */
	static int uniqueIdCount(OpenDonutConfig config) {
		Map<String, Integer> seen = new HashMap<>();
		for (ServerDefinition server : config.servers()) {
			seen.merge(server.id(), 1, Integer::sum);
		}
		return seen.size();
	}
}

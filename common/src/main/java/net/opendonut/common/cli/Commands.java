package net.opendonut.common.cli;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import net.opendonut.common.bridge.BridgeCodec;
import net.opendonut.common.bridge.BridgeSecret;
import net.opendonut.common.config.ConfigLoadResult;
import net.opendonut.common.config.ConfigLoader;
import net.opendonut.common.config.IssueCode;
import net.opendonut.common.config.SeedResolution;
import net.opendonut.common.config.SeedStore;
import net.opendonut.common.config.Severity;
import net.opendonut.common.config.ValidationIssue;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.ServerDefinition;
import net.opendonut.common.region.RegionCoverage;
import net.opendonut.common.region.RegionIndex;

/**
 * Implementations of the OpenDonut command line subcommands.
 *
 * <p>Kept separate from {@link Main} so that the behaviour of each command can be unit tested without
 * capturing standard output, and so that the exit codes are defined in exactly one place.
 */
final class Commands {

	/** Everything went as asked. */
	static final int EXIT_OK = 0;
	/** The configuration exists but has errors, or could not be read. */
	static final int EXIT_CONFIG_ERROR = 1;
	/** The query was valid but the answer was "nothing owns that coordinate". */
	static final int EXIT_UNASSIGNED = 2;
	/** The command line itself was wrong. */
	static final int EXIT_USAGE = 3;

	private final PrintStream out;
	private final PrintStream err;

	Commands(PrintStream out, PrintStream err) {
		this.out = out;
		this.err = err;
	}

	/**
	 * Validates a configuration and prints the findings.
	 *
	 * @param configPath the configuration file
	 * @return the exit code
	 */
	int check(Path configPath) {
		ConfigLoadResult result = ConfigLoader.load(configPath);
		out.println(result.result().formatWithSummary());
		if (!result.result().isValid()) {
			err.println("configuration is not usable until the errors above are fixed");
			return EXIT_CONFIG_ERROR;
		}
		if (result.config().isEmpty()) {
			err.println("configuration could not be parsed");
			return EXIT_CONFIG_ERROR;
		}
		return EXIT_OK;
	}

	/**
	 * Prints the validation report, the routing table and the coverage analysis.
	 *
	 * @param configPath the configuration file
	 * @return the exit code
	 */
	int report(Path configPath) {
		ConfigLoadResult result = ConfigLoader.load(configPath);
		out.println("OpenDonut configuration report");
		out.println("  file: " + configPath.toAbsolutePath());
		out.println();
		out.println(result.result().formatWithSummary());
		out.println();

		Optional<OpenDonutConfig> maybeConfig = result.config();
		if (maybeConfig.isEmpty()) {
			return EXIT_CONFIG_ERROR;
		}
		OpenDonutConfig config = maybeConfig.get();

		out.println("World");
		out.println("  name        : " + config.world().name());
		out.println("  seed        : " + config.world().seed().describe());
		out.println("  border      : " + config.border().describe());
		out.println("  bridge      : " + (config.bridge().enabled()
				? "enabled on channel " + config.bridge().channel()
				: "disabled"));
		out.println();

		out.println("Servers");
		for (int i = 0; i < config.servers().size(); i++) {
			ServerDefinition server = config.servers().get(i);
			out.printf("  [%d] %-16s %-24s %s (%d chunks)%n",
					i, server.id(), server.address().toString(), server.bounds().describe(),
					(server.bounds().width() / 16) * (server.bounds().depth() / 16));
		}
		if (config.servers().isEmpty()) {
			out.println("  (none)");
		}
		out.println();

		out.println("Region index");
		out.println("  " + config.regionIndex().describe());
		out.println();

		out.println("Coverage");
		RegionCoverage coverage = RegionCoverage.analyze(config.servers(), config.border().asBounds());
		out.println("  " + coverage.describe().replace(System.lineSeparator(), System.lineSeparator() + "  "));
		out.println();

		return result.result().isValid() ? EXIT_OK : EXIT_CONFIG_ERROR;
	}

	/**
	 * Answers "which server owns this coordinate?" using the production index, and cross-checks the answer
	 * with the reference implementation.
	 *
	 * <p>Printing both answers is deliberate: if they ever disagree, the reader sees it immediately, and
	 * that is the single most useful piece of information when diagnosing a routing complaint.
	 *
	 * @param configPath the configuration file
	 * @param x global block X
	 * @param z global block Z
	 * @return the exit code
	 */
	int route(Path configPath, int x, int z) {
		ConfigLoadResult result = ConfigLoader.load(configPath);
		if (result.config().isEmpty()) {
			out.println(result.result().formatWithSummary());
			return EXIT_CONFIG_ERROR;
		}
		OpenDonutConfig config = result.config().get();

		RegionIndex index = config.regionIndex();
		List<ServerDefinition> owners = index.findRegions(x, z);
		List<ServerDefinition> reference = config.linearIndex().findRegions(x, z);
		boolean insideBorder = config.border().contains(x, z);

		out.println("coordinate (" + x + ", " + z + ") chunk (" + (x >> 4) + ", " + (z >> 4) + ")");
		out.println("  inside world border : " + insideBorder + "  " + config.border().describe());

		if (owners.isEmpty()) {
			out.println("  owning server       : none (unassigned)");
			if (!result.result().isValid()) {
				out.println("  note                : the configuration has errors; see 'check'");
			}
			return EXIT_UNASSIGNED;
		}

		if (owners.size() > 1) {
			out.println("  owning server       : " + owners.size() + " regions claim this coordinate:");
			for (ServerDefinition owner : owners) {
				out.println("    - " + owner.describe());
			}
			out.println("  action              : fix the overlap, this configuration cannot be used");
			return EXIT_CONFIG_ERROR;
		}

		ServerDefinition owner = owners.get(0);
		out.println("  owning server       : " + owner.describe());
		out.println("  reference check     : " + (reference.size() == 1 && reference.get(0).id().equals(owner.id())
				? "agrees (linear scan)"
				: "DISAGREES: linear scan says " + reference));
		out.println("  offset in region    : "
				+ (x - owner.bounds().minX()) + " blocks from min-x, "
				+ (z - owner.bounds().minZ()) + " blocks from min-z");
		return EXIT_OK;
	}

	/**
	 * Resolves the world seed exactly as the proxy would, persisting a generated seed.
	 *
	 * @param configPath the configuration file
	 * @param stateDirectory the directory holding {@code seed.properties}, or {@code null} to use the
	 *                       configuration's directory
	 * @return the exit code
	 */
	int seed(Path configPath, Path stateDirectory) {
		ConfigLoadResult result = ConfigLoader.load(configPath);
		if (result.config().isEmpty()) {
			out.println(result.result().formatWithSummary());
			return EXIT_CONFIG_ERROR;
		}
		OpenDonutConfig config = result.config().get();
		Path directory = stateDirectory != null
				? stateDirectory
				: (configPath.toAbsolutePath().getParent() == null
						? Path.of(".")
						: configPath.toAbsolutePath().getParent());

		SeedResolution resolution = SeedStore.resolve(directory, config.world().seed());

		out.println("world seed resolution");
		out.println("  configured   : " + config.world().seed().describe());
		out.println("  resolved     : " + resolution.seed());
		out.println("  source       : " + resolution.detail());
		out.println("  state file   : " + directory.resolve(SeedStore.FILE_NAME).toAbsolutePath());
		out.println();
		for (ValidationIssue issue : resolution.issues()) {
			out.println("  " + issue.format());
		}
		out.println();
		out.println("Put this line in every backend's server.properties before its world is created:");
		out.println("  " + SeedStore.levelSeedLine(resolution.seed()));
		out.println();
		out.println("Note: changing the seed does not regenerate chunks that already exist. "
				+ "Existing world folders keep their terrain.");
		return resolution.hasErrors() ? EXIT_CONFIG_ERROR : EXIT_OK;
	}

	/**
	 * Creates or inspects the bridge secret.
	 *
	 * @param secretFile the secret file
	 * @param printSecret whether the Base64 value may be printed (never do this in shared logs)
	 * @return the exit code
	 */
	int secret(Path secretFile, boolean printSecret) {
		try {
			BridgeSecret.LoadResult loaded = BridgeSecret.loadOrCreate(secretFile);
			byte[] secret = loaded.secret();
			out.println("bridge secret");
			out.println("  file        : " + secretFile.toAbsolutePath());
			out.println("  status      : " + (loaded.created() ? "created" : "loaded existing"));
			out.println("  length      : " + secret.length + " bytes");
			out.println("  fingerprint : " + BridgeSecret.fingerprint(secret)
					+ "   (compare this on the proxy and on every backend)");
			if (printSecret) {
				out.println("  value       : " + BridgeSecret.toBase64(secret));
				err.println("warning: the secret value was printed; treat this terminal's scrollback as a "
						+ "credential and clear it when you are done");
			} else {
				out.println("  value       : hidden; re-run with --show to print it");
			}
			for (ValidationIssue issue : loaded.issues()) {
				out.println("  " + issue.format());
			}
			out.println();
			out.println("Bridge protocol version " + BridgeCodec.PROTOCOL_VERSION
					+ ", minimum secret length " + BridgeCodec.MIN_SECRET_LENGTH + " bytes.");
			return EXIT_OK;
		} catch (java.io.IOException e) {
			err.println("could not use the secret file '" + secretFile + "': " + e.getMessage());
			return EXIT_CONFIG_ERROR;
		}
	}

	/**
	 * Writes an example configuration to a file, so a new deployment starts from a known-good baseline.
	 *
	 * @param target where to write the file
	 * @param force whether to overwrite an existing file
	 * @return the exit code
	 */
	int init(Path target, boolean force) {
		if (Files.exists(target) && !force) {
			err.println("'" + target + "' already exists; re-run with --force to overwrite it");
			return EXIT_USAGE;
		}
		try {
			Path parent = target.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(target, EXAMPLE_CONFIG, java.nio.charset.StandardCharsets.UTF_8);
			out.println("wrote " + target.toAbsolutePath());
			out.println("Next: edit the servers list, then run 'check " + target + "'");
			return EXIT_OK;
		} catch (java.io.IOException e) {
			err.println("could not write '" + target + "': " + e.getMessage());
			return EXIT_CONFIG_ERROR;
		}
	}

	/**
	 * Prints a warning when a configuration carries findings that the operator should not miss.
	 *
	 * @param result the load result
	 */
	void printWarnings(ConfigLoadResult result) {
		for (ValidationIssue issue : result.result().issues()) {
			if (issue.severity() == Severity.ERROR || issue.code() == IssueCode.WORLD_UNCOVERED) {
				err.println(issue.format());
			}
		}
	}

	/**
	 * The baseline configuration written by {@code init}.
	 *
	 * <p>Kept as a string so that the CLI can bootstrap a deployment on a machine that has no checkout of
	 * the repository.
	 */
	static final String EXAMPLE_CONFIG = """
			# OpenDonut configuration
			#
			# One file describes the whole network. The proxy and every backend load the same document.
			# Validate it with:  java -jar opendonut-tools.jar check opendonut.yml
			#
			config-version: 1

			world:
			  # The Minecraft level folder name every backend must use.
			  name: "world"
			  # Either a number or 'random'. With 'random' the first run generates a seed and stores it in
			  # seed.properties next to this file; later runs reuse the stored value. Every backend must
			  # use the same seed, so copy seed.properties, or write the number here.
			  seed: random
			  border:
			    center-x: 0
			    center-z: 0
			    # Must be a positive multiple of 16 so that the border edges fall on chunk boundaries.
			    size: 200000

			validation:
			  # false: unassigned areas are allowed and reported as a warning.
			  # true : every block inside the border must be owned by a server.
			  require-full-coverage: false
			  # Regions must be aligned to 16-block chunks. Leave this at true.
			  strict-chunk-alignment: true
			  # ignore | warn | error
			  unknown-fields: warn

			bridge:
			  enabled: true
			  channel: "opendonut:bridge"
			  # Generated on first run. Must be identical on the proxy and on every backend.
			  secret-file: "secrets/bridge-secret.txt"
			  timestamp-tolerance-seconds: 30

			proxy:
			  auto-register-backends: true
			  require-backend-hello: true
			  disconnect-unrouted-players: false

			transfer:
			  boundary-margin-blocks: 2
			  join-grace-ticks: 40
			  cooldown-seconds: 5
			  max-pending-seconds: 15
			  # revert | log-only
			  on-transfer-failure: revert
			  # allow | return
			  unassigned-area-policy: allow

			# Optional: only needed when a backend cannot work out which entry is its own.
			# backend:
			#   server-id: west

			servers:
			  - id: west
			    address: "127.0.0.1:25566"
			    bounds:
			      min-x: -100000
			      max-x: 0
			      min-z: -100000
			      max-z: 100000

			  - id: east
			    address: "127.0.0.1:25567"
			    bounds:
			      min-x: 0
			      max-x: 100000
			      min-z: -100000
			      max-z: 100000
			""";
}

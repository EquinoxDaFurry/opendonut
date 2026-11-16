package net.opendonut.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;

import net.opendonut.common.model.SeedSpec;

/**
 * Resolves {@code world.seed} into a concrete number, and persists a generated one exactly once.
 *
 * <h2>The problem this solves</h2>
 * A random seed must be random exactly once. If every backend (or every restart of one backend)
 * generated its own random seed, the servers would carve up visibly different terrain and the player
 * would walk across a boundary into a different world. So:
 *
 * <ol>
 *   <li>{@code seed: random} on a machine where nothing is stored yet generates a seed and writes it to
 *       {@code seed.properties} next to the configuration;</li>
 *   <li>every later run — including on the other backends, once the file is copied or shared — reads the
 *       stored value and never generates a new one;</li>
 *   <li>an explicit {@code seed: 12345} always wins, and a disagreement with the stored value is reported
 *       so that a half-migrated network is noticed instead of silently split.</li>
 * </ol>
 *
 * <h2>How to change the seed deliberately</h2>
 * Two ways, both documented in {@code docs/troubleshooting.md}:
 * <ul>
 *   <li>set {@code world.seed} to the number you want — this becomes authoritative immediately;</li>
 *   <li>or delete {@code seed.properties} and restart the <em>proxy</em> first, so a new random seed is
 *       generated once, then copy the regenerated file to every backend before starting them.</li>
 * </ul>
 * Changing the seed of a world that already has players does <strong>not</strong> regenerate existing
 * chunks: Minecraft keeps chunks it has already generated. To actually get new terrain the world folders
 * have to be recreated, which OpenDonut never does for you.
 *
 * <h2>File format</h2>
 * A line-oriented properties file, chosen over JSON so that an administrator can read and edit it with
 * any text editor and see the explanation in the comments:
 *
 * <pre>
 * # OpenDonut generated seed. Delete this file to have 'seed: random' generate a new one.
 * seed=1234567890
 * generated-at=2026-11-17T09:15:00Z
 * generated-by=OpenDonut 0.1.0-dev
 * </pre>
 */
public final class SeedStore {

	/** The file the generated seed is persisted to. */
	public static final String FILE_NAME = "seed.properties";

	private static final String KEY_SEED = "seed";
	private static final String KEY_GENERATED_AT = "generated-at";
	private static final String KEY_GENERATED_BY = "generated-by";

	private SeedStore() {
	}

	/**
	 * Resolves the configured seed against the persisted state.
	 *
	 * @param stateDirectory the directory that holds {@code seed.properties}; created when missing
	 * @param spec the configured seed specification
	 * @return the resolution, including any findings
	 */
	public static SeedResolution resolve(Path stateDirectory, SeedSpec spec) {
		List<ValidationIssue> issues = new ArrayList<>();
		Path stateFile = stateDirectory.resolve(FILE_NAME);

		Optional<Long> stored = read(stateFile, issues);

		if (spec.explicit()) {
			// The configuration wins, always. A stored value that differs is reported because it usually
			// means one backend was started before the administrator changed the seed, and that backend is
			// now generating different terrain.
			if (stored.isPresent() && stored.get() != spec.value()) {
				issues.add(ValidationIssue.of(IssueCode.SEED_STATE_MISMATCH, "world.seed",
						"world.seed is " + spec.value() + " but " + stateFile.getFileName()
								+ " records " + stored.get()
								+ "; the configured seed is used. Any backend that was started with the "
								+ "stored seed has generated different terrain — see docs/troubleshooting.md."));
			}
			if (stored.isEmpty()) {
				// Record the configured value so that later runs and other machines can detect a change.
				write(stateFile, spec.value(), "configured", issues);
			}
			return new SeedResolution(spec.value(), SeedResolution.Origin.CONFIGURED,
					"configured in world.seed", issues);
		}

		if (stored.isPresent()) {
			return new SeedResolution(stored.get(), SeedResolution.Origin.STATE_FILE,
					"loaded from " + FILE_NAME, issues);
		}

		long generated = generate();
		write(stateFile, generated, "random", issues);
		issues.add(ValidationIssue.of(IssueCode.SEED_GENERATED, "world.seed",
				"world.seed is random and no seed was stored yet: generated " + generated + " and wrote it to "
						+ stateFile + ". Copy this file to every backend, or set world.seed explicitly so the "
						+ "value is part of the configuration."));
		return new SeedResolution(generated, SeedResolution.Origin.GENERATED,
				"generated and persisted to " + FILE_NAME, issues);
	}

	/**
	 * Generates a fresh seed.
	 *
	 * <p>Uses {@link Random} rather than {@link java.security.SecureRandom} deliberately: this value is
	 * not a secret, it only needs to be different from other worlds. Using the same generator Minecraft
	 * itself uses keeps the range of values familiar.
	 *
	 * @return a pseudo-random seed
	 */
	public static long generate() {
		return new Random().nextLong();
	}

	/**
	 * Reads the stored seed, if any.
	 *
	 * @param stateFile the state file
	 * @param issues the collector for problems
	 * @return the stored seed, or empty when there is none
	 */
	public static Optional<Long> read(Path stateFile, List<ValidationIssue> issues) {
		if (!Files.exists(stateFile)) {
			return Optional.empty();
		}
		try {
			Properties properties = new Properties();
			try (java.io.Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
				properties.load(reader);
			}
			String raw = properties.getProperty(KEY_SEED);
			if (raw == null || raw.isBlank()) {
				issues.add(ValidationIssue.of(IssueCode.SEED_INVALID, stateFile.getFileName().toString(),
						stateFile + " does not contain a 'seed' entry; delete the file to have OpenDonut "
								+ "regenerate it"));
				return Optional.empty();
			}
			return Optional.of(Long.parseLong(raw.trim()));
		} catch (IOException e) {
			issues.add(ValidationIssue.of(IssueCode.FILE_UNREADABLE, stateFile.getFileName().toString(),
					"could not read " + stateFile + ": " + e.getMessage()));
			return Optional.empty();
		} catch (NumberFormatException e) {
			issues.add(ValidationIssue.of(IssueCode.SEED_INVALID, stateFile.getFileName().toString(),
					"the 'seed' entry in " + stateFile + " is not a number; delete the file to regenerate it"));
			return Optional.empty();
		}
	}

	/**
	 * Writes the seed, replacing any previous state atomically.
	 *
	 * <p>The write goes to a temporary file in the same directory and is then moved into place, so a crash
	 * mid-write cannot leave a half-written seed that parses as a different number.
	 *
	 * @param stateFile the state file
	 * @param seed the seed to persist
	 * @param origin a short description of why the value was written
	 * @param issues the collector for problems
	 */
	public static void write(Path stateFile, long seed, String origin, List<ValidationIssue> issues) {
		String content = "# OpenDonut generated seed." + System.lineSeparator()
				+ "# Delete this file to have 'seed: random' generate a new one." + System.lineSeparator()
				+ "# Every backend of the network must use the same value." + System.lineSeparator()
				+ KEY_SEED + "=" + seed + System.lineSeparator()
				+ KEY_GENERATED_AT + "=" + java.time.Instant.now() + System.lineSeparator()
				+ KEY_GENERATED_BY + "=OpenDonut (" + origin + ")" + System.lineSeparator();

		try {
			Path parent = stateFile.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path temporary = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
			Files.writeString(temporary, content, StandardCharsets.UTF_8);
			try {
				Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				// Some filesystems (notably network shares) cannot move atomically. Falling back to a plain
				// replace is still better than writing in place, because the temporary file already holds
				// the complete content.
				Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			issues.add(ValidationIssue.of(IssueCode.FILE_UNREADABLE, stateFile.getFileName().toString(),
					"could not persist the seed to " + stateFile + ": " + e.getMessage()
							+ ". Set world.seed explicitly in the configuration instead."));
		}
	}

	/**
	 * Formats a seed the way it should be written into {@code server.properties}.
	 *
	 * <p>Provided so that administrative scripts and the CLI do not each invent their own advice.
	 * The value is a <em>suggestion</em>: OpenDonut never edits {@code server.properties} itself, because
	 * a backend that is already running must not have its world settings changed underneath it.
	 *
	 * @param seed the resolved seed
	 * @return the {@code level-seed} line to place in every backend's {@code server.properties}
	 */
	public static String levelSeedLine(long seed) {
		return "level-seed=" + seed;
	}
}

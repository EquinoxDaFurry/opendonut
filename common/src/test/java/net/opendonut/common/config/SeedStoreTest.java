package net.opendonut.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.opendonut.common.model.SeedSpec;

/**
 * Seed resolution and persistence.
 *
 * <p>The property that matters is "a random seed is random exactly once". Every test here is a way of
 * checking that a second, a tenth or a thousandth run never produces a different number, because a
 * backend that regenerates its seed silently generates a different world.
 */
class SeedStoreTest {

	@TempDir
	Path stateDirectory;

	@Test
	@DisplayName("a random seed is generated once and then reused forever")
	void randomSeedIsPersistedOnce() {
		SeedResolution first = SeedStore.resolve(stateDirectory, SeedSpec.random());
		assertTrue(first.wasGenerated(), "the first resolution must generate");
		assertEquals(SeedResolution.Origin.GENERATED, first.origin());
		assertTrue(first.issues().stream().anyMatch(issue -> issue.code() == IssueCode.SEED_GENERATED),
				"the administrator must be told a seed was created");
		assertTrue(Files.exists(stateDirectory.resolve(SeedStore.FILE_NAME)));

		SeedResolution second = SeedStore.resolve(stateDirectory, SeedSpec.random());
		assertEquals(first.seed(), second.seed(), "the stored seed must be reused");
		assertEquals(SeedResolution.Origin.STATE_FILE, second.origin());
		assertFalse(second.wasGenerated());

		// And on every later run too.
		for (int run = 0; run < 20; run++) {
			assertEquals(first.seed(), SeedStore.resolve(stateDirectory, SeedSpec.random()).seed());
		}
	}

	@Test
	@DisplayName("two fresh directories generate different seeds")
	void generatedSeedsDiffer() throws IOException {
		Path other = Files.createTempDirectory(stateDirectory, "other");
		long a = SeedStore.resolve(stateDirectory, SeedSpec.random()).seed();
		long b = SeedStore.resolve(other, SeedSpec.random()).seed();
		assertNotEquals(a, b, "two independent networks must not accidentally share a seed");
	}

	@Test
	@DisplayName("an explicit seed wins and is recorded")
	void explicitSeedWins() {
		SeedResolution resolution = SeedStore.resolve(stateDirectory, SeedSpec.of(424242L));
		assertEquals(424242L, resolution.seed());
		assertEquals(SeedResolution.Origin.CONFIGURED, resolution.origin());
		assertTrue(resolution.issues().isEmpty(), "the first run has nothing to warn about");

		// The configured value is now also stored, so a later change is detectable.
		assertEquals(424242L, SeedStore.read(stateDirectory.resolve(SeedStore.FILE_NAME),
				new java.util.ArrayList<>()).orElseThrow());
	}

	@Test
	@DisplayName("changing an explicit seed is allowed and reported")
	void explicitSeedChangeIsReported() throws IOException {
		SeedStore.resolve(stateDirectory, SeedSpec.of(1L));
		SeedResolution changed = SeedStore.resolve(stateDirectory, SeedSpec.of(2L));
		assertEquals(2L, changed.seed(), "the configuration is authoritative");
		assertTrue(changed.issues().stream().anyMatch(issue -> issue.code() == IssueCode.SEED_STATE_MISMATCH),
				"a half-migrated network must be noticed");
		assertFalse(changed.hasErrors(), "changing a seed is a deliberate act, not an error");
	}

	@Test
	@DisplayName("switching from random to an explicit seed adopts the configured value")
	void randomToExplicit() {
		long generated = SeedStore.resolve(stateDirectory, SeedSpec.random()).seed();
		SeedResolution explicit = SeedStore.resolve(stateDirectory, SeedSpec.of(777L));
		assertEquals(777L, explicit.seed());
		assertNotEquals(generated, explicit.seed());
		assertTrue(explicit.issues().stream().anyMatch(issue -> issue.code() == IssueCode.SEED_STATE_MISMATCH));
	}

	@Test
	@DisplayName("deleting the state file regenerates, which is the documented way to change the seed")
	void deletingStateRegenerates() throws IOException {
		long first = SeedStore.resolve(stateDirectory, SeedSpec.random()).seed();
		Files.delete(stateDirectory.resolve(SeedStore.FILE_NAME));
		SeedResolution regenerated = SeedStore.resolve(stateDirectory, SeedSpec.random());
		assertTrue(regenerated.wasGenerated());
		// The probability of a collision is 2^-64; treating it as impossible is reasonable here.
		assertNotEquals(first, regenerated.seed());
	}

	@Test
	@DisplayName("a corrupt state file is reported and replaced rather than crashing startup")
	void corruptStateFile() throws IOException {
		Files.writeString(stateDirectory.resolve(SeedStore.FILE_NAME), "seed=not-a-number\n",
				StandardCharsets.UTF_8);
		java.util.List<ValidationIssue> issues = new java.util.ArrayList<>();
		assertTrue(SeedStore.read(stateDirectory.resolve(SeedStore.FILE_NAME), issues).isEmpty());
		assertTrue(issues.stream().anyMatch(issue -> issue.code() == IssueCode.SEED_INVALID));

		// Resolving with an explicit seed recovers: the configuration is authoritative.
		SeedResolution resolution = SeedStore.resolve(stateDirectory, SeedSpec.of(5L));
		assertEquals(5L, resolution.seed());
	}

	@Test
	@DisplayName("a state file without a seed entry is reported")
	void stateFileWithoutSeed() throws IOException {
		Files.writeString(stateDirectory.resolve(SeedStore.FILE_NAME), "# nothing here\n",
				StandardCharsets.UTF_8);
		java.util.List<ValidationIssue> issues = new java.util.ArrayList<>();
		assertTrue(SeedStore.read(stateDirectory.resolve(SeedStore.FILE_NAME), issues).isEmpty());
		assertTrue(issues.stream().anyMatch(issue -> issue.code() == IssueCode.SEED_INVALID));
	}

	@Test
	@DisplayName("the state file is self describing")
	void stateFileIsReadable() throws IOException {
		long seed = SeedStore.resolve(stateDirectory, SeedSpec.random()).seed();
		String content = Files.readString(stateDirectory.resolve(SeedStore.FILE_NAME));
		assertTrue(content.contains("seed=" + seed));
		assertTrue(content.contains("Delete this file"), "the file must explain how to regenerate");
		assertTrue(content.contains("Every backend"), "the file must explain why it is shared");
	}

	@Test
	@DisplayName("the state directory is created when missing")
	void createsStateDirectory() {
		Path nested = stateDirectory.resolve("deeper/nested");
		SeedResolution resolution = SeedStore.resolve(nested, SeedSpec.random());
		assertTrue(resolution.wasGenerated());
		assertTrue(Files.exists(nested.resolve(SeedStore.FILE_NAME)));
	}

	@Test
	@DisplayName("the level-seed line matches server.properties syntax")
	void levelSeedLine() {
		assertEquals("level-seed=-1234567", SeedStore.levelSeedLine(-1234567L));
	}

	@Test
	@DisplayName("seed specifications parse the documented forms")
	void seedSpecParsing() {
		assertFalse(SeedSpec.parse("random").explicit());
		assertFalse(SeedSpec.parse("RANDOM").explicit());
		assertEquals(42L, SeedSpec.parse("42").value());
		assertEquals(-42L, SeedSpec.parse("-42").value());
		assertEquals(255L, SeedSpec.parse("0xFF").value());
		assertEquals(42L, SeedSpec.parse("  42  ").value(), "surrounding whitespace is tolerated");
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> SeedSpec.parse("hello"));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
				() -> SeedSpec.parse(""));
	}
}

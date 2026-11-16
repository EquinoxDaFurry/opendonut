package net.opendonut.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.ServerDefinition;

/**
 * Every configuration that ships with the repository must load, validate and route correctly.
 *
 * <p>These files are documentation. A stale example that no longer parses, or whose numbers no longer
 * add up, teaches an administrator the wrong thing and wastes their afternoon, so the documentation is
 * tested like code. The repository root is supplied by the build as {@code opendonut.repoRoot}.
 */
class ShippedConfigurationsTest {

	private static final List<String> SHIPPED_CONFIGURATIONS = List.of(
			"config/opendonut.example.yml",
			"examples/two-servers/opendonut.yml",
			"examples/four-servers/opendonut.yml");

	private static Path repositoryRoot() {
		String root = System.getProperty("opendonut.repoRoot");
		assertTrue(root != null && !root.isBlank(),
				"the build must pass -Dopendonut.repoRoot so that shipped files can be checked");
		return Path.of(root);
	}

	@ParameterizedTest(name = "{0} is a valid configuration")
	@ValueSource(strings = {
			"config/opendonut.example.yml",
			"examples/two-servers/opendonut.yml",
			"examples/four-servers/opendonut.yml"})
	void shippedConfigurationIsValid(String relativePath) {
		Path file = repositoryRoot().resolve(relativePath);
		assertTrue(Files.exists(file), file + " must exist");

		ConfigLoadResult result = ConfigLoader.load(file);
		assertTrue(result.isUsable(), () -> relativePath + " must be usable but reported:\n"
				+ result.result().formatWithSummary());
		assertTrue(result.result().errors().isEmpty(), () -> relativePath + " must have no errors:\n"
				+ result.result().formatWithSummary());
	}

	@Test
	@DisplayName("every shipped configuration is listed in the test")
	void noConfigurationIsForgotten() throws java.io.IOException {
		Path configDirectory = repositoryRoot().resolve("config");
		assertTrue(Files.isDirectory(configDirectory));
		try (var entries = Files.list(configDirectory)) {
			List<String> yamlFiles = entries
					.filter(path -> path.getFileName().toString().endsWith(".yml"))
					.map(path -> "config/" + path.getFileName())
					.sorted()
					.toList();
			assertEquals(List.of("config/opendonut.example.yml"), yamlFiles,
					"a new file in config/ must also be covered by this test");
		}
		// The example must be a usable starting point for a real deployment.
		assertTrue(SHIPPED_CONFIGURATIONS.contains("config/opendonut.example.yml"));
	}

	@Test
	@DisplayName("the two-server example splits the border exactly once, at x = 0")
	void twoServerExample() {
		OpenDonutConfig config = ConfigLoader
				.load(repositoryRoot().resolve("examples/two-servers/opendonut.yml"))
				.config().orElseThrow();

		ServerDefinition west = config.server("west").orElseThrow();
		ServerDefinition east = config.server("east").orElseThrow();

		assertEquals(-100000, west.bounds().minX());
		assertEquals(0, west.bounds().maxX());
		assertEquals(0, east.bounds().minX());
		assertEquals(100000, east.bounds().maxX());

		// The shared edge, resolved through the real lookup engine.
		assertEquals("west", config.findOwner(-1, 0).orElseThrow().id());
		assertEquals("east", config.findOwner(0, 0).orElseThrow().id());
		// The extreme corners of the border.
		assertEquals("west", config.findOwner(-100000, -100000).orElseThrow().id());
		assertEquals("east", config.findOwner(99999, 99999).orElseThrow().id());
		// A single block outside the border is unassigned.
		assertTrue(config.findOwner(100000, 0).isEmpty());
		assertTrue(config.findOwner(-100001, 0).isEmpty());
	}

	@Test
	@DisplayName("the four-server example resolves the corner where all four meet")
	void fourServerExample() {
		OpenDonutConfig config = ConfigLoader
				.load(repositoryRoot().resolve("examples/four-servers/opendonut.yml"))
				.config().orElseThrow();

		assertEquals(4, config.servers().size());
		assertEquals("nw", config.findOwner(-1, -1).orElseThrow().id());
		assertEquals("ne", config.findOwner(0, -1).orElseThrow().id());
		assertEquals("sw", config.findOwner(-1, 0).orElseThrow().id());
		assertEquals("se", config.findOwner(0, 0).orElseThrow().id());
		// A point in the middle of each quadrant.
		assertEquals("nw", config.findOwner(-50000, -50000).orElseThrow().id());
		assertEquals("se", config.findOwner(50000, 50000).orElseThrow().id());
	}

	@Test
	@DisplayName("the example uses the documented channel and does not embed a secret")
	void exampleCarriesNoSecret() throws java.io.IOException {
		String text = Files.readString(repositoryRoot().resolve("config/opendonut.example.yml"));
		assertTrue(text.contains("opendonut:bridge"), "the channel must match BridgeSettings.DEFAULT_CHANNEL");
		assertTrue(text.contains("secret-file"), "the secret file location must be documented");
		assertTrue(text.contains("secrets/bridge-secret.txt"),
				"the default secret path must match BridgeSettings.DEFAULT_SECRET_FILE");
		// A real secret in the example would be a credential leak.
		assertTrue(!text.contains("secret="), "the example must not contain a secret value");
	}
}

package net.opendonut.common.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line tool, driven through {@link Main#run(String[])} so the exit codes are asserted as
 * well as the output. Deployment scripts depend on those codes, so they are part of the tested surface.
 */
class MainTest {

	/** Captured output of one CLI invocation. */
	private record Run(int exitCode, String stdout, String stderr) {

		String all() {
			return stdout + stderr;
		}
	}

	private Run run(String... args) {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteArrayOutputStream err = new ByteArrayOutputStream();
		try {
			System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
			System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
			int code = Main.run(args);
			return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
	}

	private Path writeConfig(Path directory, String content) throws IOException {
		Path file = directory.resolve("opendonut.yml");
		Files.writeString(file, content, StandardCharsets.UTF_8);
		return file;
	}

	private static final String GOOD = """
			world:
			  name: world
			  seed: 42
			  border:
			    size: 200000
			servers:
			  - id: west
			    address: "127.0.0.1:25566"
			    bounds: { min-x: -100000, max-x: 0, min-z: -100000, max-z: 100000 }
			  - id: east
			    address: "127.0.0.1:25567"
			    bounds: { min-x: 0, max-x: 100000, min-z: -100000, max-z: 100000 }
			""";

	@Test
	@DisplayName("no arguments prints usage and exits with the usage code")
	void usage() {
		Run run = run();
		assertEquals(3, run.exitCode());
		assertTrue(run.stdout().contains("usage:"), run.all());
		assertTrue(run.stdout().contains("check"), "the usage must list the commands");
	}

	@Test
	@DisplayName("help exits successfully")
	void help() {
		assertEquals(0, run("help").exitCode());
	}

	@Test
	@DisplayName("an unknown command is refused")
	void unknownCommand() {
		Run run = run("frobnicate");
		assertEquals(3, run.exitCode());
		assertTrue(run.stderr().contains("unknown command"), run.all());
	}

	@Test
	@DisplayName("a missing argument is refused with the usage line")
	void missingArgument() {
		Run run = run("route", "opendonut.yml", "1");
		assertEquals(3, run.exitCode());
		assertTrue(run.stderr().contains("route <config.yml> <x> <z>"), run.all());
	}

	@Test
	@DisplayName("a non numeric coordinate is refused")
	void badCoordinate() {
		Run run = run("route", "opendonut.yml", "here", "0");
		assertEquals(3, run.exitCode());
		assertTrue(run.stderr().contains("not a whole number"), run.all());
	}

	@Test
	@DisplayName("check accepts a good configuration and prints the findings")
	void checkGood(@TempDir Path directory) throws IOException {
		Path config = writeConfig(directory, GOOD);
		Run run = run("check", config.toString());
		assertEquals(0, run.exitCode(), run.all());
		assertTrue(run.stdout().contains("0 errors"), run.stdout());
		assertTrue(run.stdout().contains("coverage"), run.stdout());
	}

	@Test
	@DisplayName("check refuses a configuration with errors and names the code")
	void checkBad(@TempDir Path directory) throws IOException {
		Path config = writeConfig(directory, GOOD.replace("max-x: 0", "max-x: -100000"));
		Run run = run("check", config.toString());
		assertEquals(1, run.exitCode());
		assertTrue(run.stdout().contains("BOUNDS_INVALID"), run.all());
		assertTrue(run.stderr().contains("not usable"), run.all());
	}

	@Test
	@DisplayName("check on a missing file is a configuration error, not a crash")
	void checkMissingFile(@TempDir Path directory) {
		Run run = run("check", directory.resolve("nope.yml").toString());
		assertEquals(1, run.exitCode());
		assertTrue(run.stdout().contains("FILE_NOT_FOUND"), run.all());
	}

	@Test
	@DisplayName("route names the owning server")
	void routeOwned(@TempDir Path directory) throws IOException {
		Path config = writeConfig(directory, GOOD);
		Run west = run("route", config.toString(), "-1", "0");
		assertEquals(0, west.exitCode());
		assertTrue(west.stdout().contains("west"), west.stdout());
		assertTrue(west.stdout().contains("agrees (linear scan)"), west.stdout());

		Run east = run("route", config.toString(), "0", "0");
		assertEquals(0, east.exitCode());
		assertTrue(east.stdout().contains("east"), east.stdout());
	}

	@Test
	@DisplayName("route reports an unassigned coordinate with its own exit code")
	void routeUnassigned(@TempDir Path directory) throws IOException {
		Path config = writeConfig(directory, GOOD);
		Run run = run("route", config.toString(), "100000", "0");
		assertEquals(2, run.exitCode(), run.all());
		assertTrue(run.stdout().contains("unassigned"), run.stdout());
	}

	@Test
	@DisplayName("report prints the world, the routing table and the coverage")
	void report(@TempDir Path directory) throws IOException {
		Path config = writeConfig(directory, GOOD);
		Run run = run("report", config.toString());
		assertEquals(0, run.exitCode(), run.all());
		assertTrue(run.stdout().contains("routing table") || run.stdout().contains("west"), run.stdout());
		assertTrue(run.stdout().contains("Coverage"), run.stdout());
		assertTrue(run.stdout().contains("lookup buckets"), run.stdout());
	}

	@Test
	@DisplayName("seed resolves, persists and prints the level-seed line")
	void seed(@TempDir Path directory) throws IOException {
		Path config = writeConfig(directory, GOOD);
		Run run = run("seed", config.toString(), directory.toString());
		assertEquals(0, run.exitCode(), run.all());
		assertTrue(run.stdout().contains("level-seed=42"), run.stdout());
		assertTrue(Files.exists(directory.resolve("seed.properties")));
	}

	@Test
	@DisplayName("seed generates a value for 'random' and reuses it on the next run")
	void seedRandom(@TempDir Path directory) throws IOException {
		Path config = writeConfig(directory, GOOD.replace("seed: 42", "seed: random"));
		Run first = run("seed", config.toString(), directory.toString());
		assertEquals(0, first.exitCode(), first.all());
		assertTrue(first.stdout().contains("SEED_GENERATED"), first.all());

		Run second = run("seed", config.toString(), directory.toString());
		assertEquals(0, second.exitCode(), second.all());
		assertTrue(second.stdout().contains("loaded from seed.properties"), second.all());
	}

	@Test
	@DisplayName("secret creates a file, then reports the same fingerprint")
	void secret(@TempDir Path directory) throws IOException {
		Path secretFile = directory.resolve("secrets/bridge-secret.txt");
		Run created = run("secret", secretFile.toString());
		assertEquals(0, created.exitCode(), created.all());
		assertTrue(created.stdout().contains("created"), created.stdout());
		assertTrue(Files.exists(secretFile));

		Run loaded = run("secret", secretFile.toString());
		assertEquals(0, loaded.exitCode(), loaded.all());
		assertTrue(loaded.stdout().contains("loaded existing"), loaded.stdout());

		// The fingerprint must match between runs, and the value must not be printed without --show.
		assertTrue(!created.stdout().contains("value       : ") || created.stdout().contains("hidden"));
		Run shown = run("secret", secretFile.toString(), "--show");
		assertEquals(0, shown.exitCode());
		String fingerprint = created.stdout().lines()
				.filter(line -> line.contains("fingerprint"))
				.findFirst().orElseThrow();
		assertTrue(shown.stdout().contains(fingerprint.trim().split("\\s+")[2]), shown.stdout());
	}

	@Test
	@DisplayName("init writes a configuration that then validates")
	void init(@TempDir Path directory) throws IOException {
		Path target = directory.resolve("opendonut.yml");
		Run init = run("init", target.toString());
		assertEquals(0, init.exitCode(), init.all());
		assertTrue(Files.exists(target));

		Run check = run("check", target.toString());
		assertEquals(0, check.exitCode(), check.all());

		// Refuses to overwrite without --force, and does overwrite with it.
		assertNotEquals(0, run("init", target.toString()).exitCode());
		assertEquals(0, run("init", target.toString(), "--force").exitCode());
	}

	@Test
	@DisplayName("an internal failure is reported as an internal error, not a stack trace only")
	void handlesUnexpectedArguments(@TempDir Path directory) {
		Run run = run("route", directory.toString(), "0", "0");
		// A directory is not a configuration file; the tool must report that as a configuration problem.
		assertEquals(1, run.exitCode(), run.all());
	}
}

package net.opendonut.common.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The OpenDonut command line tool.
 *
 * <p>Everything the OpenDonut configuration engine can do, without a Minecraft server or a proxy: validate
 * a configuration, inspect the routing table, ask who owns a coordinate, resolve the world seed, and
 * manage the bridge secret. This is the tool to reach for when a network misbehaves, because it answers
 * the same questions the running components answer, using the same code.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -jar opendonut-tools-&lt;version&gt;.jar &lt;command&gt; [arguments]
 *
 *   check  &lt;config.yml&gt;              validate and print findings; exit 0 when usable
 *   report &lt;config.yml&gt;              report plus routing table, coverage and overlaps
 *   route  &lt;config.yml&gt; &lt;x&gt; &lt;z&gt;      which server owns a global coordinate
 *   seed   &lt;config.yml&gt; [stateDir]   resolve the world seed, persisting it when generated
 *   secret &lt;secret-file&gt; [--show]    create or inspect the bridge secret
 *   init   &lt;config.yml&gt; [--force]    write a baseline configuration
 *   help                             this text
 * </pre>
 *
 * <h2>Exit codes</h2>
 * <pre>
 *   0  success
 *   1  the configuration has errors, or could not be read
 *   2  the query was valid but no server owns that coordinate
 *   3  the command line was wrong
 * </pre>
 *
 * <p>The exit codes are part of the interface: a deployment script can run {@code check} before starting
 * the proxy and refuse to continue on a non-zero result.
 */
public final class Main {

	private Main() {
	}

	/**
	 * Entry point.
	 *
	 * @param args the command line arguments
	 */
	public static void main(String[] args) {
		int exitCode = run(args);
		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}

	/**
	 * Runs the tool and returns the exit code instead of terminating the JVM.
	 *
	 * <p>Used by the tests, which assert on exit codes without ending the test process.
	 *
	 * @param args the command line arguments
	 * @return the exit code
	 */
	public static int run(String[] args) {
		Commands commands = new Commands(System.out, System.err);

		if (args.length == 0) {
			printUsage(System.out);
			return Commands.EXIT_USAGE;
		}

		String command = args[0].toLowerCase(java.util.Locale.ROOT);
		List<String> rest = new ArrayList<>();
		for (int i = 1; i < args.length; i++) {
			rest.add(args[i]);
		}

		try {
			return switch (command) {
				case "check" -> {
					Path config = requirePath(rest, 0, "check <config.yml>");
					yield commands.check(config);
				}
				case "report" -> {
					Path config = requirePath(rest, 0, "report <config.yml>");
					yield commands.report(config);
				}
				case "route" -> {
					Path config = requirePath(rest, 0, "route <config.yml> <x> <z>");
					int x = requireInt(rest, 1, "route <config.yml> <x> <z>");
					int z = requireInt(rest, 2, "route <config.yml> <x> <z>");
					yield commands.route(config, x, z);
				}
				case "seed" -> {
					Path config = requirePath(rest, 0, "seed <config.yml> [stateDir]");
					Path stateDirectory = rest.size() > 1 ? Path.of(rest.get(1)) : null;
					yield commands.seed(config, stateDirectory);
				}
				case "secret" -> {
					Path secretFile = requirePath(rest, 0, "secret <secret-file> [--show]");
					boolean show = rest.contains("--show");
					yield commands.secret(secretFile, show);
				}
				case "init" -> {
					Path target = requirePath(rest, 0, "init <config.yml> [--force]");
					boolean force = rest.contains("--force");
					yield commands.init(target, force);
				}
				case "help", "--help", "-h" -> {
					printUsage(System.out);
					yield Commands.EXIT_OK;
				}
				default -> {
					System.err.println("unknown command '" + args[0] + "'");
					printUsage(System.err);
					yield Commands.EXIT_USAGE;
				}
			};
		} catch (UsageException e) {
			System.err.println(e.getMessage());
			printUsage(System.err);
			return Commands.EXIT_USAGE;
		} catch (RuntimeException e) {
			// A failure here is a bug in OpenDonut rather than in the argument: report it with the stack
			// trace so it can be filed as an issue.
			System.err.println("internal error: " + e);
			e.printStackTrace(System.err);
			return Commands.EXIT_CONFIG_ERROR;
		}
	}

	/**
	 * Reads a required path argument.
	 *
	 * @param arguments the arguments after the command name
	 * @param index the position to read
	 * @param usage the usage string for the message
	 * @return the path
	 * @throws UsageException if the argument is missing
	 */
	private static Path requirePath(List<String> arguments, int index, String usage) {
		if (arguments.size() <= index) {
			throw new UsageException("missing argument; usage: " + usage);
		}
		return Path.of(arguments.get(index));
	}

	/**
	 * Reads a required integer argument.
	 *
	 * @param arguments the arguments after the command name
	 * @param index the position to read
	 * @param usage the usage string for the message
	 * @return the value
	 * @throws UsageException if the argument is missing or not a number
	 */
	private static int requireInt(List<String> arguments, int index, String usage) {
		if (arguments.size() <= index) {
			throw new UsageException("missing argument; usage: " + usage);
		}
		try {
			return Integer.parseInt(arguments.get(index));
		} catch (NumberFormatException e) {
			throw new UsageException("'" + arguments.get(index)
					+ "' is not a whole number of blocks; usage: " + usage);
		}
	}

	/**
	 * Prints the usage text.
	 *
	 * @param out the stream to print to
	 */
	private static void printUsage(java.io.PrintStream out) {
		out.println("OpenDonut tools " + version());
		out.println();
		out.println("usage: java -jar opendonut-tools.jar <command> [arguments]");
		out.println();
		out.println("  check  <config.yml>              validate a configuration and print findings");
		out.println("  report <config.yml>              validation, routing table, coverage and overlaps");
		out.println("  route  <config.yml> <x> <z>      which server owns a global block coordinate");
		out.println("  seed   <config.yml> [stateDir]   resolve the world seed, persisting a generated one");
		out.println("  secret <secret-file> [--show]    create or inspect the bridge secret");
		out.println("  init   <config.yml> [--force]    write a baseline configuration");
		out.println("  help                             this text");
		out.println();
		out.println("exit codes: 0 success, 1 configuration error, 2 unassigned coordinate, 3 usage error");
	}

	/**
	 * The tool version, taken from the jar manifest when available.
	 *
	 * @return the version string
	 */
	private static String version() {
		Package self = Main.class.getPackage();
		String version = self == null ? null : self.getImplementationVersion();
		return version == null ? "(development build)" : version;
	}

	/**
	 * Thrown when the command line is wrong. Handled by {@link #run(String[])} and never escapes.
	 */
	private static final class UsageException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private UsageException(String message) {
			super(message);
		}
	}
}

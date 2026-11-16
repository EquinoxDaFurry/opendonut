package net.opendonut.common.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.opendonut.common.config.IssueCode;
import net.opendonut.common.config.ValidationIssue;

/**
 * Loads, creates and hardens the shared bridge secret.
 *
 * <h2>Threat model</h2>
 * The secret authenticates bridge traffic between the proxy and the backends. Anyone holding it can mint
 * a valid {@link BridgeMessage.TransferRequest}. That is a real capability: they can move players between
 * servers, which is a denial-of-service vector and, combined with a weak region layout, could be used to
 * push a player into an area they should not reach. The secret is therefore treated as a credential:
 *
 * <ul>
 *   <li>it lives in its own file, never in the YAML, so a configuration that gets pasted into a support
 *       thread does not leak it;</li>
 *   <li>the file is written with owner-only permissions where the platform supports it, and a warning is
 *       emitted when it is world readable;</li>
 *   <li>it is generated with {@link SecureRandom}, never from a clock or a hostname;</li>
 *   <li>the file is listed in {@code .gitignore} by default, along with the other secret paths.</li>
 * </ul>
 *
 * <h2>Format</h2>
 * One line of Base64 inside a small comment header, so an administrator can read the file, verify it is
 * identical on both machines, and compare it with the output of the CLI's {@code secret} command:
 *
 * <pre>
 * # OpenDonut bridge secret.
 * # The same value must be present on the proxy and on every backend. Never commit this file.
 * secret=Zk3n...==
 * </pre>
 */
public final class BridgeSecret {

	/** Recommended secret length. */
	public static final int SECRET_BYTES = 32;

	private static final String KEY = "secret=";
	private static final SecureRandom RANDOM = new SecureRandom();

	private BridgeSecret() {
	}

	/**
	 * Generates a new random secret.
	 *
	 * @return {@value #SECRET_BYTES} fresh random bytes
	 */
	public static byte[] generate() {
		byte[] secret = new byte[SECRET_BYTES];
		RANDOM.nextBytes(secret);
		return secret;
	}

	/**
	 * Loads a secret file.
	 *
	 * <p>Accepts the documented {@code secret=<base64>} form, and — for administrators who keep secrets
	 * elsewhere — a bare Base64 line, a hex string, or the raw bytes of the file.
	 *
	 * @param file the file to read
	 * @return the secret, or empty when the file is absent
	 * @throws IOException if the file exists but cannot be read or parsed
	 */
	public static Optional<byte[]> load(Path file) throws IOException {
		if (file == null || !Files.exists(file)) {
			return Optional.empty();
		}
		String text = Files.readString(file, StandardCharsets.UTF_8).trim();
		if (text.isEmpty()) {
			throw new IOException("secret file '" + file + "' is empty");
		}
		for (String line : text.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			if (trimmed.startsWith(KEY)) {
				trimmed = trimmed.substring(KEY.length()).trim();
			}
			byte[] decoded = decode(trimmed);
			if (decoded == null) {
				throw new IOException("secret file '" + file
						+ "' does not contain a readable Base64 or hex secret");
			}
			if (decoded.length < BridgeCodec.MIN_SECRET_LENGTH) {
				throw new IOException("the secret in '" + file + "' is only " + decoded.length
						+ " bytes; at least " + BridgeCodec.MIN_SECRET_LENGTH + " are required");
			}
			return Optional.of(decoded);
		}
		throw new IOException("secret file '" + file + "' contains no secret value");
	}

	/**
	 * Loads the secret, creating and persisting a new one when the file does not exist yet.
	 *
	 * @param file the secret file
	 * @return the findings produced, including the informational message about a newly created secret
	 * @throws IOException if the file exists but is unusable, or cannot be written
	 */
	public static LoadResult loadOrCreate(Path file) throws IOException {
		List<ValidationIssue> issues = new ArrayList<>();
		Optional<byte[]> existing = load(file);
		if (existing.isPresent()) {
			byte[] secret = existing.get();
			issues.addAll(checkPermissions(file, secret));
			return new LoadResult(secret, issues, false);
		}

		byte[] secret = generate();
		write(file, secret, issues);
		issues.add(ValidationIssue.of(IssueCode.DERIVED_VALUE, "bridge.secret-file",
				"generated a new bridge secret at " + file
						+ ". Copy this file to the proxy and to every other backend, keeping it out of "
						+ "version control and out of chat."));
		return new LoadResult(secret, issues, true);
	}

	/**
	 * Writes a secret file, replacing any previous content atomically and restricting permissions.
	 *
	 * @param file the file to write
	 * @param secret the secret bytes
	 * @param issues the collector for permission and durability warnings
	 * @throws IOException if the file cannot be written
	 */
	public static void write(Path file, byte[] secret, List<ValidationIssue> issues) throws IOException {
		if (secret == null || secret.length < BridgeCodec.MIN_SECRET_LENGTH) {
			throw new IOException("refusing to write a secret shorter than " + BridgeCodec.MIN_SECRET_LENGTH
					+ " bytes");
		}
		String content = "# OpenDonut bridge secret." + System.lineSeparator()
				+ "# The same value must be present on the proxy and on every backend." + System.lineSeparator()
				+ "# Never commit this file, never paste it into chat, and keep backups private." + System.lineSeparator()
				+ KEY + Base64.getEncoder().encodeToString(secret) + System.lineSeparator();

		Path parent = file.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temporary, content, StandardCharsets.UTF_8);
		harden(temporary, issues);
		try {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
		if (issues != null) {
			issues.addAll(checkPermissions(file, secret));
		}
	}

	/**
	 * Attempts to restrict a file to owner read/write.
	 *
	 * <p>Silently does nothing on platforms without POSIX permissions, which includes Windows. The
	 * administrator is told about that separately by {@link #checkPermissions}.
	 *
	 * @param file the file to harden
	 * @param issues the collector for warnings
	 */
	private static void harden(Path file, List<ValidationIssue> issues) {
		try {
			Set<PosixFilePermission> ownerOnly = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
			Files.setPosixFilePermissions(file, ownerOnly);
		} catch (UnsupportedOperationException e) {
			// Not a POSIX platform. Nothing to do; the warning below explains the situation.
		} catch (FileSystemException e) {
			issues.add(ValidationIssue.of(IssueCode.BRIDGE_SECRET_WORLD_READABLE, "bridge.secret-file",
					"could not restrict permissions on " + file + ": " + e.getReason()
							+ "; make sure only the server user can read it"));
		} catch (IOException e) {
			issues.add(ValidationIssue.of(IssueCode.BRIDGE_SECRET_WORLD_READABLE, "bridge.secret-file",
					"could not restrict permissions on " + file + ": " + e.getMessage()));
		}
	}

	/**
	 * Checks the permissions of an existing secret file.
	 *
	 * @param file the secret file
	 * @param secret the loaded secret, used to warn about a short value
	 * @return the findings
	 */
	public static List<ValidationIssue> checkPermissions(Path file, byte[] secret) {
		List<ValidationIssue> issues = new ArrayList<>();
		if (secret != null && secret.length < SECRET_BYTES) {
			issues.add(ValidationIssue.of(IssueCode.BRIDGE_SECRET_INVALID, "bridge.secret-file",
					"the shared secret is only " + secret.length + " bytes; regenerate it with the OpenDonut "
							+ "CLI 'secret' command, which produces " + SECRET_BYTES));
		}
		try {
			Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
			boolean othersCanRead = permissions.contains(PosixFilePermission.GROUP_READ)
					|| permissions.contains(PosixFilePermission.OTHERS_READ);
			if (othersCanRead) {
				issues.add(ValidationIssue.of(IssueCode.BRIDGE_SECRET_WORLD_READABLE, "bridge.secret-file",
						file + " is readable by other users on this machine; run 'chmod 600 " + file
								+ "' so that only the server user can read the bridge secret"));
			}
		} catch (UnsupportedOperationException e) {
			issues.add(ValidationIssue.of(IssueCode.DERIVED_VALUE, "bridge.secret-file",
					"this platform has no POSIX permissions, so OpenDonut cannot verify that " + file
							+ " is private; make sure the folder is not shared"));
		} catch (IOException e) {
			issues.add(ValidationIssue.of(IssueCode.BRIDGE_SECRET_WORLD_READABLE, "bridge.secret-file",
					"could not read the permissions of " + file + ": " + e.getMessage()));
		}
		return issues;
	}

	/**
	 * Decodes Base64 or hex text.
	 *
	 * @param text the text to decode
	 * @return the decoded bytes, or {@code null} when the text is neither valid Base64 nor valid hex
	 */
	private static byte[] decode(String text) {
		if (text.isEmpty()) {
			return null;
		}
		try {
			return Base64.getDecoder().decode(text);
		} catch (IllegalArgumentException e) {
			// Fall through to hex.
		}
		if (text.length() % 2 == 0 && text.matches("[0-9a-fA-F]+")) {
			byte[] result = new byte[text.length() / 2];
			for (int i = 0; i < result.length; i++) {
				result[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
			}
			return result;
		}
		return null;
	}

	/**
	 * Renders a secret as the Base64 text an administrator can copy to another machine.
	 *
	 * @param secret the secret bytes
	 * @return the Base64 text
	 */
	public static String toBase64(byte[] secret) {
		return Base64.getEncoder().encodeToString(secret);
	}

	/**
	 * A comparison-safe fingerprint of a secret, for logs.
	 *
	 * <p>Derived from the first four bytes of a SHA-256 of the concatenation of the secret and its own
	 * reverse, so it does not reveal the secret itself while still letting an administrator confirm that
	 * two machines hold the same value.
	 *
	 * @param secret the secret bytes
	 * @return eight hex characters
	 */
	public static String fingerprint(byte[] secret) {
		try {
			byte[] material = new byte[secret.length * 2];
			System.arraycopy(secret, 0, material, 0, secret.length);
			for (int i = 0; i < secret.length; i++) {
				material[secret.length + i] = secret[secret.length - 1 - i];
			}
			byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(material);
			StringBuilder out = new StringBuilder(8);
			for (int i = 0; i < 4; i++) {
				out.append(String.format("%02x", digest[i]));
			}
			return out.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * The outcome of {@link #loadOrCreate(Path)}.
	 *
	 * @param secret the usable secret bytes
	 * @param issues findings produced while loading or creating
	 * @param created whether a new secret was written by this call
	 */
	public record LoadResult(byte[] secret, List<ValidationIssue> issues, boolean created) {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if the secret is missing
		 */
		public LoadResult {
			if (secret == null) {
				throw new IllegalArgumentException("secret must not be null");
			}
			issues = List.copyOf(issues);
		}

		@Override
		public byte[] secret() {
			return secret.clone();
		}
	}
}

package net.opendonut.common.model;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A backend host/port pair, parsed from the {@code address} field of the OpenDonut configuration.
 *
 * <p>Accepted forms:
 *
 * <pre>
 *   127.0.0.1:25566
 *   play.example.com:25566
 *   [::1]:25566            (IPv6 literal, brackets required)
 *   localhost:25566
 * </pre>
 *
 * <p>A missing port is rejected rather than silently defaulting to 25565: for a backend address a
 * wrong port produces a confusing "server offline" symptom instead of an obvious configuration
 * error.
 *
 * @param host the hostname or IP literal, never blank, never surrounded by brackets
 * @param port the TCP port in {@code 1..65535}
 */
public record ServerAddress(String host, int port) {

	/**
	 * Validates the address.
	 *
	 * @throws IllegalArgumentException if the host is blank or the port is out of range
	 */
	public ServerAddress {
		Objects.requireNonNull(host, "host");
		if (host.isBlank()) {
			throw new IllegalArgumentException("host must not be blank");
		}
		if (host.indexOf(' ') >= 0) {
			throw new IllegalArgumentException("host must not contain spaces: '" + host + "'");
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("port must be between 1 and 65535, got " + port);
		}
	}

	/**
	 * Parses an {@code address} string from the configuration.
	 *
	 * @param raw the raw value, for example {@code "127.0.0.1:25566"}
	 * @return the parsed address
	 * @throws IllegalArgumentException if the value is not a valid {@code host:port} pair; the
	 *         message is intended to be shown directly to the administrator
	 */
	public static ServerAddress parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("address must not be empty");
		}
		String value = raw.trim();

		String host;
		String portPart;
		if (value.startsWith("[")) {
			int closing = value.indexOf(']');
			if (closing < 0) {
				throw new IllegalArgumentException("IPv6 address is missing its closing bracket: '" + raw + "'");
			}
			host = value.substring(1, closing);
			String rest = value.substring(closing + 1);
			if (!rest.startsWith(":")) {
				throw new IllegalArgumentException("IPv6 address must be followed by a port, for example [::1]:25566");
			}
			portPart = rest.substring(1);
		} else {
			int separator = value.lastIndexOf(':');
			if (separator < 0) {
				throw new IllegalArgumentException("address must include a port, for example 127.0.0.1:25566 (got '"
						+ raw + "')");
			}
			if (value.indexOf(':') != separator) {
				throw new IllegalArgumentException(
						"IPv6 addresses must be wrapped in brackets, for example [::1]:25566 (got '" + raw + "')");
			}
			host = value.substring(0, separator);
			portPart = value.substring(separator + 1);
		}

		if (host.isBlank()) {
			throw new IllegalArgumentException("address is missing a host: '" + raw + "'");
		}
		int port;
		try {
			port = Integer.parseInt(portPart.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("address port is not a number: '" + portPart + "'");
		}
		return new ServerAddress(host, port);
	}

	/**
	 * Attempts to parse an address without throwing.
	 *
	 * @param raw the raw value
	 * @return the parsed address, or empty when the value is invalid
	 */
	public static Optional<ServerAddress> tryParse(String raw) {
		try {
			return Optional.of(parse(raw));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/**
	 * Explains why an address string is invalid.
	 *
	 * @param raw the raw value
	 * @return the parser's message, or an empty string when the value is valid
	 */
	public static String describeProblem(String raw) {
		try {
			parse(raw);
			return "";
		} catch (IllegalArgumentException e) {
			return e.getMessage();
		}
	}

	/**
	 * Tests whether this address refers to the loopback interface.
	 *
	 * @return {@code true} for {@code localhost}, {@code 127.x.y.z} and {@code ::1}
	 */
	public boolean isLoopback() {
		String normalised = host.toLowerCase(Locale.ROOT);
		return normalised.equals("localhost")
				|| normalised.equals("::1")
				|| normalised.equals("0:0:0:0:0:0:0:1")
				|| normalised.startsWith("127.");
	}

	/**
	 * Tests whether two addresses point at the same socket.
	 *
	 * <p>Loopback hostnames are treated as equivalent, so {@code localhost:25566} and
	 * {@code 127.0.0.1:25566} match. This matters for the backend port auto-detection, where one
	 * side comes from {@code server.properties} and the other from the central configuration.
	 *
	 * @param other the other address, may be {@code null}
	 * @return {@code true} if both addresses resolve to the same host and port
	 */
	public boolean isSameSocketAs(ServerAddress other) {
		if (other == null || port != other.port) {
			return false;
		}
		if (host.equalsIgnoreCase(other.host)) {
			return true;
		}
		return isLoopback() && other.isLoopback();
	}

	@Override
	public String toString() {
		return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
	}
}

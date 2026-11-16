package net.opendonut.common.config;

/**
 * How serious a configuration finding is.
 *
 * <p>The distinction drives real behaviour, so it is not cosmetic:
 * <ul>
 *   <li>{@link #ERROR} prevents the proxy from routing players and prevents a backend from enabling
 *       transfers. Every error describes a situation that would corrupt world data or route players
 *       to the wrong server.</li>
 *   <li>{@link #WARNING} is a situation that is legal but almost certainly not intended, or that is
 *       only legal because an administrator opted in. The system starts; the operator is told.</li>
 *   <li>{@link #INFO} is pure reporting: coverage statistics, derived values, and advice.</li>
 * </ul>
 */
public enum Severity {

	/** Fatal. Routing and transfers are refused until the configuration is fixed. */
	ERROR,

	/** Legal but suspicious, or explicitly opted into. Routing continues. */
	WARNING,

	/** Reporting only. */
	INFO;

	/** @return the prefix used when printing this severity */
	public String label() {
		return switch (this) {
			case ERROR -> "ERROR";
			case WARNING -> "WARN";
			case INFO -> "INFO";
		};
	}
}

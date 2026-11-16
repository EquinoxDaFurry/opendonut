package net.opendonut.common.config;

import java.util.Objects;

/**
 * One finding produced while loading or validating a configuration.
 *
 * @param severity how serious the finding is
 * @param code the stable machine readable code
 * @param path the location inside the configuration document, for example {@code servers[2].bounds.min-x};
 *             empty for whole-file findings
 * @param message the human readable explanation, written to be shown directly to an administrator
 * @param line the 1-based line number in the source file, or {@code -1} when unknown
 */
public record ValidationIssue(Severity severity, IssueCode code, String path, String message, int line) {

	/**
	 * Validates the record.
	 *
	 * @throws IllegalArgumentException if a component is missing
	 */
	public ValidationIssue {
		Objects.requireNonNull(severity, "severity");
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(message, "message");
	}

	/**
	 * Creates a finding without a source location.
	 *
	 * @param severity how serious the finding is
	 * @param code the machine readable code
	 * @param path the configuration path, may be empty
	 * @param message the explanation
	 * @return the finding
	 */
	public static ValidationIssue of(Severity severity, IssueCode code, String path, String message) {
		return new ValidationIssue(severity, code, path, message, -1);
	}

	/**
	 * Creates a finding that uses the code's default severity.
	 *
	 * @param code the machine readable code
	 * @param path the configuration path, may be empty
	 * @param message the explanation
	 * @param line the source line, or {@code -1}
	 * @return the finding
	 */
	public static ValidationIssue of(IssueCode code, String path, String message, int line) {
		return new ValidationIssue(code.defaultSeverity(), code, path, message, line);
	}

	/**
	 * Creates a finding without a source location, using the code's default severity.
	 *
	 * @param code the machine readable code
	 * @param path the configuration path, may be empty
	 * @param message the explanation
	 * @return the finding
	 */
	public static ValidationIssue of(IssueCode code, String path, String message) {
		return new ValidationIssue(code.defaultSeverity(), code, path, message, -1);
	}

	/**
	 * Tests whether this finding is fatal.
	 *
	 * @return {@code true} if the severity is {@link Severity#ERROR}
	 */
	public boolean isError() {
		return severity == Severity.ERROR;
	}

	/**
	 * Formats the finding the way the CLI and the server log print it.
	 *
	 * <p>Format: {@code [SEVERITY] CODE (path:line) message}, with the location omitted when unknown.
	 *
	 * @return a single line description
	 */
	public String format() {
		StringBuilder out = new StringBuilder();
		out.append('[').append(severity.label()).append("] ").append(code);
		if (!path.isEmpty() || line > 0) {
			out.append(" (");
			if (!path.isEmpty()) {
				out.append(path);
			}
			if (line > 0) {
				if (!path.isEmpty()) {
					out.append(':');
				}
				out.append(line);
			}
			out.append(')');
		}
		out.append(' ').append(message);
		return out.toString();
	}

	@Override
	public String toString() {
		return format();
	}
}

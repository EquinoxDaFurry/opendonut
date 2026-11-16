package net.opendonut.common.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The complete set of findings for one configuration, in a deterministic order.
 *
 * <p>Determinism matters more than it looks: the same file must always produce the same report so that
 * a CI check can diff it, and so that "the first error" is a stable thing for scripts to key on.
 * Findings are ordered by severity (errors first) and then by source line, because that is the order an
 * administrator wants to fix them in.
 */
public final class ValidationResult {

	/**
	 * Mutable collector used while parsing and validating. Not part of the public API surface of the
	 * loader, but exposed so the parser and the validator can share one instance.
	 */
	public static final class Collector {

		private final List<ValidationIssue> issues = new ArrayList<>();

		/**
		 * Records a finding.
		 *
		 * @param issue the finding
		 * @return the collector, for chaining
		 */
		public Collector add(ValidationIssue issue) {
			issues.add(Objects.requireNonNull(issue, "issue"));
			return this;
		}

		/**
		 * Records a finding using a code's default severity.
		 *
		 * @param code the machine readable code
		 * @param path the configuration path
		 * @param line the source line, or {@code -1}
		 * @param message the explanation
		 * @return the collector, for chaining
		 */
		public Collector add(IssueCode code, String path, int line, String message) {
			return add(ValidationIssue.of(code, path, message, line));
		}

		/**
		 * Records a finding with an explicit severity.
		 *
		 * @param severity how serious the finding is
		 * @param code the machine readable code
		 * @param path the configuration path
		 * @param line the source line, or {@code -1}
		 * @param message the explanation
		 * @return the collector, for chaining
		 */
		public Collector add(Severity severity, IssueCode code, String path, int line, String message) {
			return add(new ValidationIssue(severity, code, path, message, line));
		}

		/**
		 * Records a finding without a source location.
		 *
		 * @param code the machine readable code
		 * @param message the explanation
		 * @return the collector, for chaining
		 */
		public Collector add(IssueCode code, String message) {
			return add(ValidationIssue.of(code, "", message));
		}

		/**
		 * Merges another collector's findings into this one.
		 *
		 * @param other the collector to absorb
		 * @return the collector, for chaining
		 */
		public Collector addAll(ValidationResult.Collector other) {
			issues.addAll(other.issues);
			return this;
		}

		/**
		 * Tests whether any recorded finding is fatal.
		 *
		 * @return {@code true} if at least one error was recorded
		 */
		public boolean hasErrors() {
			for (ValidationIssue issue : issues) {
				if (issue.isError()) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Freezes the collected findings into a sorted result.
		 *
		 * @return the immutable result
		 */
		public ValidationResult toResult() {
			return new ValidationResult(issues);
		}
	}

	/** The empty result, used when a caller wants a no-op validator. */
	public static final ValidationResult EMPTY = new ValidationResult(List.of());

	private final List<ValidationIssue> issues;
	private final List<ValidationIssue> errors;
	private final List<ValidationIssue> warnings;
	private final List<ValidationIssue> infos;

	private ValidationResult(List<ValidationIssue> issues) {
		List<ValidationIssue> sorted = new ArrayList<>(issues);
		sorted.sort((a, b) -> {
			int bySeverity = a.severity().compareTo(b.severity());
			if (bySeverity != 0) {
				return bySeverity;
			}
			if (a.line() > 0 && b.line() > 0 && a.line() != b.line()) {
				return Integer.compare(a.line(), b.line());
			}
			int byPath = a.path().compareTo(b.path());
			if (byPath != 0) {
				return byPath;
			}
			return a.code().compareTo(b.code());
		});
		this.issues = List.copyOf(sorted);
		this.errors = filter(Severity.ERROR);
		this.warnings = filter(Severity.WARNING);
		this.infos = filter(Severity.INFO);
	}

	private List<ValidationIssue> filter(Severity severity) {
		return issues.stream().filter(issue -> issue.severity() == severity).collect(Collectors.toUnmodifiableList());
	}

	/** @return every finding, errors first, then warnings, then informational messages */
	public List<ValidationIssue> issues() {
		return issues;
	}

	/** @return only the fatal findings */
	public List<ValidationIssue> errors() {
		return errors;
	}

	/** @return only the warnings */
	public List<ValidationIssue> warnings() {
		return warnings;
	}

	/** @return only the informational findings */
	public List<ValidationIssue> infos() {
		return infos;
	}

	/**
	 * Tests whether the configuration may be used to route players.
	 *
	 * @return {@code true} if no fatal finding was recorded
	 */
	public boolean isValid() {
		return errors.isEmpty();
	}

	/** @return {@code true} if at least one fatal finding was recorded */
	public boolean hasErrors() {
		return !errors.isEmpty();
	}

	/**
	 * Tests whether a specific code was reported at any severity.
	 *
	 * @param code the code to look for
	 * @return {@code true} if the code appears in the report
	 */
	public boolean contains(IssueCode code) {
		for (ValidationIssue issue : issues) {
			if (issue.code() == code) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tests whether a specific code was reported at a specific severity.
	 *
	 * @param code the code to look for
	 * @param severity the severity to match
	 * @return {@code true} if a matching finding exists
	 */
	public boolean contains(IssueCode code, Severity severity) {
		for (ValidationIssue issue : issues) {
			if (issue.code() == code && issue.severity() == severity) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Counts the findings of one severity.
	 *
	 * @param severity the severity to count
	 * @return the number of matching findings
	 */
	public int count(Severity severity) {
		return switch (severity) {
			case ERROR -> errors.size();
			case WARNING -> warnings.size();
			case INFO -> infos.size();
		};
	}

	/**
	 * Renders the report as plain text, one finding per line.
	 *
	 * @return the report
	 */
	public String format() {
		if (issues.isEmpty()) {
			return "no findings";
		}
		return issues.stream().map(ValidationIssue::format).collect(Collectors.joining(System.lineSeparator()));
	}

	/**
	 * Renders a summary line plus the findings.
	 *
	 * @return the report
	 */
	public String formatWithSummary() {
		return summary() + System.lineSeparator() + format();
	}

	/**
	 * A one-line count of findings.
	 *
	 * @return for example {@code 2 errors, 1 warning, 4 informational messages}
	 */
	public String summary() {
		return errors.size() + " error" + (errors.size() == 1 ? "" : "s")
				+ ", " + warnings.size() + " warning" + (warnings.size() == 1 ? "" : "s")
				+ ", " + infos.size() + " informational message" + (infos.size() == 1 ? "" : "s");
	}

	@Override
	public String toString() {
		return "ValidationResult[" + summary() + "]";
	}

	/**
	 * An unmodifiable view of the findings, for callers that want to build their own report.
	 *
	 * @return the immutable list
	 */
	public List<ValidationIssue> unmodifiableIssues() {
		return Collections.unmodifiableList(issues);
	}
}

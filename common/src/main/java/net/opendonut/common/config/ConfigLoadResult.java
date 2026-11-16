package net.opendonut.common.config;

import java.util.Objects;
import java.util.Optional;

import net.opendonut.common.model.OpenDonutConfig;

/**
 * The outcome of loading a configuration: the parsed model when it is usable, plus every finding.
 *
 * <p>Two very different failure modes are both represented here on purpose:
 * <ul>
 *   <li>{@link #config()} is empty — the document could not be turned into a model at all (missing
 *       world, missing servers, unreadable file). There is nothing to route with.</li>
 *   <li>{@link #config()} is present but {@link #result()}{@code .hasErrors()} — the model exists and
 *       can be inspected and printed (which is what the CLI does), but it must not be used to route
 *       players.</li>
 * </ul>
 * Callers that must not run on a broken configuration should use {@link #requireUsable()}.
 *
 * @param config the parsed configuration, empty when the document could not be parsed into a model
 * @param result every finding produced while loading and validating
 * @param source the origin of the document, for messages
 */
public record ConfigLoadResult(Optional<OpenDonutConfig> config, ValidationResult result, String source) {

	/**
	 * Validates the record.
	 *
	 * @throws IllegalArgumentException if a component is missing
	 */
	public ConfigLoadResult {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(result, "result");
		Objects.requireNonNull(source, "source");
	}

	/**
	 * Creates a result with no configuration, used when the file could not be read at all.
	 *
	 * @param source the origin of the document
	 * @param result the findings
	 * @return the result
	 */
	public static ConfigLoadResult unusable(String source, ValidationResult result) {
		return new ConfigLoadResult(Optional.empty(), result, source);
	}

	/**
	 * Tests whether the configuration exists and has no fatal findings.
	 *
	 * @return {@code true} when the configuration may be used to route players
	 */
	public boolean isUsable() {
		return config.isPresent() && result.isValid();
	}

	/**
	 * Returns the configuration, or throws with the full report.
	 *
	 * <p>Used by the mod and the plugin at startup: a configuration that cannot route must stop the
	 * component rather than let it half-work.
	 *
	 * @return the usable configuration
	 * @throws IllegalStateException if the configuration is missing or has errors, with a message that
	 *                               lists every finding
	 */
	public OpenDonutConfig requireUsable() {
		OpenDonutConfig value = config.orElseThrow(() -> new IllegalStateException(
				"OpenDonut configuration '" + source + "' could not be parsed:" + System.lineSeparator()
						+ result.formatWithSummary()));
		if (result.hasErrors()) {
			throw new IllegalStateException(
					"OpenDonut configuration '" + source + "' has errors:" + System.lineSeparator()
							+ result.formatWithSummary());
		}
		return value;
	}

	/**
	 * Renders the findings and the routing table as a report suitable for a log or a CLI.
	 *
	 * @return a multi-line report
	 */
	public String describe() {
		StringBuilder out = new StringBuilder();
		out.append("OpenDonut configuration '").append(source).append("'").append(System.lineSeparator());
		out.append(result.formatWithSummary());
		config.ifPresent(value -> out.append(System.lineSeparator())
				.append(System.lineSeparator())
				.append(value.describeRoutingTable()));
		return out.toString();
	}
}

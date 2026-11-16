package net.opendonut.common.config;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;

import net.opendonut.common.model.OpenDonutConfig;

/**
 * Loads, parses and validates an OpenDonut YAML configuration file in one step.
 *
 * <pre>{@code
 * ConfigLoadResult result = ConfigLoader.load(Path.of("config/opendonut.yml"));
 * if (!result.isUsable()) {
 *     log.error("{}", result.result().formatWithSummary());
 *     return;
 * }
 * OpenDonutConfig config = result.config().orElseThrow();
 * }</pre>
 *
 * <h2>Security posture</h2>
 * The document is composed with SnakeYAML's {@link SafeConstructor}: OpenDonut only ever reads plain
 * scalars, lists and mappings, never constructs arbitrary Java objects from YAML tags, so a
 * configuration file cannot be turned into a gadget. Alias expansion and document size are bounded to
 * keep a hostile or accidental file from exhausting memory.
 *
 * <h2>Determinism</h2>
 * The same file always produces the same findings in the same order, so the report can be diffed in CI.
 * See {@link ValidationResult}.
 */
public final class ConfigLoader {

	/**
	 * Maximum size of a configuration document, in code points.
	 *
	 * <p>Four megabytes is roughly a hundred thousand server entries — far beyond any real network — and
	 * still small enough that a runaway generated file fails fast instead of being parsed.
	 */
	private static final int MAX_DOCUMENT_CODE_POINTS = 4 * 1024 * 1024;

	/**
	 * Maximum number of aliases that may be expanded, protecting against "billion laughs" style
	 * documents.
	 */
	private static final int MAX_ALIASES = 50;

	private ConfigLoader() {
	}

	/**
	 * Loads a configuration file.
	 *
	 * <p>Never throws for a bad file: every problem, including "the file does not exist", is reported as
	 * a finding. Callers that must not continue on a broken configuration use
	 * {@link ConfigLoadResult#requireUsable()}.
	 *
	 * @param file the configuration file
	 * @return the load result
	 */
	public static ConfigLoadResult load(Path file) {
		ValidationResult.Collector issues = new ValidationResult.Collector();
		SourceMap.Builder sourceMap = SourceMap.builder();
		String source = file == null ? "(no file)" : file.toString();

		if (file == null) {
			issues.add(IssueCode.FILE_NOT_FOUND, "", -1, "no configuration file was given");
			return ConfigLoadResult.unusable(source, issues.toResult());
		}
		if (!Files.exists(file)) {
			issues.add(IssueCode.FILE_NOT_FOUND, "", -1,
					"configuration file '" + file + "' does not exist; copy config/opendonut.example.yml to "
							+ "this path and edit it");
			return ConfigLoadResult.unusable(source, issues.toResult());
		}
		if (Files.isDirectory(file)) {
			issues.add(IssueCode.FILE_UNREADABLE, "", -1,
					"'" + file + "' is a directory, not a configuration file");
			return ConfigLoadResult.unusable(source, issues.toResult());
		}

		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (IOException e) {
			issues.add(IssueCode.FILE_UNREADABLE, "", -1,
					"could not read '" + file + "': " + e.getMessage());
			return ConfigLoadResult.unusable(source, issues.toResult());
		}
		return loadFromString(text, source, issues, sourceMap);
	}

	/**
	 * Loads a configuration from a string, used by tests and by administrative tooling that generates a
	 * configuration in memory.
	 *
	 * @param yamlText the YAML document
	 * @param sourceName the name to report in messages
	 * @return the load result
	 */
	public static ConfigLoadResult loadFromString(String yamlText, String sourceName) {
		return loadFromString(yamlText, sourceName, new ValidationResult.Collector(), SourceMap.builder());
	}

	private static ConfigLoadResult loadFromString(
			String yamlText,
			String sourceName,
			ValidationResult.Collector issues,
			SourceMap.Builder sourceMap) {

		if (yamlText == null || yamlText.isBlank()) {
			issues.add(IssueCode.FILE_EMPTY, "", -1,
					"configuration '" + sourceName + "' is empty; copy config/opendonut.example.yml and edit it");
			return ConfigLoadResult.unusable(sourceName, issues.toResult());
		}

		Node root;
		try {
			root = compose(yamlText);
		} catch (YAMLException e) {
			IssueCode code = e.getClass().getSimpleName().contains("DuplicateKey")
					? IssueCode.YAML_DUPLICATE_KEY
					: IssueCode.YAML_INVALID;
			// MarkedYAMLException carries the problem, the context and the source mark; a plain
			// YAMLException carries only a message. Both are handled so that neither is swallowed.
			Mark mark = e instanceof MarkedYAMLException marked ? marked.getProblemMark() : null;
			int line = mark == null ? -1 : mark.getLine() + 1;
			String detail = buildYamlMessage(e);
			issues.add(code, "", line, detail);
			return ConfigLoadResult.unusable(sourceName, issues.toResult());
		}

		if (root == null) {
			issues.add(IssueCode.FILE_EMPTY, "", -1,
					"configuration '" + sourceName + "' contains no document (only comments or blank lines)");
			return ConfigLoadResult.unusable(sourceName, issues.toResult());
		}

		ConfigParser parser = new ConfigParser(issues, sourceMap);
		Optional<OpenDonutConfig> parsed = parser.parse(root, sourceName);
		if (parsed.isEmpty()) {
			return new ConfigLoadResult(Optional.empty(), issues.toResult(), sourceName);
		}

		OpenDonutConfig config = parsed.get();
		new ConfigValidator(issues, sourceMap.build()).validate(config);
		return new ConfigLoadResult(Optional.of(config), issues.toResult(), sourceName);
	}

	/**
	 * Composes a YAML document into a node tree with SafeConstructor and bounded resource use.
	 *
	 * @param yamlText the document
	 * @return the root node, or {@code null} for an empty document
	 * @throws YAMLException if the document is not valid YAML
	 */
	private static Node compose(String yamlText) {
		LoaderOptions loaderOptions = new LoaderOptions();
		// Duplicate keys are reported by YamlAccess with a configuration path and a line number, which is
		// far more useful than the parser's own message, so composition is allowed to accept them.
		loaderOptions.setAllowDuplicateKeys(true);
		loaderOptions.setMaxAliasesForCollections(MAX_ALIASES);
		loaderOptions.setCodePointLimit(MAX_DOCUMENT_CODE_POINTS);
		loaderOptions.setNestingDepthLimit(64);

		Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
		return yaml.compose(new StringReader(yamlText));
	}

	/**
	 * Builds a readable message from a SnakeYAML exception, including the marked source snippet.
	 *
	 * @param e the exception
	 * @return a multi-line explanation
	 */
	private static String buildYamlMessage(YAMLException e) {
		StringBuilder out = new StringBuilder();
		out.append("invalid YAML");
		Mark mark = null;
		if (e instanceof MarkedYAMLException marked) {
			if (marked.getContext() != null && !marked.getContext().isBlank()) {
				out.append(": ").append(marked.getContext().trim());
			}
			if (marked.getProblem() != null && !marked.getProblem().isBlank()) {
				out.append(": ").append(marked.getProblem().trim());
			}
			mark = marked.getProblemMark();
		} else if (e.getMessage() != null) {
			out.append(": ").append(e.getMessage().trim());
		}
		if (mark != null && mark.get_snippet() != null) {
			out.append(System.lineSeparator()).append("    ").append(mark.get_snippet().trim());
		}
		return out.toString();
	}
}

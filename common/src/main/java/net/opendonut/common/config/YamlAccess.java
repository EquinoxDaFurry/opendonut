package net.opendonut.common.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import net.opendonut.common.model.UnknownFieldPolicy;

/**
 * Typed, location-aware access to a composed SnakeYAML document.
 *
 * <h2>Why the low level node API instead of bean binding</h2>
 * Loading YAML directly into Java objects is convenient and useless for a validator: the first mistake
 * aborts the load, and the resulting message names a Java field, not the line the administrator typed.
 * Working on {@link Node}s keeps the source line and column of every value, so every finding can point
 * at the exact place to fix, and it lets the parser report <em>several</em> problems in one pass instead
 * of one per build.
 *
 * <h2>Contract</h2>
 * Every getter is total: it never throws for bad input. It reports the problem through the shared
 * {@link ValidationResult.Collector} and returns a fallback, so parsing continues and the administrator
 * sees the whole list of mistakes at once.
 */
final class YamlAccess {

	private final ValidationResult.Collector issues;
	private final SourceMap.Builder sourceMap;

	YamlAccess(ValidationResult.Collector issues, SourceMap.Builder sourceMap) {
		this.issues = issues;
		this.sourceMap = sourceMap;
	}

	/**
	 * Finds the best line to report for a value that is missing.
	 *
	 * <p>A missing key has no node, so there is no line of its own; the enclosing section is what the
	 * administrator has to edit, so its line is reported instead.
	 *
	 * @param childPath the path of the missing value
	 * @return the line of the nearest recorded ancestor, or {@code -1}
	 */
	private int missingLine(String childPath) {
		return sourceMap == null ? -1 : sourceMap.lineOfNearestAncestor(childPath);
	}

	/**
	 * Builds a child path for messages.
	 *
	 * @param parent the parent path, may be empty
	 * @param segment the child segment
	 * @return the joined path
	 */
	static String child(String parent, String segment) {
		return parent.isEmpty() ? segment : parent + "." + segment;
	}

	/**
	 * Builds an indexed child path for messages.
	 *
	 * @param parent the parent path, may be empty
	 * @param index the sequence index
	 * @return the joined path, for example {@code servers[2]}
	 */
	static String index(String parent, int index) {
		return parent + "[" + index + "]";
	}

	/**
	 * Reads the 1-based source line of a node.
	 *
	 * @param node the node, may be {@code null}
	 * @return the line number, or {@code -1} when unknown
	 */
	static int lineOf(Node node) {
		if (node == null || node.getStartMark() == null) {
			return -1;
		}
		return node.getStartMark().getLine() + 1;
	}

	/**
	 * Tests whether a node represents YAML {@code null} or an empty value.
	 *
	 * @param node the node, may be {@code null}
	 * @return {@code true} if the node carries no value
	 */
	static boolean isNull(Node node) {
		if (node == null) {
			return true;
		}
		if (node.getTag() == Tag.NULL) {
			return true;
		}
		return node instanceof ScalarNode scalar && scalar.getValue().isBlank();
	}

	/**
	 * Coerces a node to a mapping.
	 *
	 * @param node the node, may be {@code null}
	 * @param path the configuration path used in messages
	 * @param required whether a missing node is a fatal finding
	 * @return the mapping, or {@code null} when the node is missing or of the wrong kind
	 */
	MappingNode asMapping(Node node, String path, boolean required) {
		if (isNull(node)) {
			if (required) {
				issues.add(IssueCode.MISSING_FIELD, path, missingLine(path), "required section '" + path + "' is missing");
			}
			return null;
		}
		if (node instanceof MappingNode mapping) {
			return mapping;
		}
		issues.add(IssueCode.INVALID_TYPE, path, lineOf(node),
				"expected a mapping (key: value pairs) but found a " + describeType(node));
		return null;
	}

	/**
	 * Coerces a node to a sequence.
	 *
	 * @param node the node, may be {@code null}
	 * @param path the configuration path used in messages
	 * @param required whether a missing node is a fatal finding
	 * @return the sequence, or {@code null} when the node is missing or of the wrong kind
	 */
	SequenceNode asSequence(Node node, String path, boolean required) {
		if (isNull(node)) {
			if (required) {
				issues.add(IssueCode.MISSING_FIELD, path, missingLine(path), "required list '" + path + "' is missing");
			}
			return null;
		}
		if (node instanceof SequenceNode sequence) {
			return sequence;
		}
		issues.add(IssueCode.INVALID_TYPE, path, lineOf(node),
				"expected a list but found a " + describeType(node));
		return null;
	}

	/**
	 * Describes a node's YAML kind, for error messages.
	 *
	 * @param node the node
	 * @return a human readable kind
	 */
	private static String describeType(Node node) {
		if (node instanceof MappingNode) {
			return "mapping";
		}
		if (node instanceof SequenceNode) {
			return "list";
		}
		if (node instanceof ScalarNode scalar) {
			String rendered = scalar.getValue();
			if (rendered.length() > 40) {
				rendered = rendered.substring(0, 37) + "...";
			}
			return "scalar value '" + rendered + "'";
		}
		return "value";
	}

	/**
	 * Enumerates the entries of a mapping, preserving document order and recording duplicate keys.
	 *
	 * <p>Duplicate keys are reported here rather than left to the YAML parser so the message can name
	 * the configuration path and mention that the last value wins. Keeping the last occurrence matches
	 * what most YAML stacks do, so the reported behaviour is not surprising.
	 *
	 * @param mapping the mapping
	 * @param path the configuration path used in messages
	 * @return the entries, keyed by name, in document order
	 */
	Map<String, Node> entries(MappingNode mapping, String path) {
		Map<String, Node> result = new LinkedHashMap<>();
		for (NodeTuple tuple : mapping.getValue()) {
			String key = keyOf(tuple.getKeyNode(), path);
			if (key == null) {
				continue;
			}
			Node previous = result.put(key, tuple.getValueNode());
			if (previous != null) {
				issues.add(IssueCode.YAML_DUPLICATE_KEY, child(path, key), lineOf(tuple.getKeyNode()),
						"duplicate key '" + key + "'; the last occurrence is used");
			}
		}
		return result;
	}

	/**
	 * Extracts the name of a mapping key.
	 *
	 * @param keyNode the key node
	 * @param path the configuration path used in messages
	 * @return the key name, or {@code null} when the key is not a scalar
	 */
	private String keyOf(Node keyNode, String path) {
		if (keyNode instanceof ScalarNode scalar) {
			return scalar.getValue();
		}
		issues.add(IssueCode.INVALID_TYPE, path, lineOf(keyNode),
				"mapping keys must be plain names, found a " + describeType(keyNode));
		return null;
	}

	/**
	 * Reports every key of a mapping that the schema does not define.
	 *
	 * @param entries the mapping entries
	 * @param path the configuration path used in messages
	 * @param known the accepted key names
	 * @param policy what to do about unknown keys
	 */
	void reportUnknownKeys(Map<String, Node> entries, String path, Set<String> known, UnknownFieldPolicy policy) {
		if (policy == UnknownFieldPolicy.IGNORE) {
			return;
		}
		Severity severity = policy == UnknownFieldPolicy.ERROR ? Severity.ERROR : Severity.WARNING;
		for (Map.Entry<String, Node> entry : entries.entrySet()) {
			if (known.contains(entry.getKey())) {
				continue;
			}
			String suggestion = suggest(entry.getKey(), known);
			issues.add(severity, IssueCode.UNKNOWN_FIELD, child(path, entry.getKey()), lineOf(entry.getValue()),
					"unknown key '" + entry.getKey() + "' in " + (path.isEmpty() ? "the document" : path)
							+ (suggestion.isEmpty() ? "" : "; did you mean '" + suggestion + "'?"));
		}
	}

	/**
	 * Suggests the closest known key name, so a typo is immediately obvious.
	 *
	 * <p>A small edit-distance search over the accepted keys. With schemas this size the cost is
	 * irrelevant and the payoff — catching {@code max_x} for {@code max-x} — is large.
	 *
	 * @param unknown the key the administrator wrote
	 * @param known the accepted keys
	 * @return the closest key, or an empty string when nothing is close enough
	 */
	private static String suggest(String unknown, Set<String> known) {
		String best = "";
		int bestDistance = Integer.MAX_VALUE;
		int limit = Math.max(2, unknown.length() / 3 + 1);
		for (String candidate : known) {
			int distance = editDistance(unknown.toLowerCase(java.util.Locale.ROOT), candidate);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return bestDistance <= limit ? best : "";
	}

	/**
	 * Levenshtein distance, capped implicitly by the caller's threshold.
	 *
	 * @param a first string, already lower-cased
	 * @param b second string
	 * @return the edit distance
	 */
	private static int editDistance(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
				current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}

	/**
	 * Reads a string value.
	 *
	 * @param entries the parent mapping entries
	 * @param key the key to read
	 * @param path the parent configuration path
	 * @param fallback the value returned when the key is absent
	 * @param required whether a missing key is fatal
	 * @return the string, or the fallback
	 */
	String readString(Map<String, Node> entries, String key, String path, String fallback, boolean required) {
		Node node = entries.get(key);
		String childPath = child(path, key);
		if (isNull(node)) {
			if (required) {
				issues.add(IssueCode.MISSING_FIELD, childPath, missingLine(childPath),
						"required value '" + key + "' is missing");
			}
			return fallback;
		}
		if (node instanceof ScalarNode scalar) {
			return scalar.getValue();
		}
		issues.add(IssueCode.INVALID_TYPE, childPath, lineOf(node),
				"expected a single value but found a " + describeType(node));
		return fallback;
	}

	/**
	 * Reads an integer value.
	 *
	 * @param entries the parent mapping entries
	 * @param key the key to read
	 * @param path the parent configuration path
	 * @param fallback the value returned when the key is absent or invalid
	 * @param required whether a missing key is fatal
	 * @return the integer, or the fallback
	 */
	Integer readInt(Map<String, Node> entries, String key, String path, Integer fallback, boolean required) {
		Long value = readLong(entries, key, path, fallback == null ? null : fallback.longValue(), required);
		if (value == null) {
			return null;
		}
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			issues.add(IssueCode.INVALID_VALUE, child(path, key), lineOf(entries.get(key)),
					"value " + value + " does not fit in a 32-bit block coordinate");
			return fallback;
		}
		return value.intValue();
	}

	/**
	 * Reads a 64-bit integer value.
	 *
	 * @param entries the parent mapping entries
	 * @param key the key to read
	 * @param path the parent configuration path
	 * @param fallback the value returned when the key is absent or invalid
	 * @param required whether a missing key is fatal
	 * @return the long, or the fallback
	 */
	Long readLong(Map<String, Node> entries, String key, String path, Long fallback, boolean required) {
		Node node = entries.get(key);
		String childPath = child(path, key);
		if (isNull(node)) {
			if (required) {
				issues.add(IssueCode.MISSING_FIELD, childPath, missingLine(childPath),
						"required value '" + key + "' is missing");
			}
			return fallback;
		}
		if (node instanceof ScalarNode scalar) {
			String text = scalar.getValue().trim();
			boolean hex = text.startsWith("0x") || text.startsWith("0X") || text.startsWith("-0x")
					|| text.startsWith("-0X");
			try {
				if (hex) {
					boolean negative = text.startsWith("-");
					String digits = text.substring(negative ? 3 : 2);
					long parsed = Long.parseLong(digits, 16);
					return negative ? -parsed : parsed;
				}
				return Long.parseLong(text);
			} catch (NumberFormatException e) {
				if (scalar.getTag() == Tag.STR) {
					// A quoted non-numeric string, or a placeholder the administrator left behind.
					issues.add(IssueCode.INVALID_TYPE, childPath, lineOf(node),
							"expected a number but found '" + text + "'");
				} else {
					issues.add(IssueCode.INVALID_VALUE, childPath, lineOf(node),
							"'" + text + "' is not a valid integer");
				}
				return fallback;
			}
		}
		issues.add(IssueCode.INVALID_TYPE, childPath, lineOf(node),
				"expected a number but found a " + describeType(node));
		return fallback;
	}

	/**
	 * Reads a boolean value, accepting the usual YAML spellings.
	 *
	 * @param entries the parent mapping entries
	 * @param key the key to read
	 * @param path the parent configuration path
	 * @param fallback the value returned when the key is absent or invalid
	 * @return the boolean, or the fallback
	 */
	Boolean readBoolean(Map<String, Node> entries, String key, String path, Boolean fallback) {
		Node node = entries.get(key);
		String childPath = child(path, key);
		if (isNull(node)) {
			return fallback;
		}
		if (node instanceof ScalarNode scalar) {
			String text = scalar.getValue().trim().toLowerCase(java.util.Locale.ROOT);
			if (text.equals("true") || text.equals("yes") || text.equals("on")) {
				return Boolean.TRUE;
			}
			if (text.equals("false") || text.equals("no") || text.equals("off")) {
				return Boolean.FALSE;
			}
			issues.add(IssueCode.INVALID_VALUE, childPath, lineOf(node),
					"expected true or false but found '" + scalar.getValue() + "'");
			return fallback;
		}
		issues.add(IssueCode.INVALID_TYPE, childPath, lineOf(node),
				"expected true or false but found a " + describeType(node));
		return fallback;
	}

	/**
	 * Reads an enum value by trying a parser function.
	 *
	 * @param entries the parent mapping entries
	 * @param key the key to read
	 * @param path the parent configuration path
	 * @param fallback the value returned when the key is absent
	 * @param parser the parser for the enum type
	 * @param accepted the accepted spellings, listed in the error message
	 * @param <E> the enum type
	 * @return the parsed value, or the fallback
	 */
	<E> E readEnum(
			Map<String, Node> entries,
			String key,
			String path,
			E fallback,
			java.util.function.Function<String, java.util.Optional<E>> parser,
			String accepted) {
		Node node = entries.get(key);
		String childPath = child(path, key);
		if (isNull(node)) {
			return fallback;
		}
		if (!(node instanceof ScalarNode scalar)) {
			issues.add(IssueCode.INVALID_TYPE, childPath, lineOf(node),
					"expected one of " + accepted + " but found a " + describeType(node));
			return fallback;
		}
		return parser.apply(scalar.getValue()).orElseGet(() -> {
			issues.add(IssueCode.INVALID_VALUE, childPath, lineOf(node),
					"'" + scalar.getValue() + "' is not valid; expected one of " + accepted);
			return fallback;
		});
	}

	/**
	 * Records a raw scalar value without interpreting it, used for the seed.
	 *
	 * @param entries the parent mapping entries
	 * @param key the key to read
	 * @param path the parent configuration path
	 * @param required whether a missing key is fatal
	 * @return the raw text, or {@code null}
	 */
	String readRawScalar(Map<String, Node> entries, String key, String path, boolean required) {
		Node node = entries.get(key);
		String childPath = child(path, key);
		if (isNull(node)) {
			if (required) {
				issues.add(IssueCode.MISSING_FIELD, childPath, missingLine(childPath),
						"required value '" + key + "' is missing");
			}
			return null;
		}
		if (node instanceof ScalarNode scalar) {
			return scalar.getValue();
		}
		issues.add(IssueCode.INVALID_TYPE, childPath, lineOf(node),
				"expected a single value but found a " + describeType(node));
		return null;
	}

	/**
	 * Reports a finding on behalf of a caller that performed its own checks.
	 *
	 * @param severity how serious the finding is
	 * @param code the machine readable code
	 * @param path the configuration path
	 * @param line the source line, or {@code -1}
	 * @param message the explanation
	 */
	void report(Severity severity, IssueCode code, String path, int line, String message) {
		issues.add(severity, code, path, line, message);
	}

	/**
	 * Reports a finding with the code's default severity.
	 *
	 * @param code the machine readable code
	 * @param path the configuration path
	 * @param line the source line, or {@code -1}
	 * @param message the explanation
	 */
	void report(IssueCode code, String path, int line, String message) {
		issues.add(code, path, line, message);
	}

	/**
	 * Collects the node paths of a mapping for callers that need the raw document structure.
	 *
	 * @param mapping the mapping
	 * @return the value nodes in document order
	 */
	List<Node> values(MappingNode mapping) {
		List<Node> result = new ArrayList<>();
		for (NodeTuple tuple : mapping.getValue()) {
			result.add(tuple.getValueNode());
		}
		return result;
	}
}

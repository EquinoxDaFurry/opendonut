package net.opendonut.common.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Source line numbers for configuration paths.
 *
 * <p>The parser is the only component that knows where a value was written; the validator works on the
 * typed model. Passing this map between them means a semantic finding such as "two regions overlap" can
 * still point at the line the administrator has to edit, instead of at the file as a whole.
 *
 * <p>Keys are configuration paths in the same form used by {@link ValidationIssue#path()}, for example
 * {@code servers[2]} or {@code world.border.size}. A missing key yields {@code -1}, which the reporter
 * renders as "no location".
 */
public final class SourceMap {

	/** A map with no recorded locations. */
	public static final SourceMap EMPTY = new SourceMap(Map.of());

	private final Map<String, Integer> lines;

	private SourceMap(Map<String, Integer> lines) {
		this.lines = Map.copyOf(lines);
	}

	/**
	 * Builds a source map from a mutable builder map.
	 *
	 * @param lines the recorded locations
	 * @return the immutable map
	 */
	static SourceMap of(Map<String, Integer> lines) {
		return lines.isEmpty() ? EMPTY : new SourceMap(lines);
	}

	/**
	 * Creates a mutable builder.
	 *
	 * @return a builder that accepts {@code path -> line} pairs
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Looks a location up.
	 *
	 * @param path the configuration path
	 * @return the 1-based source line, or {@code -1} when unknown
	 */
	public int line(String path) {
		return lines.getOrDefault(path, -1);
	}

	/**
	 * Tests whether a location is known.
	 *
	 * @param path the configuration path
	 * @return {@code true} when a line was recorded
	 */
	public boolean has(String path) {
		return lines.containsKey(path);
	}

	/** @return every recorded location, unmodifiable */
	public Map<String, Integer> asMap() {
		return Collections.unmodifiableMap(lines);
	}

	/** @return the number of recorded locations */
	public int size() {
		return lines.size();
	}

	/**
	 * Mutable collector used while parsing.
	 */
	public static final class Builder {

		private final Map<String, Integer> lines = new HashMap<>();

		private Builder() {
		}

		/**
		 * Records a location; later writes for the same path are ignored so the first (outermost)
		 * occurrence wins, which is the one an administrator wants to see.
		 *
		 * @param path the configuration path
		 * @param line the 1-based source line, or {@code -1} to skip
		 * @return the builder, for chaining
		 */
		public Builder put(String path, int line) {
			if (line > 0) {
				lines.putIfAbsent(path, line);
			}
			return this;
		}

		/**
		 * Looks a location up while parsing is still in progress.
		 *
		 * @param path the configuration path
		 * @return the 1-based source line, or {@code -1} when unknown
		 */
		public int line(String path) {
			return lines.getOrDefault(path, -1);
		}

		/**
		 * Looks a location up, falling back to the closest recorded ancestor.
		 *
		 * <p>Used for findings about a value that is <em>absent</em>: there is no node to point at, so the
		 * best available location is the enclosing section the administrator has to edit. A message
		 * pointing at {@code world.border} is far more useful than one pointing nowhere.
		 *
		 * @param path the configuration path of the missing value
		 * @return the line of the closest known ancestor, or {@code -1} when nothing is known
		 */
		public int lineOfNearestAncestor(String path) {
			String candidate = path;
			while (!candidate.isEmpty()) {
				int line = lines.getOrDefault(candidate, -1);
				if (line > 0) {
					return line;
				}
				int cut = Math.max(candidate.lastIndexOf('.'), candidate.lastIndexOf('['));
				if (cut < 0) {
					return -1;
				}
				candidate = candidate.substring(0, cut);
			}
			return -1;
		}

		/**
		 * Freezes the recorded locations.
		 *
		 * @return the immutable source map
		 */
		public SourceMap build() {
			return SourceMap.of(lines);
		}
	}
}

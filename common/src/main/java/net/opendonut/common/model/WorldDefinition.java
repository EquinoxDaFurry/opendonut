package net.opendonut.common.model;

import java.util.Objects;

/**
 * The {@code world} section of the OpenDonut configuration: the identity of the shared world every
 * backend is supposed to be generating.
 *
 * <p>This is <em>not</em> a description of the current state of any world folder — OpenDonut never
 * writes to world folders. It is the contract that backends are checked against at startup: same
 * level name, same seed, same border.
 *
 * @param name the Minecraft level folder name every backend must use (usually {@code world})
 * @param seed the configured seed specification
 * @param border the square world border shared by all backends
 */
public record WorldDefinition(String name, SeedSpec seed, WorldBorderDefinition border) {

	/**
	 * Validates the world definition.
	 *
	 * @throws IllegalArgumentException if the name is blank
	 */
	public WorldDefinition {
		Objects.requireNonNull(name, "name");
		if (name.isBlank()) {
			throw new IllegalArgumentException("world name must not be blank");
		}
		Objects.requireNonNull(seed, "seed");
		Objects.requireNonNull(border, "border");
	}

	/**
	 * Human readable summary for logs.
	 *
	 * @return for example {@code OpenDonut (level 'world') seed=random center=(0,0) size=200000}
	 */
	public String describe() {
		return name + " (level '" + name + "') seed=" + seed.describe() + " " + border.describe();
	}

	@Override
	public String toString() {
		return describe();
	}
}

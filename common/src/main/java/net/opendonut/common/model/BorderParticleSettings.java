package net.opendonut.common.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The {@code border-particles} section: a cosmetic wall of particles that shows a player where their region
 * ends.
 *
 * <h2>What a player sees</h2>
 * A one-block-thick wall of particles standing on the boundary they are walking towards, clipped to a sphere
 * around them. Because the wall is the intersection of the sphere with the boundary plane, it appears when the
 * player is within {@code radius} blocks of the edge and grows as they approach — a handful of particles at
 * first, a full wall at the boundary. Nothing is drawn in the middle of a region.
 *
 * <p>The geometry is in {@link net.opendonut.common.region.BorderParticleGeometry}, which is Minecraft-free and
 * tested exactly; the backend only has to spawn a particle at each returned position.
 *
 * <h2>Why these are configurable</h2>
 * The wall is the one part of OpenDonut a player notices without being told about it, and what makes it
 * pleasant rather than annoying is a matter of taste and of hardware, not of correctness: how far away it
 * starts, how often it is redrawn, which particle it uses. Someone who finds it distracting sets
 * {@code enabled: false} and nothing else changes.
 *
 * @param enabled whether to draw anything at all
 * @param particle the particle to use, as a registry name: {@code end_rod}, {@code minecraft:end_rod}, and so
 *                 on. Only particles that need no extra data can be used — the "simple" ones — because a
 *                 particle such as a dust colour has parameters the configuration does not express
 * @param radius the radius of the sphere around each player, in blocks; the wall can be seen from this far
 *               away at most, and is at its widest when the player stands on the boundary
 * @param intervalTicks how often the wall is redrawn, in ticks: 1 redraws every tick, 4 five times a second.
 *                      Particles are short-lived, so a redraw is what keeps the wall present
 * @param maxPerSide the most particles to send for any one side of the region, keeping the nearest. A budget so
 *                   that a large radius on a long boundary cannot flood a connection; the default covers a
 *                   radius of nine comfortably
 */
public record BorderParticleSettings(
		boolean enabled,
		String particle,
		int radius,
		int intervalTicks,
		int maxPerSide) {

	/** The particle used unless the configuration says otherwise: a bright, still, unmistakable dot. */
	public static final String DEFAULT_PARTICLE = "end_rod";

	/** The radius that shows a player the boundary just before they reach it, without walling their view. */
	public static final int DEFAULT_RADIUS = 9;

	/** Five redraws a second: enough for a steady wall, a fifth of the traffic of redrawing every tick. */
	public static final int DEFAULT_INTERVAL_TICKS = 4;

	/** Enough for a nine-block disc with room to spare, and a hard ceiling on what one player can cost. */
	public static final int DEFAULT_MAX_PER_SIDE = 256;

	/** Largest radius accepted, in blocks. Beyond this the wall stops being a hint and becomes a view. */
	public static final int MAX_RADIUS = 32;

	/**
	 * A registry name, optionally namespaced. Deliberately strict: the value is resolved against Minecraft's
	 * particle registry, and a name that cannot be a registry name is a typo worth reporting at parse time.
	 *
	 * <p>Declared <em>before</em> {@link #DEFAULT}, because the record's constructor uses it: static fields are
	 * initialised in the order they are written, so a pattern declared below {@code DEFAULT} is still null when
	 * the default instance validates itself. That mistake produced an {@code ExceptionInInitializerError} on
	 * first use of this class, which the test suite caught — the geometry tests never touch it, the CLI tests do.
	 */
	private static final Pattern PARTICLE_NAME = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");

	/** The documented defaults, used when the {@code border-particles} section is absent. */
	public static final BorderParticleSettings DEFAULT = new BorderParticleSettings(
			true, DEFAULT_PARTICLE, DEFAULT_RADIUS, DEFAULT_INTERVAL_TICKS, DEFAULT_MAX_PER_SIDE);

	/**
	 * Validates the settings.
	 *
	 * @throws IllegalArgumentException if a value is out of range or the particle name is not a registry name
	 */
	public BorderParticleSettings {
		Objects.requireNonNull(particle, "particle");
		String normalised = particle.trim().toLowerCase(Locale.ROOT);
		if (!PARTICLE_NAME.matcher(normalised).matches()) {
			throw new IllegalArgumentException("border-particles.particle must be a registry name such as '"
					+ DEFAULT_PARTICLE + "' or 'minecraft:end_rod', got '" + particle + "'");
		}
		particle = normalised;
		if (radius < 1 || radius > MAX_RADIUS) {
			throw new IllegalArgumentException("border-particles.radius must be between 1 and " + MAX_RADIUS
					+ ", got " + radius);
		}
		if (intervalTicks < 1 || intervalTicks > 40) {
			throw new IllegalArgumentException("border-particles.interval-ticks must be between 1 and 40, got "
					+ intervalTicks);
		}
		if (maxPerSide < 1 || maxPerSide > 4096) {
			throw new IllegalArgumentException("border-particles.max-per-side must be between 1 and 4096, got "
					+ maxPerSide);
		}
	}

	/**
	 * The particle name as a full registry name.
	 *
	 * @return the name, with {@code minecraft:} added when the configuration omitted a namespace
	 */
	public String qualifiedParticleName() {
		return particle.indexOf(':') >= 0 ? particle : "minecraft:" + particle;
	}

	/**
	 * Renders the settings for a log line or a validation report.
	 *
	 * @return a one-line description
	 */
	public String describe() {
		return enabled
				? particle + ", radius " + radius + ", every " + intervalTicks + " tick(s), up to " + maxPerSide
						+ " per side"
				: "disabled";
	}
}

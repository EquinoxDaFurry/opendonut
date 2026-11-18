package net.opendonut.common.model;

/**
 * The {@code containment} section: what a backend does with things that end up outside its own region.
 *
 * <h2>The rule this configuration exists to serve</h2>
 * A backend must never let the outside of its region become real: no mobs spawning there, no blocks being
 * placed or broken there, dropped items rubber-banded back in, and a player physically unable to get more
 * than a couple of blocks past the border even when their connection is lagging. This server would otherwise
 * generate and write chunks that another server also considers its own, which is precisely the corruption
 * OpenDonut exists to prevent.
 *
 * <p>Containment is a safety invariant of the region model, not an optional extra, so it is enabled by
 * default — like the boundary transfer itself. Set {@code containment.enabled: false} to run without it
 * (not recommended).
 *
 * @param enabled whether hard containment is active on backends
 * @param maxOvershootBlocks how far past its own border a player may ever be, in blocks. Must be at least
 *                          {@code transfer.boundary-margin-blocks}, otherwise the boundary transfer could
 *                          never fire: the clamp would trigger first and the player would be pinned at the
 *                          border. The default of 2 matches the default margin.
 * @param borderProximityBlocks how close to its region's border a player must be before their chunk view is
 *                              shrunk
 * @param nearBorderChunkRadius the chunk-cache radius sent to a player who is within
 *                              {@code border-proximity-blocks} of the border, so they cannot load much of the
 *                              world outside the region
 * @param entitySweepIntervalTicks how often the backend sweeps the shell just outside its region for entities;
 *                                 {@code 0} disables the sweep (entity-load prevention still applies)
 * @param entitySweepShellBlocks how far outside its region the sweep reaches, in blocks
 */
public record ContainmentSettings(
		boolean enabled,
		int maxOvershootBlocks,
		int borderProximityBlocks,
		int nearBorderChunkRadius,
		int entitySweepIntervalTicks,
		int entitySweepShellBlocks) {

	/** Enabled by default: leaving the outside of a region unreal is the point of owning a region. */
	public static final ContainmentSettings DEFAULT = new ContainmentSettings(true, 2, 48, 3, 20, 64);

	/**
	 * Validates the settings.
	 *
	 * @throws IllegalArgumentException if a value is outside its supported range; the message is written to be
	 *                                  shown to an administrator
	 */
	public ContainmentSettings {
		if (maxOvershootBlocks < 0 || maxOvershootBlocks > 64) {
			throw new IllegalArgumentException(
					"containment.max-overshoot-blocks must be between 0 and 64, got " + maxOvershootBlocks);
		}
		if (borderProximityBlocks < 0 || borderProximityBlocks > 512) {
			throw new IllegalArgumentException("containment.border-proximity-blocks must be between 0 and 512, "
					+ "got " + borderProximityBlocks);
		}
		if (nearBorderChunkRadius < 1 || nearBorderChunkRadius > 32) {
			throw new IllegalArgumentException("containment.near-border-chunk-radius must be between 1 and 32 "
					+ "chunks, got " + nearBorderChunkRadius);
		}
		if (entitySweepIntervalTicks < 0 || entitySweepIntervalTicks > 1200) {
			throw new IllegalArgumentException("containment.entity-sweep-interval-ticks must be between 0 and "
					+ "1200, got " + entitySweepIntervalTicks);
		}
		if (entitySweepShellBlocks < 1 || entitySweepShellBlocks > 256) {
			throw new IllegalArgumentException("containment.entity-sweep-shell-blocks must be between 1 and "
					+ "256, got " + entitySweepShellBlocks);
		}
	}

	/**
	 * Renders the settings for a log line or a validation report.
	 *
	 * @return a one-line description
	 */
	public String describe() {
		if (!enabled) {
			return "disabled";
		}
		return "max overshoot " + maxOvershootBlocks + " block(s), chunk radius " + nearBorderChunkRadius
				+ " within " + borderProximityBlocks + " block(s) of the border, entity sweep every "
				+ entitySweepIntervalTicks + " tick(s) over " + entitySweepShellBlocks + " block(s)";
	}
}

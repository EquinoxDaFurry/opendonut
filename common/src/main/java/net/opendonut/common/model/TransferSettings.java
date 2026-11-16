package net.opendonut.common.model;

import java.util.Objects;

/**
 * Backend transfer behaviour: when to ask for a transfer, and what to do when one fails.
 *
 * <p>These values exist to stop the two classic multi-server world bugs: <em>transfer loops</em>
 * (two servers each believing the other should own a border block, so the player ping-pongs forever)
 * and <em>double transfers</em> (a second request being sent while the first is still in flight).
 *
 * @param boundaryMarginBlocks how far inside the new region a player must walk before a transfer is
 *                             requested. A value of {@code 1}-{@code 4} absorbs the jitter of a
 *                             player standing exactly on a boundary and of a client that reports
 *                             positions slightly ahead of the server.
 * @param joinGraceTicks how long after joining a backend ignores boundary checks. The player is
 *                       still being moved into place by the proxy and their saved position is not
 *                       yet meaningful; 40 ticks is two seconds.
 * @param cooldownSeconds minimum time between two transfer attempts for the same player. A transfer
 *                        that fails repeatedly backs off by this amount instead of hammering the
 *                        proxy.
 * @param maxPendingSeconds how long a request may stay unanswered before the backend treats it as
 *                          failed and starts enforcing the failure policy.
 * @param onTransferFailure behaviour when a transfer did not complete
 * @param unassignedAreaPolicy behaviour when a player enters an unassigned coordinate
 */
public record TransferSettings(
		int boundaryMarginBlocks,
		int joinGraceTicks,
		int cooldownSeconds,
		int maxPendingSeconds,
		TransferFailurePolicy onTransferFailure,
		UnassignedAreaPolicy unassignedAreaPolicy) {

	/** The documented defaults, used when the {@code transfer} section is absent. */
	public static final TransferSettings DEFAULT =
			new TransferSettings(2, 40, 5, 15, TransferFailurePolicy.REVERT, UnassignedAreaPolicy.ALLOW);

	/**
	 * Validates the settings.
	 *
	 * @throws IllegalArgumentException if a value is out of a sane range
	 */
	public TransferSettings {
		Objects.requireNonNull(onTransferFailure, "onTransferFailure");
		Objects.requireNonNull(unassignedAreaPolicy, "unassignedAreaPolicy");
		if (boundaryMarginBlocks < 0 || boundaryMarginBlocks > 512) {
			throw new IllegalArgumentException(
					"boundary margin must be between 0 and 512 blocks, got " + boundaryMarginBlocks);
		}
		if (joinGraceTicks < 0 || joinGraceTicks > 20 * 60) {
			throw new IllegalArgumentException(
					"join grace must be between 0 and 1200 ticks, got " + joinGraceTicks);
		}
		if (cooldownSeconds < 0 || cooldownSeconds > 3600) {
			throw new IllegalArgumentException("cooldown must be between 0 and 3600 seconds, got " + cooldownSeconds);
		}
		if (maxPendingSeconds < 1 || maxPendingSeconds > 600) {
			throw new IllegalArgumentException(
					"max pending time must be between 1 and 600 seconds, got " + maxPendingSeconds);
		}
	}

	/**
	 * Reports whether the cooldown is shorter than the pending timeout, in which case the pending
	 * timeout is the value that actually controls retries. The configuration validator surfaces this
	 * as an informational message so administrators are not surprised.
	 *
	 * @return {@code true} if the cooldown value has no effect
	 */
	public boolean cooldownIsShadowedByPendingTimeout() {
		return cooldownSeconds > 0 && maxPendingSeconds > cooldownSeconds;
	}
}

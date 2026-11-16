package net.opendonut.common.bridge;

/**
 * Why a bridge message was rejected.
 *
 * <p>Every failure has a distinct value so that the log can distinguish "an old backend is running an
 * older protocol" from "somebody is probing the channel", and so that a test can assert on the exact
 * rejection rather than on a message string.
 */
public enum DecodeFailure {

	/** The frame is shorter than the fixed header plus the authentication tag. */
	TRUNCATED,

	/** The frame does not start with the OpenDonut magic bytes. */
	BAD_MAGIC,

	/** The protocol version in the frame is not the one this build speaks. */
	UNSUPPORTED_VERSION,

	/** The frame carries a message id this build does not know. */
	UNKNOWN_MESSAGE_TYPE,

	/** The declared payload length is negative, larger than the limit, or longer than the frame. */
	PAYLOAD_TOO_LARGE,

	/** The HMAC did not verify: wrong secret, corrupted frame, or a forged message. */
	BAD_SIGNATURE,

	/** The timestamp is outside the accepted window, which usually means a clock is wrong. */
	STALE_TIMESTAMP,

	/** This exact frame has already been handled; a replay or a duplicated delivery. */
	REPLAY,

	/** The signature was valid but the payload could not be parsed. */
	MALFORMED_PAYLOAD,

	/** The shared secret is missing, empty or too short to be used. */
	INVALID_SECRET;

	/**
	 * Tests whether a failure indicates an attack rather than a misconfiguration.
	 *
	 * <p>Used to decide the log level: a forged message is worth a warning, a stale clock is not.
	 *
	 * @return {@code true} for failures that suggest deliberate interference
	 */
	public boolean isSuspicious() {
		return this == BAD_SIGNATURE || this == REPLAY || this == BAD_MAGIC;
	}
}

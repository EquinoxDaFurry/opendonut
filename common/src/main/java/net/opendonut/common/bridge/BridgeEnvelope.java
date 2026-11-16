package net.opendonut.common.bridge;

import java.util.Arrays;
import java.util.Objects;

/**
 * A decoded, authenticated bridge message together with the metadata used to authenticate it.
 *
 * @param message the decoded message
 * @param timestampMillis the sender's clock reading, in epoch milliseconds
 * @param nonce the 16-byte anti-replay nonce; wrapped defensively so the array cannot be mutated after
 *              decoding
 */
public record BridgeEnvelope(BridgeMessage message, long timestampMillis, byte[] nonce) {

	/**
	 * Validates the record and copies the nonce.
	 *
	 * @throws IllegalArgumentException if a component is missing or the nonce has the wrong length
	 */
	public BridgeEnvelope {
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(nonce, "nonce");
		if (nonce.length != BridgeCodec.NONCE_LENGTH) {
			throw new IllegalArgumentException(
					"nonce must be " + BridgeCodec.NONCE_LENGTH + " bytes, got " + nonce.length);
		}
		nonce = Arrays.copyOf(nonce, nonce.length);
	}

	@Override
	public byte[] nonce() {
		return Arrays.copyOf(nonce, nonce.length);
	}

	/**
	 * Tests whether this envelope is the same message as another, ignoring the authentication metadata.
	 *
	 * @param other the other envelope, may be {@code null}
	 * @return {@code true} if the messages are equal
	 */
	public boolean hasSameMessageAs(BridgeEnvelope other) {
		return other != null && message.equals(other.message);
	}
}

package net.opendonut.common.bridge;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of decoding a bridge frame: either an authenticated envelope, or a reason for rejection.
 *
 * <p>Modelled as a sealed hierarchy so that callers cannot forget to handle the failure branch: an
 * unchecked exception would be easy to swallow in a proxy event handler, and silently ignoring a
 * rejected transfer request is exactly the bug that makes a seamless world not look seamless.
 */
public sealed interface BridgeDecodeResult permits BridgeDecodeResult.Ok, BridgeDecodeResult.Failed {

	/**
	 * A frame that authenticated and parsed.
	 *
	 * @param envelope the message and its authentication metadata
	 */
	record Ok(BridgeEnvelope envelope) implements BridgeDecodeResult {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if the envelope is missing
		 */
		public Ok {
			Objects.requireNonNull(envelope, "envelope");
		}

		@Override
		public boolean isAccepted() {
			return true;
		}

		@Override
		public Optional<BridgeEnvelope> envelopeIfAccepted() {
			return Optional.of(envelope);
		}
	}

	/**
	 * A frame that was rejected.
	 *
	 * @param failure the reason
	 * @param detail a human readable explanation for the log
	 */
	record Failed(DecodeFailure failure, String detail) implements BridgeDecodeResult {

		/**
		 * Validates the record.
		 *
		 * @throws IllegalArgumentException if a component is missing
		 */
		public Failed {
			Objects.requireNonNull(failure, "failure");
			Objects.requireNonNull(detail, "detail");
		}

		@Override
		public boolean isAccepted() {
			return false;
		}

		@Override
		public Optional<BridgeEnvelope> envelopeIfAccepted() {
			return Optional.empty();
		}
	}

	/**
	 * Creates an accepted result.
	 *
	 * @param envelope the authenticated message
	 * @return the result
	 */
	static BridgeDecodeResult ok(BridgeEnvelope envelope) {
		return new Ok(envelope);
	}

	/**
	 * Creates a rejected result.
	 *
	 * @param failure the reason
	 * @param detail the explanation
	 * @return the result
	 */
	static BridgeDecodeResult failed(DecodeFailure failure, String detail) {
		return new Failed(failure, detail);
	}

	/**
	 * Tests whether the frame was accepted.
	 *
	 * @return {@code true} for {@link Ok}
	 */
	boolean isAccepted();

	/**
	 * The envelope, when the frame was accepted.
	 *
	 * @return the envelope, or empty when the frame was rejected
	 */
	Optional<BridgeEnvelope> envelopeIfAccepted();
}

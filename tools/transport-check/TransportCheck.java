import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import net.opendonut.common.bridge.BridgeCodec;
import net.opendonut.common.bridge.BridgeDecodeResult;
import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.bridge.BridgeSecret;
import net.opendonut.common.bridge.NonceCache;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;

/**
 * Verifies the payload-framing contract at the Minecraft custom-payload boundary, without a Minecraft client.
 *
 * <h2>Why this exists</h2>
 * A transfer failed in production with {@code BAD_MAGIC} on every frame, because the Fabric payload codec
 * wrapped the frame in a VarInt length prefix while Velocity passes the payload field through verbatim. The
 * symptom said nothing about framing, and reproducing it needed a player connected to a proxy.
 *
 * <p>This tool closes that gap: it drives <em>Velocity's own</em> {@code PluginMessagePacket} — the class the
 * proxy uses for plugin messages — in both directions around a real frame, and then feeds what a plugin would
 * receive into the shared decoder. If the bytes survive Velocity's own encode/decode cycle unchanged and the
 * decoder accepts them, the transport boundary is consistent.
 *
 * <h2>Running it</h2>
 * <pre>
 *   tools/verify-transport.ps1
 * </pre>
 * which compiles this file against the local Velocity jar and the built {@code common} jar, and runs it. See
 * {@code tools/transport-check/README.md}.
 *
 * <h2>Caveat</h2>
 * This harness uses Velocity's <em>internal</em> classes ({@code com.velocitypowered.proxy.*}), which are not
 * API. It is a development diagnostic, deliberately not part of the Gradle build: if a future Velocity release
 * changes those internals this file will stop compiling, and that is a signal to re-read the bytecode rather
 * than a build failure for the project itself.
 */
public final class TransportCheck {

	private static final String CHANNEL = "opendonut:bridge";
	private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_26_3;

	private TransportCheck() {
	}

	/**
	 * Runs the checks.
	 *
	 * @param args unused
	 */
	public static void main(String[] args) {
		byte[] secret = BridgeSecret.generate();
		long now = System.currentTimeMillis();
		int failures = 0;

		// The message type that failed in production.
		byte[] frame = BridgeCodec.encode(new BridgeMessage.TransferRequest(
				UUID.fromString("00000000-0000-0000-0000-000000000042"),
				"TestPlayer", "west", 1034, 69, 658, 90.0f, 0.0f, "east",
				"past max-x, 1034 block(s) outside"), secret, now, BridgeCodec.randomNonce());

		System.out.println("frame under test      : " + BridgeCodec.describeFrame(frame));

		failures += checkDirection("backend -> proxy", frame, secret, now,
				"the payload a proxy plugin receives", ProtocolUtils.Direction.CLIENTBOUND);
		failures += checkDirection("proxy -> backend", frame, secret, now,
				"the payload a backend mod receives", ProtocolUtils.Direction.CLIENTBOUND);

		// The old, broken framing: a VarInt length prefix that the receiver never expects.
		byte[] prefixed = varIntPrefixed(frame);
		BridgeDecodeResult broken = BridgeCodec.decode(prefixed, secret, now, 30, new NonceCache());
		System.out.println();
		System.out.println("length-prefixed framing (the old bug):");
		System.out.println("  as seen by the receiver : " + BridgeCodec.describeFrame(prefixed));
		System.out.println("  decoder                 : " + (broken.isAccepted() ? "ACCEPTED" : "REJECTED " + broken));
		if (broken.isAccepted()) {
			System.out.println("  FAIL: a length-prefixed payload must not be accepted");
			failures++;
		}

		System.out.println();
		if (failures == 0) {
			System.out.println("RESULT: PASS — the payload boundary carries frames verbatim in both directions");
		} else {
			System.out.println("RESULT: FAIL — " + failures + " check(s) failed (see above)");
			System.exit(1);
		}
	}

	/**
	 * Pushes a frame through Velocity's plugin message packet and back, then decodes what a receiver would see.
	 *
	 * @param label what is being checked, for the report
	 * @param frame the frame to carry
	 * @param secret the shared secret
	 * @param now the receiver's clock
	 * @param description what the payload represents
	 * @param direction the direction to encode in
	 * @return the number of failed checks
	 */
	private static int checkDirection(
			String label, byte[] frame, byte[] secret, long now, String description,
			ProtocolUtils.Direction direction) {

		int failures = 0;
		ByteBuf buffer = Unpooled.buffer();
		try {
			PluginMessagePacket outbound = new PluginMessagePacket(CHANNEL, Unpooled.wrappedBuffer(frame));
			outbound.encode(buffer, direction, VERSION);

			PluginMessagePacket parsed = new PluginMessagePacket();
			parsed.decode(buffer, direction, VERSION);
			byte[] received = ByteBufUtil.getBytes(parsed.content());

			System.out.println();
			System.out.println(label + ":");
			System.out.println("  " + description + " : " + BridgeCodec.describeFrame(received));

			boolean identical = Arrays.equals(frame, received);
			System.out.println("  byte-identical to the frame : " + identical);
			if (!identical) {
				failures++;
			}

			BridgeDecodeResult result = BridgeCodec.decode(received, secret, now, 30, new NonceCache());
			System.out.println("  decoder                     : " + (result.isAccepted()
					? "ACCEPTED (" + result.envelopeIfAccepted().orElseThrow().message().type() + ")"
					: "REJECTED " + result));
			if (!result.isAccepted()) {
				failures++;
			}
			return failures;
		} finally {
			buffer.release();
		}
	}

	/**
	 * Encodes a length prefix the way {@code FriendlyByteBuf.writeByteArray} does — the mistake this check
	 * exists to catch.
	 *
	 * @param frame the frame to wrap
	 * @return the prefixed payload
	 */
	private static byte[] varIntPrefixed(byte[] frame) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int remaining = frame.length;
		while ((remaining & ~0x7F) != 0) {
			out.write((remaining & 0x7F) | 0x80);
			remaining >>>= 7;
		}
		out.write(remaining);
		out.writeBytes(frame);
		return out.toByteArray();
	}
}

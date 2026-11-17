package net.opendonut.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code player-data} settings: the defaults are safe, every out-of-range value is refused with a message
 * an administrator can act on, and the unimplemented options fail loudly instead of half-working.
 */
class PlayerDataSettingsTest {

	@Test
	@DisplayName("the defaults are valid and disabled")
	void defaults() {
		PlayerDataSettings settings = PlayerDataSettings.DEFAULT;
		assertFalse(settings.enabled());
		assertEquals("disabled", settings.describe());
		assertEquals(PlayerDataSettings.STORAGE_SQLITE, settings.storage());
		assertEquals(PlayerDataSettings.DEFAULT_FILE, settings.file());
		assertEquals(60_000L, settings.syncIntervalMillis());
		assertEquals(60_000L, settings.leaseMillis());
		assertEquals(10_000L, settings.flushTimeoutMillis());
		assertFalse(settings.mayProceedWithoutFreshData());
	}

	@Nested
	@DisplayName("validation")
	class Validation {

		private static BuilderLike enabled() {
			return new BuilderLike();
		}

		@Test
		@DisplayName("an unimplemented storage is refused with the plan named")
		void storage() {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> enabled().storage("postgres").build());
			assertTrue(e.getMessage().contains("postgres"), e.getMessage());
			assertTrue(e.getMessage().contains("sqlite"), e.getMessage());
		}

		@Test
		@DisplayName("a blank or path-y file name is refused")
		void file() {
			assertThrows(IllegalArgumentException.class, () -> enabled().file("").build());
			assertThrows(IllegalArgumentException.class, () -> enabled().file("a/b.db").build());
			assertThrows(IllegalArgumentException.class, () -> enabled().file("a:b").build());
		}

		@Test
		@DisplayName("out-of-range numbers are refused")
		void ranges() {
			assertThrows(IllegalArgumentException.class, () -> enabled().syncIntervalSeconds(-1).build());
			assertThrows(IllegalArgumentException.class, () -> enabled().syncIntervalSeconds(3601).build());
			assertThrows(IllegalArgumentException.class, () -> enabled().leaseSeconds(9).build());
			assertThrows(IllegalArgumentException.class, () -> enabled().leaseSeconds(3601).build());
			assertThrows(IllegalArgumentException.class, () -> enabled().keepHistory(-1).build());
			assertThrows(IllegalArgumentException.class, () -> enabled().keepHistory(1001).build());
			assertThrows(IllegalArgumentException.class, () -> enabled().maxPayloadBytes(1023).build());
			assertThrows(IllegalArgumentException.class,
					() -> enabled().maxPayloadBytes(PlayerDataSettings.MAX_PAYLOAD_BYTES_LIMIT + 1).build());
			assertThrows(IllegalArgumentException.class, () -> enabled().flushTimeoutSeconds(0).build());
			assertThrows(IllegalArgumentException.class, () -> enabled().flushTimeoutSeconds(61).build());
		}

		@Test
		@DisplayName("an unimplemented scope is refused and names what is implemented")
		void scope() {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> enabled().scope(PlayerDataSettings.SyncScope.PROGRESS).build());
			assertTrue(e.getMessage().contains("progress"), e.getMessage());
			assertTrue(e.getMessage().contains("session"), e.getMessage());
		}
	}

	@Nested
	@DisplayName("enums")
	class Enums {

		@Test
		@DisplayName("on-flush-failure parses its spellings")
		void flushFailure() {
			assertEquals(Optional.of(PlayerDataSettings.OnFlushFailure.REFUSE_TRANSFER),
					PlayerDataSettings.OnFlushFailure.parse("refuse-transfer"));
			assertEquals(Optional.of(PlayerDataSettings.OnFlushFailure.REFUSE_TRANSFER),
					PlayerDataSettings.OnFlushFailure.parse("REFUSE"));
			assertEquals(Optional.of(PlayerDataSettings.OnFlushFailure.PROCEED_WITH_LAST_KNOWN),
					PlayerDataSettings.OnFlushFailure.parse("proceed_with_last_known"));
			assertEquals(Optional.of(PlayerDataSettings.OnFlushFailure.PROCEED_WITH_LAST_KNOWN),
					PlayerDataSettings.OnFlushFailure.parse("last-known"));
			assertEquals(Optional.empty(), PlayerDataSettings.OnFlushFailure.parse("explode"));
			assertEquals(Optional.empty(), PlayerDataSettings.OnFlushFailure.parse(null));
		}

		@Test
		@DisplayName("scope parses its levels")
		void scope() {
			assertEquals(Optional.of(PlayerDataSettings.SyncScope.SESSION),
					PlayerDataSettings.SyncScope.parse("session"));
			assertEquals(Optional.of(PlayerDataSettings.SyncScope.EVERYTHING),
					PlayerDataSettings.SyncScope.parse("Everything"));
			assertEquals(Optional.empty(), PlayerDataSettings.SyncScope.parse("inventory-only"));
		}
	}

	@Test
	@DisplayName("describe summarises an enabled configuration")
	void describeEnabled() {
		PlayerDataSettings settings = new BuilderLike()
				.onFlushFailure(PlayerDataSettings.OnFlushFailure.PROCEED_WITH_LAST_KNOWN)
				.build();
		String text = settings.describe();
		assertTrue(text.contains("sqlite"), text);
		assertTrue(text.contains("session"), text);
		assertTrue(text.contains("proceed-with-last-known"), text);
		assertTrue(text.contains("flush timeout 10 s"), text);
		assertTrue(settings.mayProceedWithoutFreshData());
	}

	/**
	 * A small builder so the validation tests read as "change one field, expect a refusal".
	 */
	static final class BuilderLike {

		private boolean enabled = true;
		private String storage = PlayerDataSettings.STORAGE_SQLITE;
		private String file = PlayerDataSettings.DEFAULT_FILE;
		private int syncIntervalSeconds = 60;
		private int leaseSeconds = 60;
		private PlayerDataSettings.OnFlushFailure onFlushFailure = PlayerDataSettings.OnFlushFailure.REFUSE_TRANSFER;
		private PlayerDataSettings.SyncScope scope = PlayerDataSettings.SyncScope.SESSION;
		private int keepHistory = 20;
		private int maxPayloadBytes = PlayerDataSettings.DEFAULT_MAX_PAYLOAD_BYTES;
		private int flushTimeoutSeconds = 10;

		BuilderLike enabled(boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		BuilderLike storage(String storage) {
			this.storage = storage;
			return this;
		}

		BuilderLike file(String file) {
			this.file = file;
			return this;
		}

		BuilderLike syncIntervalSeconds(int value) {
			this.syncIntervalSeconds = value;
			return this;
		}

		BuilderLike leaseSeconds(int value) {
			this.leaseSeconds = value;
			return this;
		}

		BuilderLike onFlushFailure(PlayerDataSettings.OnFlushFailure value) {
			this.onFlushFailure = value;
			return this;
		}

		BuilderLike scope(PlayerDataSettings.SyncScope value) {
			this.scope = value;
			return this;
		}

		BuilderLike keepHistory(int value) {
			this.keepHistory = value;
			return this;
		}

		BuilderLike maxPayloadBytes(int value) {
			this.maxPayloadBytes = value;
			return this;
		}

		BuilderLike flushTimeoutSeconds(int value) {
			this.flushTimeoutSeconds = value;
			return this;
		}

		PlayerDataSettings build() {
			return new PlayerDataSettings(enabled, storage, file, syncIntervalSeconds, leaseSeconds, onFlushFailure,
					scope, keepHistory, maxPayloadBytes, flushTimeoutSeconds);
		}
	}
}

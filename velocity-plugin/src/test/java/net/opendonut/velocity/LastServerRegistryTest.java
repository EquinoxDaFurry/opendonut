package net.opendonut.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The last-server memory behind {@code proxy.remember-last-server}: remember, overwrite, look up, and the
 * size cap that keeps a long-lived proxy bounded.
 */
class LastServerRegistryTest {

	@Test
	@DisplayName("a remembered server is returned, and a newer connection overwrites it")
	void rememberAndLookup() {
		LastServerRegistry registry = new LastServerRegistry();
		UUID player = UUID.randomUUID();

		assertNull(registry.lookup(player), "a player never seen has no last server");

		registry.remember(player, "west");
		assertEquals("west", registry.lookup(player));

		registry.remember(player, "east");
		assertEquals("east", registry.lookup(player), "the latest connection wins");
		assertEquals(1, registry.size());
	}

	@Test
	@DisplayName("blank names are ignored so a half-known state cannot be recorded")
	void blankIgnored() {
		LastServerRegistry registry = new LastServerRegistry();
		UUID player = UUID.randomUUID();
		registry.remember(player, "");
		registry.remember(player, null);
		assertNull(registry.lookup(player));
		assertEquals(0, registry.size());
	}

	@Test
	@DisplayName("forget removes the entry")
	void forget() {
		LastServerRegistry registry = new LastServerRegistry();
		UUID player = UUID.randomUUID();
		registry.remember(player, "west");
		registry.forget(player);
		assertNull(registry.lookup(player));
	}

	@Test
	@DisplayName("the map is capped: once full, unknown players are not remembered but known ones still update")
	void capped() {
		LastServerRegistry registry = new LastServerRegistry();
		for (int i = 0; i < LastServerRegistry.MAX_ENTRIES; i++) {
			registry.remember(UUID.randomUUID(), "west");
		}
		assertEquals(LastServerRegistry.MAX_ENTRIES, registry.size());

		UUID unknown = UUID.randomUUID();
		registry.remember(unknown, "east");
		assertNull(registry.lookup(unknown), "a new player is not remembered once the map is full");
		assertEquals(LastServerRegistry.MAX_ENTRIES, registry.size());

		UUID known = UUID.randomUUID();
		// Fill with known first: make this one known before the cap.
		LastServerRegistry registry2 = new LastServerRegistry();
		for (int i = 0; i < LastServerRegistry.MAX_ENTRIES - 1; i++) {
			registry2.remember(UUID.randomUUID(), "west");
		}
		registry2.remember(known, "west");
		// one more slot left
		registry2.remember(UUID.randomUUID(), "west");
		registry2.remember(known, "east");
		assertEquals("east", registry2.lookup(known), "a known player still updates past the cap");
	}
}

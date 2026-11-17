package net.opendonut.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validation of the {@code time} section: every value is range-checked at construction, and a configuration
 * that asks for something impossible must fail loudly rather than half-work.
 */
class TimeSettingsTest {

	@Test
	@DisplayName("the default is disabled with auto authority and the overworld clock")
	void defaults() {
		TimeSettings settings = TimeSettings.DEFAULT;
		assertFalse(settings.enabled());
		assertTrue(settings.authorityIsAuto());
		assertEquals("auto", settings.explicitAuthority());
		assertEquals("minecraft:overworld", settings.clockId());
		assertEquals(5_000L, settings.reportIntervalMillis());
		assertEquals(30_000L, settings.syncIntervalMillis());
		assertEquals("disabled", settings.describe());
	}

	@Test
	@DisplayName("an explicit authority and a full clock id are accepted and normalised to lowercase")
	void explicitAuthority() {
		TimeSettings settings = new TimeSettings(true, "West", "Minecraft:Overworld", 5, 30, 20);
		assertTrue(settings.enabled());
		assertFalse(settings.authorityIsAuto());
		assertEquals("west", settings.explicitAuthority());
		assertEquals("minecraft:overworld", settings.clockId());
		assertTrue(settings.describe().contains("west"));
	}

	@Test
	@DisplayName("a clock id without a namespace is accepted (the registry fills in minecraft:)")
	void clockWithoutNamespace() {
		TimeSettings settings = new TimeSettings(true, "auto", "overworld", 5, 30, 20);
		assertEquals("overworld", settings.clockId());
	}

	@Test
	@DisplayName("an empty authority is rejected")
	void emptyAuthority() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new TimeSettings(true, "  ", "minecraft:overworld", 5, 30, 20));
		assertTrue(e.getMessage().contains("time.authority"), e.getMessage());
	}

	@Test
	@DisplayName("an authority with characters outside a server id is rejected")
	void badAuthority() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new TimeSettings(true, "west east", "minecraft:overworld", 5, 30, 20));
		assertTrue(e.getMessage().contains("time.authority"), e.getMessage());
	}

	@Test
	@DisplayName("an invalid clock id is rejected")
	void badClock() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new TimeSettings(true, "auto", "not a clock!!", 5, 30, 20));
		assertTrue(e.getMessage().contains("time.clock"), e.getMessage());
	}

	@Test
	@DisplayName("intervals outside their ranges are rejected; zero is legal only for the push interval")
	void intervalRanges() {
		assertThrows(IllegalArgumentException.class,
				() -> new TimeSettings(true, "auto", "minecraft:overworld", 0, 30, 20));
		assertThrows(IllegalArgumentException.class,
				() -> new TimeSettings(true, "auto", "minecraft:overworld", 3601, 30, 20));
		assertThrows(IllegalArgumentException.class,
				() -> new TimeSettings(true, "auto", "minecraft:overworld", 5, -1, 20));
		assertThrows(IllegalArgumentException.class,
				() -> new TimeSettings(true, "auto", "minecraft:overworld", 5, 3601, 20));
		assertThrows(IllegalArgumentException.class,
				() -> new TimeSettings(true, "auto", "minecraft:overworld", 5, 30, -1));

		TimeSettings zeroPush = new TimeSettings(true, "auto", "minecraft:overworld", 5, 0, 20);
		assertEquals(0L, zeroPush.syncIntervalMillis());
	}
}

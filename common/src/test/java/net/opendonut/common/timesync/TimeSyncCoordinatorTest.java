package net.opendonut.common.timesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.opendonut.common.model.TimeSettings;
import net.opendonut.common.timesync.TimeSyncCoordinator.ClockReading;
import net.opendonut.common.timesync.TimeSyncCoordinator.ReportVerdict;

/**
 * The proxy-side time coordinator: authority resolution, which reports are trusted, and the wall-clock
 * estimate between reports.
 */
class TimeSyncCoordinatorTest {

	private static final long T0 = 1_700_000_000_000L;

	private static TimeSettings auto() {
		return new TimeSettings(true, "auto", "minecraft:overworld", 5, 30, 20);
	}

	private static TimeSettings explicit(String authority) {
		return new TimeSettings(true, authority, "minecraft:overworld", 5, 30, 20);
	}

	@Test
	@DisplayName("with authority auto, the first verified backend becomes the authority, once")
	void autoAuthorityIsFirstVerified() {
		TimeSyncCoordinator coordinator = new TimeSyncCoordinator(auto());
		assertNull(coordinator.authorityId());
		coordinator.onBackendVerified("west", T0);
		coordinator.onBackendVerified("east", T0 + 1);
		assertEquals("west", coordinator.authorityId());
		assertTrue(coordinator.isAuthority("west"));
		assertFalse(coordinator.isAuthority("east"));
	}

	@Test
	@DisplayName("with an explicit authority, verification of other backends does not change it")
	void explicitAuthorityIsFixed() {
		TimeSyncCoordinator coordinator = new TimeSyncCoordinator(explicit("east"));
		assertEquals("east", coordinator.authorityId());
		coordinator.onBackendVerified("west", T0);
		assertEquals("east", coordinator.authorityId());
		assertFalse(coordinator.isAuthority("west"));
		assertTrue(coordinator.isAuthority("east"));
	}

	@Test
	@DisplayName("only the authority's report is stored; other backends are refused (warned once)")
	void onlyAuthorityReportsAreTrusted() {
		TimeSyncCoordinator coordinator = new TimeSyncCoordinator(explicit("west"));

		ReportVerdict impostor = coordinator.onReport("east",
				new ClockReading("minecraft:overworld", 999_999L, 1.0f, false), T0);
		assertFalse(impostor.accepted());
		assertTrue(impostor.detail().contains("not the time authority"), impostor.detail());
		// The second report from the same impostor is refused silently, so a misconfigured backend cannot
		// spam the log every five seconds.
		ReportVerdict again = coordinator.onReport("east",
				new ClockReading("minecraft:overworld", 888_888L, 1.0f, false), T0 + 5_000);
		assertFalse(again.accepted());
		assertEquals("", again.detail());

		assertTrue(coordinator.estimateAt(T0).isEmpty(), "the impostor's reading must not become the base");

		ReportVerdict authority = coordinator.onReport("west",
				new ClockReading("minecraft:overworld", 1000L, 1.0f, false), T0);
		assertTrue(authority.accepted(), authority.detail());
		assertEquals(Optional.of(new ClockReading("minecraft:overworld", 1000L, 1.0f, false)),
				coordinator.estimateAt(T0));
	}

	@Test
	@DisplayName("a report for a different clock than configured is refused even from the authority")
	void wrongClockRefused() {
		TimeSyncCoordinator coordinator = new TimeSyncCoordinator(explicit("west"));
		ReportVerdict verdict = coordinator.onReport("west",
				new ClockReading("minecraft:the_end", 5L, 1.0f, false), T0);
		assertFalse(verdict.accepted());
		assertTrue(verdict.detail().contains("minecraft:the_end"), verdict.detail());
		assertTrue(coordinator.estimateAt(T0).isEmpty());
	}

	@Test
	@DisplayName("the estimate advances by wall clock at 20 ticks per second at rate 1")
	void estimateAdvancesAtGameRate() {
		TimeSyncCoordinator coordinator = new TimeSyncCoordinator(explicit("west"));
		coordinator.onReport("west", new ClockReading("minecraft:overworld", 24_000L, 1.0f, false), T0);
		// 5 seconds later: 5 * 20 = 100 ticks.
		assertEquals(24_100L, coordinator.estimateAt(T0 + 5_000).orElseThrow().totalTicks());
		// 250 ms later: 5 ticks.
		assertEquals(24_005L, coordinator.estimateAt(T0 + 250).orElseThrow().totalTicks());
	}

	@Test
	@DisplayName("the estimate honours the reported rate and does not advance while paused")
	void estimateHonoursRateAndPause() {
		TimeSyncCoordinator coordinator = new TimeSyncCoordinator(explicit("west"));
		coordinator.onReport("west", new ClockReading("minecraft:overworld", 1_000L, 2.0f, false), T0);
		// rate 2 at 20 tps: 40 ticks per wall second.
		assertEquals(1_040L, coordinator.estimateAt(T0 + 1_000).orElseThrow().totalTicks());

		TimeSyncCoordinator paused = new TimeSyncCoordinator(explicit("west"));
		paused.onReport("west", new ClockReading("minecraft:overworld", 1_000L, 1.0f, true), T0);
		ClockReading estimate = paused.estimateAt(T0 + 60_000).orElseThrow();
		assertEquals(1_000L, estimate.totalTicks());
		assertTrue(estimate.paused());
	}

	@Test
	@DisplayName("a later report snaps the estimate to the authority, even backwards")
	void laterReportSnapsEstimate() {
		TimeSyncCoordinator coordinator = new TimeSyncCoordinator(explicit("west"));
		coordinator.onReport("west", new ClockReading("minecraft:overworld", 50_000L, 1.0f, false), T0);
		assertEquals(50_100L, coordinator.estimateAt(T0 + 5_000).orElseThrow().totalTicks());
		// Somebody ran /time set on the authority while it was unreachable; the next report wins.
		coordinator.onReport("west", new ClockReading("minecraft:overworld", 18_000L, 1.0f, false), T0 + 6_000);
		assertEquals(18_000L, coordinator.estimateAt(T0 + 6_000).orElseThrow().totalTicks());
		assertEquals(18_020L, coordinator.estimateAt(T0 + 7_000).orElseThrow().totalTicks());
	}

	@Test
	@DisplayName("the estimate never goes below zero, even if the wall clock is behind the report stamp")
	void estimateClampsNegativeElapsed() {
		TimeSyncCoordinator coordinator = new TimeSyncCoordinator(explicit("west"));
		coordinator.onReport("west", new ClockReading("minecraft:overworld", 100L, 1.0f, false), T0);
		assertEquals(100L, coordinator.estimateAt(T0 - 10_000).orElseThrow().totalTicks());
	}

	@Test
	@DisplayName("describe names the authority and the last reading")
	void describeWorks() {
		TimeSyncCoordinator coordinator = new TimeSyncCoordinator(explicit("west"));
		assertTrue(coordinator.describe().contains("not resolved") || coordinator.describe().contains("'west'"));
		coordinator.onReport("west", new ClockReading("minecraft:overworld", 42L, 1.0f, false), T0);
		String text = coordinator.describe();
		assertTrue(text.contains("'west'"), text);
		assertTrue(text.contains("42"), text);
	}
}

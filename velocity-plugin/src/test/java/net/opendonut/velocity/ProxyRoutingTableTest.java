package net.opendonut.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.opendonut.common.bridge.BridgeMessage;
import net.opendonut.common.config.ConfigLoader;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.ServerDefinition;

/**
 * The proxy's decision logic, tested without a proxy.
 *
 * <p>{@link ProxyRoutingTable} holds the rules that decide whether a player moves and whether a backend is
 * allowed to receive one. Those rules are the security boundary of the whole system — a backend's suggestion is
 * ignored in favour of this table — so they are worth testing directly rather than only through a live transfer.
 * Because the class deliberately has no Velocity imports, that is possible: only the transport is missing here,
 * not the logic.
 */
class ProxyRoutingTableTest {

	private static final String TWO_SERVERS = """
			config-version: 1
			world:
			  name: "world"
			  seed: 1234
			  border:
			    center-x: 0
			    center-z: 0
			    size: 200000
			validation:
			  require-full-coverage: true
			servers:
			  - id: west
			    address: "127.0.0.1:25566"
			    bounds: { min-x: -100000, max-x: 0, min-z: -100000, max-z: 100000 }
			  - id: east
			    address: "127.0.0.1:25567"
			    bounds: { min-x: 0, max-x: 100000, min-z: -100000, max-z: 100000 }
			""";

	private static final String MINECRAFT_VERSION = "26.3";

	private OpenDonutConfig config;
	private ProxyRoutingTable table;

	@BeforeEach
	void setUp() {
		config = ConfigLoader.loadFromString(TWO_SERVERS, "test.yml").requireUsable();
		table = new ProxyRoutingTable(config);
	}

	/**
	 * Sends a HELLO that matches the configuration, so a test can vary exactly one field.
	 *
	 * @param connectionName the name the proxy knows the connection by
	 * @param claimedId the id the backend claims
	 * @param worldName the level name
	 * @param seed the reported seed
	 * @param bounds the claimed region
	 * @param minecraftVersion the reported Minecraft version
	 * @return the verdict
	 */
	private ProxyRoutingTable.Verdict hello(
			String connectionName, String claimedId, String worldName, long seed,
			net.opendonut.common.model.RegionBounds bounds, String minecraftVersion) {
		return table.acceptHello(connectionName, claimedId, worldName, seed, bounds, minecraftVersion,
				"0.1.0-dev", 1234L, MINECRAFT_VERSION);
	}

	private net.opendonut.common.model.RegionBounds west() {
		return config.server("west").orElseThrow().bounds();
	}

	@Nested
	@DisplayName("backend verification")
	class Verification {

		@Test
		@DisplayName("a backend that matches the configuration is verified")
		void matchingBackendIsVerified() {
			ProxyRoutingTable.Verdict verdict = hello("west", "west", "world", 1234L, west(), MINECRAFT_VERSION);
			assertTrue(verdict.accepted(), verdict.detail());
			assertTrue(verdict.state().verified());
			assertTrue(verdict.detail().contains("match"), verdict.detail());
			assertTrue(table.mayReceivePlayers("west"));
		}

		@Test
		@DisplayName("a backend that claims another server's identity is rejected")
		void impersonationIsRejected() {
			// This is the case that matters: the proxy knows which registered server a connection belongs to, so
			// a backend cannot claim to be a different one.
			ProxyRoutingTable.Verdict verdict = hello("west", "east", "world", 1234L, west(), MINECRAFT_VERSION);
			assertFalse(verdict.accepted());
			assertTrue(verdict.detail().contains("the proxy knows"), verdict.detail());
			assertFalse(table.mayReceivePlayers("east"));
		}

		@Test
		@DisplayName("a backend with an unconfigured id is rejected")
		void unknownIdIsRejected() {
			ProxyRoutingTable.Verdict verdict = hello("south", "south", "world", 1234L, west(), MINECRAFT_VERSION);
			assertFalse(verdict.accepted());
			assertTrue(verdict.detail().contains("not defined"), verdict.detail());
		}

		@Test
		@DisplayName("a backend whose region differs from the configuration is rejected")
		void regionMismatchIsRejected() {
			net.opendonut.common.model.RegionBounds wrong = new net.opendonut.common.model.RegionBounds(0, 100000, -100000, 100000);
			ProxyRoutingTable.Verdict verdict = hello("west", "west", "world", 1234L, wrong, MINECRAFT_VERSION);
			assertFalse(verdict.accepted());
			assertTrue(verdict.detail().contains("region"), verdict.detail());
			assertTrue(verdict.detail().contains("east"), "the mismatch should name the rectangle it actually is");
		}

		@Test
		@DisplayName("a backend with a different level name is rejected")
		void levelNameMismatchIsRejected() {
			ProxyRoutingTable.Verdict verdict = hello("west", "west", "world_nether", 1234L, west(), MINECRAFT_VERSION);
			assertFalse(verdict.accepted());
			assertTrue(verdict.detail().contains("world_nether"), verdict.detail());
		}

		@Test
		@DisplayName("a backend generating different terrain is rejected")
		void seedMismatchIsRejected() {
			ProxyRoutingTable.Verdict verdict = hello("west", "west", "world", 999L, west(), MINECRAFT_VERSION);
			assertFalse(verdict.accepted());
			assertTrue(verdict.detail().contains("seed"), verdict.detail());
			assertTrue(verdict.detail().contains("999"), verdict.detail());
			assertTrue(verdict.detail().contains("1234"), verdict.detail());
		}

		@Test
		@DisplayName("an unknown seed is accepted with a warning, because it cannot be checked")
		void unknownSeedIsAcceptedWithAWarning() {
			ProxyRoutingTable.Verdict verdict = hello("west", "west", "world",
					BridgeMessage.Hello.UNKNOWN_SEED, west(), MINECRAFT_VERSION);
			assertTrue(verdict.accepted(), verdict.detail());
			assertFalse(verdict.state().warnings().isEmpty());
			assertTrue(verdict.state().warnings().get(0).contains("seed"), verdict.detail());
		}

		@Test
		@DisplayName("a different Minecraft version is a warning, not a rejection")
		void versionDifferenceIsAWarning() {
			ProxyRoutingTable.Verdict verdict = hello("west", "west", "world", 1234L, west(), "26.4");
			assertTrue(verdict.accepted(), verdict.detail());
			assertTrue(verdict.detail().contains("26.4"), verdict.detail());
		}

		@Test
		@DisplayName("require-backend-hello defaults to true, so an unverified backend is not routed to")
		void requiresAVerifiedHelloByDefault() {
			assertTrue(config.proxy().requireBackendHello(), "the documented default is the safe one");
			assertFalse(table.mayReceivePlayers("west"), "nothing has been heard from west yet");
		}

		@Test
		@DisplayName("verification state is recorded and replaced by a later HELLO")
		void stateIsReplaced() {
			hello("west", "west", "world", 999L, west(), MINECRAFT_VERSION);
			assertFalse(table.mayReceivePlayers("west"));
			hello("west", "west", "world", 1234L, west(), MINECRAFT_VERSION);
			assertTrue(table.mayReceivePlayers("west"), "a fixed backend must be able to recover");
			assertEquals(1, table.backends().size(), "one entry per backend, not per HELLO");
		}
	}

	@Nested
	@DisplayName("routing")
	class Routing {

		@Test
		@DisplayName("ownership comes from this table, including at the shared edge")
		void ownershipIsLocal() {
			assertEquals("west", table.owner(-1, 0).orElseThrow().id());
			assertEquals("east", table.owner(0, 0).orElseThrow().id());
			assertEquals("west", table.owner(-100000, -100000).orElseThrow().id());
			assertEquals("east", table.owner(99999, 99999).orElseThrow().id());
		}

		@Test
		@DisplayName("unowned coordinates have no owner")
		void unownedCoordinates() {
			assertTrue(table.owner(100000, 0).isEmpty());
			assertTrue(table.owner(-100001, 0).isEmpty());
			assertTrue(table.owner(0, 100000).isEmpty());
		}

		@Test
		@DisplayName("the table exposes the same regions as the configuration")
		void regionsComeFromTheConfiguration() {
			List<String> ids = config.servers().stream().map(ServerDefinition::id).toList();
			assertEquals(ids, List.of("west", "east"));
			assertEquals(2, table.index().regions().size());
		}
	}

	@Nested
	@DisplayName("pending transfers")
	class PendingTransfers {

		private ProxyRoutingTable.PendingTransfer transfer(UUID playerId, long issuedAt) {
			return new ProxyRoutingTable.PendingTransfer(playerId, "west", "east", 0, 64, -1500, 90.0f, 0.0f,
					"past max-x", issuedAt);
		}

		@Test
		@DisplayName("a transfer can be recorded, found and cleared")
		void recordAndClear() {
			UUID playerId = UUID.randomUUID();
			ProxyRoutingTable.PendingTransfer pending = transfer(playerId, System.currentTimeMillis());
			table.markPending(pending);

			assertEquals(pending, table.pending(playerId).orElseThrow());
			assertEquals(1, table.pendingCount());
			assertEquals(pending, table.clearPending(playerId).orElseThrow());
			assertTrue(table.pending(playerId).isEmpty());
			assertEquals(0, table.pendingCount());
		}

		@Test
		@DisplayName("a stale transfer is dropped, so it cannot be applied to a later session")
		void staleTransfersArePruned() {
			UUID stale = UUID.randomUUID();
			UUID live = UUID.randomUUID();
			table.markPending(transfer(stale, System.currentTimeMillis() - 10 * 60_000L));
			table.markPending(transfer(live, System.currentTimeMillis()));

			assertEquals(1, table.pruneStale(60_000L));
			assertTrue(table.pending(stale).isEmpty());
			assertTrue(table.pending(live).isPresent());
		}

		@Test
		@DisplayName("the placement carries the coordinates and the facing")
		void placementCarriesCoordinates() {
			UUID playerId = UUID.randomUUID();
			ProxyRoutingTable.PendingTransfer pending = transfer(playerId, System.currentTimeMillis());
			BridgeMessage.PlacePlayer placement = table.placementFor(pending);

			assertEquals(playerId, placement.playerId());
			assertEquals("west", placement.fromServerId());
			assertEquals("east", placement.toServerId());
			assertEquals(0, placement.globalX());
			assertEquals(64, placement.globalY());
			assertEquals(-1500, placement.globalZ());
			assertEquals(90.0f, placement.yaw());
			assertEquals(0.0f, placement.pitch());
			assertEquals(pending.issuedAtMillis(), placement.issuedAtMillis());
		}
	}

	@Nested
	@DisplayName("diagnostic output")
	class Diagnostics {

		@Test
		@DisplayName("the routing table names every region and its state")
		void routingTableOutput() {
			String before = table.describeRegions(List.of("west", "east"));
			assertTrue(before.contains("west"), before);
			assertTrue(before.contains("east"), before);
			assertTrue(before.contains("X=[-100000,0)"), before);
			assertTrue(before.contains("[no HELLO yet]"), before);

			hello("west", "west", "world", 1234L, west(), MINECRAFT_VERSION);
			String after = table.describeRegions(List.of("west", "east"));
			assertTrue(after.contains("[verified]"), after);
		}

		@Test
		@DisplayName("a region the proxy has no server for is flagged")
		void unregisteredRegionIsFlagged() {
			String output = table.describeRegions(List.of("west"));
			assertTrue(output.contains("[not registered with the proxy]"), output);
		}

		@Test
		@DisplayName("the backend listing explains that nothing has been heard yet")
		void emptyBackendListing() {
			assertTrue(table.describeBackends().contains("No backend has announced itself"), table.describeBackends());
		}

		@Test
		@DisplayName("the backend listing shows a rejection reason, not just a verdict")
		void rejectedBackendListing() {
			hello("west", "west", "world", 999L, west(), MINECRAFT_VERSION);
			String output = table.describeBackends();
			assertTrue(output.contains("REJECTED"), output);
			assertTrue(output.contains("seed"), output);
		}

		@Test
		@DisplayName("the same configuration produces the same table, and tables are independent")
		void tablesAreIndependent() {
			ProxyRoutingTable other = new ProxyRoutingTable(config);
			hello("west", "west", "world", 1234L, west(), MINECRAFT_VERSION);
			assertTrue(table.mayReceivePlayers("west"));
			assertFalse(other.mayReceivePlayers("west"), "a second table must not inherit the first one's state");
			assertNotSame(table.owner(0, 0), other.owner(0, 0).orElseThrow());
			assertEquals(table.owner(0, 0).orElseThrow().id(), other.owner(0, 0).orElseThrow().id());
		}
	}
}

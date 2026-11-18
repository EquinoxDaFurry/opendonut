package net.opendonut.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import net.opendonut.common.model.BorderParticleSettings;
import net.opendonut.common.model.OpenDonutConfig;
import net.opendonut.common.model.PlayerDataSettings;
import net.opendonut.common.model.TimeSettings;
import net.opendonut.common.model.TransferFailurePolicy;
import net.opendonut.common.model.UnknownFieldPolicy;
import net.opendonut.common.model.UnassignedAreaPolicy;

/**
 * YAML parsing: the document structure, defaults, and the diagnostics for a document that is wrong.
 *
 * <p>The assertions are mostly about the <em>findings</em> rather than about a thrown exception, because
 * that is the contract: a broken configuration produces a report an administrator can act on, and an
 * unusable document returns an empty model instead of throwing.
 */
class ConfigLoaderTest {

	/**
	 * A minimal, valid document used as the base for the negative tests.
	 */
	private static final String VALID = """
			config-version: 1
			world:
			  name: "world"
			  seed: 12345
			  border:
			    center-x: 0
			    center-z: 0
			    size: 200000
			servers:
			  - id: west
			    address: "127.0.0.1:25566"
			    bounds:
			      min-x: -100000
			      max-x: 0
			      min-z: -100000
			      max-z: 100000
			  - id: east
			    address: "127.0.0.1:25567"
			    bounds:
			      min-x: 0
			      max-x: 100000
			      min-z: -100000
			      max-z: 100000
			""";

	private static ConfigLoadResult load(String yaml) {
		return ConfigLoader.loadFromString(yaml, "test.yml");
	}

	@Nested
	@DisplayName("a valid document")
	class Valid {

		@Test
		@DisplayName("parses into a usable configuration")
		void parses() {
			ConfigLoadResult result = load(VALID);
			assertTrue(result.isUsable(), () -> "expected a usable configuration but got:\n"
					+ result.result().formatWithSummary());
			OpenDonutConfig config = result.config().orElseThrow();
			assertEquals("world", config.world().name());
			assertEquals(12345L, config.world().seed().value());
			assertEquals(2, config.servers().size());
			assertEquals("west", config.servers().get(0).id());
			assertEquals(25566, config.servers().get(0).address().port());
			assertEquals(-100000, config.servers().get(0).bounds().minX());
		}

		@Test
		@DisplayName("applies documented defaults for absent optional sections")
		void defaults() {
			OpenDonutConfig config = load(VALID).config().orElseThrow();
			assertEquals(UnknownFieldPolicy.WARN, config.validation().unknownFields());
			assertFalse(config.validation().requireFullCoverage());
			assertTrue(config.validation().strictChunkAlignment());
			assertTrue(config.bridge().enabled());
			assertEquals("opendonut:bridge", config.bridge().channel());
			assertEquals(30, config.bridge().timestampToleranceSeconds());
			assertTrue(config.proxy().autoRegisterBackends());
			assertTrue(config.proxy().requireBackendHello());
			assertEquals(2, config.transfer().boundaryMarginBlocks());
			assertEquals(40, config.transfer().joinGraceTicks());
			assertEquals(TransferFailurePolicy.REVERT, config.transfer().onTransferFailure());
			assertTrue(config.proxy().rememberLastServer(), "login routing remembers the last server by default");
			assertTrue(config.containment().enabled(), "containment is a safety invariant and on by default");
			assertEquals(2, config.containment().maxOvershootBlocks());
			assertFalse(config.chat().enabled(), "chat relay is opt-in like the other cross-server features");
			assertTrue(config.playerData().syncAdvancements());
			assertFalse(config.playerData().enabled());
			assertEquals(UnassignedAreaPolicy.ALLOW, config.transfer().unassignedAreaPolicy());
			assertTrue(config.backend().autoDetect(), "no backend.server-id means auto-detection");
		}

		@Test
		@DisplayName("reads the optional sections when they are present")
		void optionalSections() {
			String yaml = VALID + """
					validation:
					  require-full-coverage: true
					  unknown-fields: error
					bridge:
					  enabled: false
					  channel: "example:chan"
					  secret-file: "/etc/opendonut/secret"
					  timestamp-tolerance-seconds: 60
					proxy:
					  auto-register-backends: false
					  require-backend-hello: false
					  disconnect-unrouted-players: true
					  remember-last-server: false
					transfer:
					  boundary-margin-blocks: 8
					  join-grace-ticks: 0
					  cooldown-seconds: 1
					  max-pending-seconds: 2
					  on-transfer-failure: log-only
					  unassigned-area-policy: return
					containment:
					  max-overshoot-blocks: 8
					chat:
					  enabled: true
					backend:
					  server-id: east
					""";
			ConfigLoadResult result = load(yaml);
			assertTrue(result.isUsable(), () -> result.result().formatWithSummary());
			OpenDonutConfig config = result.config().orElseThrow();

			assertTrue(config.validation().requireFullCoverage());
			assertEquals(UnknownFieldPolicy.ERROR, config.validation().unknownFields());
			assertFalse(config.bridge().enabled());
			assertEquals("example", config.bridge().channelNamespace());
			assertEquals("chan", config.bridge().channelPath());
			assertEquals(60, config.bridge().timestampToleranceSeconds());
			assertFalse(config.proxy().autoRegisterBackends());
			assertTrue(config.proxy().disconnectUnroutedPlayers());
			assertEquals(8, config.transfer().boundaryMarginBlocks());
			assertEquals(TransferFailurePolicy.LOG_ONLY, config.transfer().onTransferFailure());
			assertEquals(UnassignedAreaPolicy.RETURN, config.transfer().unassignedAreaPolicy());
			assertFalse(config.proxy().rememberLastServer());
			assertEquals(8, config.containment().maxOvershootBlocks());
			assertTrue(config.containment().enabled(), "containment is on unless explicitly disabled");
			assertTrue(config.chat().enabled());
			assertEquals("east", config.backend().serverId().orElseThrow());
		}

		@Test
		@DisplayName("border particles are on by default, with the documented wall")
		void borderParticleDefaults() {
			BorderParticleSettings particles = load(VALID).config().orElseThrow().borderParticles();
			assertTrue(particles.enabled(), "the wall is on unless it is turned off");
			assertEquals(BorderParticleSettings.DEFAULT_PARTICLE, particles.particle());
			assertEquals(9, particles.radius(), "a nine block sphere is the documented default");
			assertEquals(4, particles.intervalTicks());
			assertEquals(256, particles.maxPerSide());
			assertEquals("minecraft:end_rod", particles.qualifiedParticleName(),
					"a bare name is namespaced when it is resolved against the registry");
		}

		@Test
		@DisplayName("accepts 'random' as a seed and hexadecimal seeds")
		void seedForms() {
			assertFalse(load(VALID.replace("seed: 12345", "seed: random")).config().orElseThrow()
					.world().seed().explicit());
			assertEquals(255L, load(VALID.replace("seed: 12345", "seed: 0xFF")).config().orElseThrow()
					.world().seed().value());
			assertEquals(-5L, load(VALID.replace("seed: 12345", "seed: -5")).config().orElseThrow()
					.world().seed().value());
		}

		@Test
		@DisplayName("a missing config-version is accepted and defaults to the current schema")
		void versionIsOptional() {
			assertTrue(load(VALID.replace("config-version: 1", "")).isUsable());
		}
	}

	@Nested
	@DisplayName("the player-data section")
	class PlayerData {

		@Test
		@DisplayName("is absent by default: disabled, sqlite, session scope, opt-in")
		void absentMeansDisabled() {
			OpenDonutConfig config = load(VALID).config().orElseThrow();
			PlayerDataSettings settings = config.playerData();
			assertFalse(settings.enabled(), "player-data must be opt-in, so an old config keeps its old behaviour");
			assertEquals(PlayerDataSettings.STORAGE_SQLITE, settings.storage());
			assertEquals(PlayerDataSettings.SyncScope.SESSION, settings.scope());
			assertEquals(PlayerDataSettings.OnFlushFailure.REFUSE_TRANSFER, settings.onFlushFailure());
			assertFalse(settings.mayProceedWithoutFreshData());
		}

		@Test
		@DisplayName("every documented key is read")
		void allKeys() {
			String yaml = VALID + """
					player-data:
					  enabled: true
					  storage: sqlite
					  file: "my-data.db"
					  sync-interval-seconds: 30
					  lease-seconds: 45
					  on-flush-failure: proceed-with-last-known
					  scope: session
					  keep-history: 5
					  max-payload-bytes: 32768
					  flush-timeout-seconds: 7
					""";
			ConfigLoadResult result = load(yaml);
			assertTrue(result.isUsable(), () -> result.result().formatWithSummary());
			PlayerDataSettings settings = result.config().orElseThrow().playerData();
			assertTrue(settings.enabled());
			assertEquals("sqlite", settings.storage());
			assertEquals("my-data.db", settings.file());
			assertEquals(30, settings.syncIntervalSeconds());
			assertEquals(30_000L, settings.syncIntervalMillis());
			assertEquals(45, settings.leaseSeconds());
			assertEquals(45_000L, settings.leaseMillis());
			assertEquals(PlayerDataSettings.OnFlushFailure.PROCEED_WITH_LAST_KNOWN, settings.onFlushFailure());
			assertTrue(settings.mayProceedWithoutFreshData());
			assertEquals(PlayerDataSettings.SyncScope.SESSION, settings.scope());
			assertEquals(5, settings.keepHistory());
			assertEquals(32768, settings.maxPayloadBytes());
			assertEquals(7, settings.flushTimeoutSeconds());
			assertEquals(7_000L, settings.flushTimeoutMillis());
		}

		@Test
		@DisplayName("enabled: true with nothing else uses every default")
		void enabledOnly() {
			OpenDonutConfig config = load(VALID + """
					player-data:
					  enabled: true
					""").config().orElseThrow();
			PlayerDataSettings settings = config.playerData();
			assertTrue(settings.enabled());
			assertEquals(PlayerDataSettings.DEFAULT_FILE, settings.file());
			assertEquals(60, settings.syncIntervalSeconds());
			assertEquals(10, settings.flushTimeoutSeconds());
		}

		@Test
		@DisplayName("an unimplemented storage is a reported error, and the document is unusable")
		void unimplementedStorage() {
			ConfigLoadResult result = load(VALID + """
					player-data:
					  enabled: true
					  storage: postgresql
					""");
			assertTrue(result.result().contains(IssueCode.INVALID_VALUE),
					() -> "expected an INVALID_VALUE finding but got:\n" + result.result().formatWithSummary());
			assertFalse(result.isUsable());
		}

		@Test
		@DisplayName("an unimplemented scope is a reported error")
		void unimplementedScope() {
			ConfigLoadResult result = load(VALID + """
					player-data:
					  enabled: true
					  scope: everything
					""");
			assertTrue(result.result().contains(IssueCode.INVALID_VALUE),
					() -> "expected an INVALID_VALUE finding but got:\n" + result.result().formatWithSummary());
			assertFalse(result.isUsable());
		}

		@Test
		@DisplayName("an out-of-range value is a reported error naming the section")
		void outOfRange() {
			ConfigLoadResult result = load(VALID + """
					player-data:
					  enabled: true
					  flush-timeout-seconds: 0
					""");
			assertTrue(result.result().contains(IssueCode.INVALID_VALUE),
					() -> result.result().formatWithSummary());
			assertTrue(result.result().formatWithSummary().contains("player-data"),
					"the finding must name the section:\n" + result.result().formatWithSummary());
		}

		@Test
		@DisplayName("an unknown key in the section is reported under the configured policy")
		void unknownKey() {
			ConfigLoadResult result = load(VALID + """
					player-data:
					  enabled: true
					  chunk-size: 4096
					""");
			// The default policy is WARN, so the document stays usable but the finding exists.
			assertTrue(result.isUsable());
			assertTrue(result.result().contains(IssueCode.UNKNOWN_FIELD),
					() -> result.result().formatWithSummary());
		}

		@Test
		@DisplayName("a bad enum spelling is a reported error, not a silent default")
		void badEnum() {
			ConfigLoadResult result = load(VALID + """
					player-data:
					  on-flush-failure: explode
					""");
			assertTrue(result.result().contains(IssueCode.INVALID_VALUE),
					() -> result.result().formatWithSummary());
		}
	}

	@Nested
	@DisplayName("the time section")
	class Time {

		@Test
		@DisplayName("is absent by default: disabled, auto authority, overworld clock, opt-in")
		void absentMeansDisabled() {
			OpenDonutConfig config = load(VALID).config().orElseThrow();
			TimeSettings settings = config.time();
			assertFalse(settings.enabled(), "time sync must be opt-in, so an old config keeps its old behaviour");
			assertTrue(settings.authorityIsAuto());
			assertEquals(TimeSettings.AUTHORITY_AUTO, settings.authority());
			assertEquals(TimeSettings.DEFAULT_CLOCK, settings.clock());
			assertEquals(5, settings.reportIntervalSeconds());
			assertEquals(30, settings.syncIntervalSeconds());
			assertEquals(20, settings.logDivergenceTicks());
		}

		@Test
		@DisplayName("every documented key is read")
		void allKeys() {
			String yaml = VALID + """
					time:
					  enabled: true
					  authority: west
					  clock: minecraft:the_end
					  report-interval-seconds: 2
					  sync-interval-seconds: 0
					  log-divergence-ticks: 100
					""";
			ConfigLoadResult result = load(yaml);
			assertTrue(result.isUsable(), () -> result.result().formatWithSummary());
			TimeSettings settings = result.config().orElseThrow().time();
			assertTrue(settings.enabled());
			assertFalse(settings.authorityIsAuto());
			assertEquals("west", settings.explicitAuthority());
			assertEquals("minecraft:the_end", settings.clockId());
			assertEquals(2, settings.reportIntervalSeconds());
			assertEquals(2_000L, settings.reportIntervalMillis());
			assertEquals(0, settings.syncIntervalSeconds());
			assertEquals(0L, settings.syncIntervalMillis());
			assertEquals(100, settings.logDivergenceTicks());
		}

		@Test
		@DisplayName("enabled: true with nothing else uses every default, and an explicit authority that exists passes")
		void enabledOnly() {
			OpenDonutConfig config = load(VALID + """
					time:
					  enabled: true
					  authority: east
					""").config().orElseThrow();
			TimeSettings settings = config.time();
			assertTrue(settings.enabled());
			assertEquals("east", settings.explicitAuthority());
			assertEquals(TimeSettings.DEFAULT_CLOCK, settings.clock());
		}

		@Test
		@DisplayName("a containment overshoot below the boundary margin is a CONTAINMENT_OVERSHOOT_BELOW_MARGIN error")
		void containmentOvershootBelowMargin() {
			ConfigLoadResult result = load(VALID + """
					transfer:
					  boundary-margin-blocks: 8
					containment:
					  max-overshoot-blocks: 2
					""");
			assertTrue(result.result().contains(IssueCode.CONTAINMENT_OVERSHOOT_BELOW_MARGIN),
					() -> "expected a CONTAINMENT_OVERSHOOT_BELOW_MARGIN finding but got:\n"
							+ result.result().formatWithSummary());
			assertFalse(result.isUsable());
		}

		@Test
		@DisplayName("containment keys are read when present and default when absent")
		void containmentSection() {
			OpenDonutConfig defaults = load(VALID).config().orElseThrow();
			assertTrue(defaults.containment().enabled());
			assertEquals(2, defaults.containment().maxOvershootBlocks());
			assertEquals(48, defaults.containment().borderProximityBlocks());
			assertEquals(3, defaults.containment().nearBorderChunkRadius());
			assertEquals(20, defaults.containment().entitySweepIntervalTicks());
			assertEquals(64, defaults.containment().entitySweepShellBlocks());

			OpenDonutConfig custom = load(VALID + """
					containment:
					  enabled: false
					  max-overshoot-blocks: 5
					  near-border-chunk-radius: 2
					chat:
					  enabled: true
					""").config().orElseThrow();
			assertFalse(custom.containment().enabled());
			assertEquals(5, custom.containment().maxOvershootBlocks());
			assertEquals(2, custom.containment().nearBorderChunkRadius());
			assertTrue(custom.chat().enabled());
		}

		@Test
		@DisplayName("an explicit authority that names no server is a TIME_AUTHORITY_UNKNOWN error")
		void unknownAuthority() {
			ConfigLoadResult result = load(VALID + """
					time:
					  enabled: true
					  authority: north
					""");
			assertTrue(result.result().contains(IssueCode.TIME_AUTHORITY_UNKNOWN),
					() -> "expected a TIME_AUTHORITY_UNKNOWN finding but got:\n"
							+ result.result().formatWithSummary());
			assertFalse(result.isUsable());
		}

		@Test
		@DisplayName("an invalid clock id is a reported error naming the section")
		void badClock() {
			ConfigLoadResult result = load(VALID + """
					time:
					  enabled: true
					  clock: "Not A Clock!!"
					""");
			assertTrue(result.result().contains(IssueCode.INVALID_VALUE),
					() -> result.result().formatWithSummary());
			assertTrue(result.result().formatWithSummary().contains("time"),
					"the finding must name the section:\n" + result.result().formatWithSummary());
		}

		@Test
		@DisplayName("out-of-range intervals are reported errors, and 0 is legal only for the push interval")
		void outOfRange() {
			ConfigLoadResult noReport = load(VALID + """
					time:
					  enabled: true
					  report-interval-seconds: 0
					""");
			assertTrue(noReport.result().contains(IssueCode.INVALID_VALUE),
					() -> noReport.result().formatWithSummary());

			ConfigLoadResult negativePush = load(VALID + """
					time:
					  enabled: true
					  sync-interval-seconds: -1
					""");
			assertTrue(negativePush.result().contains(IssueCode.INVALID_VALUE),
					() -> negativePush.result().formatWithSummary());

			ConfigLoadResult zeroPush = load(VALID + """
					time:
					  enabled: true
					  sync-interval-seconds: 0
					""");
			assertTrue(zeroPush.isUsable(), () -> zeroPush.result().formatWithSummary());
			assertEquals(0, zeroPush.config().orElseThrow().time().syncIntervalSeconds());
		}

		@Test
		@DisplayName("an unknown key in the section is reported under the configured policy")
		void unknownKey() {
			ConfigLoadResult result = load(VALID + """
					time:
					  enabled: true
					  timezone: utc
					""");
			assertTrue(result.isUsable());
			assertTrue(result.result().contains(IssueCode.UNKNOWN_FIELD),
					() -> result.result().formatWithSummary());
		}
	}

	@Nested
	@DisplayName("a broken document")
	class Broken {

		@Test
		@DisplayName("an empty document is reported, not thrown")
		void emptyDocument() {
			ConfigLoadResult result = load("   \n# only a comment\n");
			assertTrue(result.result().contains(IssueCode.FILE_EMPTY));
			assertTrue(result.config().isEmpty());
			assertFalse(result.isUsable());
		}

		@Test
		@DisplayName("a YAML syntax error names the line")
		void syntaxError() {
			// A tab used for indentation is a classic YAML mistake and is rejected by the parser.
			ConfigLoadResult result = load("world:\n\tname: broken\n");
			assertTrue(result.result().contains(IssueCode.YAML_INVALID),
					() -> result.result().formatWithSummary());
			assertTrue(result.result().errors().get(0).line() > 0, "the finding must carry a line number");
		}

		@Test
		@DisplayName("a duplicate key is reported with its path")
		void duplicateKey() {
			ConfigLoadResult result = load(VALID.replace("  seed: 12345", "  seed: 12345\n  seed: 999"));
			assertTrue(result.result().contains(IssueCode.YAML_DUPLICATE_KEY),
					() -> result.result().formatWithSummary());
		}

		@Test
		@DisplayName("a missing world section is fatal and specific")
		void missingWorld() {
			ConfigLoadResult result = load(VALID.replaceAll("(?m)^world:.*(\\R  .*)*", ""));
			assertTrue(result.result().contains(IssueCode.MISSING_FIELD));
			assertTrue(result.config().isEmpty());
		}

		@Test
		@DisplayName("a missing required value names the key")
		void missingValue() {
			ConfigLoadResult result = load(VALID.replace("    size: 200000", ""));
			assertTrue(result.result().contains(IssueCode.MISSING_FIELD),
					() -> result.result().formatWithSummary());
			ValidationIssue issue = result.result().errors().stream()
					.filter(candidate -> candidate.code() == IssueCode.MISSING_FIELD)
					.findFirst().orElseThrow();
			assertEquals("world.border.size", issue.path());
			assertTrue(issue.line() > 0);
		}

		@Test
		@DisplayName("a value of the wrong type names the key and the value")
		void wrongType() {
			ConfigLoadResult result = load(VALID.replace("      max-x: 0", "      max-x: east"));
			assertTrue(result.result().contains(IssueCode.INVALID_TYPE),
					() -> result.result().formatWithSummary());
			ValidationIssue issue = result.result().errors().stream()
					.filter(candidate -> candidate.code() == IssueCode.INVALID_TYPE)
					.findFirst().orElseThrow();
			assertEquals("servers[0].bounds.max-x", issue.path());
			assertTrue(issue.message().contains("east"), "the message should quote the offending value");
		}

		@Test
		@DisplayName("an unparsable address is reported with the parser's own explanation")
		void invalidAddress() {
			ConfigLoadResult result = load(VALID.replace("\"127.0.0.1:25566\"", "\"127.0.0.1\""));
			assertTrue(result.result().contains(IssueCode.ADDRESS_INVALID),
					() -> result.result().formatWithSummary());
			ValidationIssue issue = result.result().errors().stream()
					.filter(candidate -> candidate.code() == IssueCode.ADDRESS_INVALID)
					.findFirst().orElseThrow();
			assertTrue(issue.message().contains("port"), issue.message());
		}

		@Test
		@DisplayName("an unusable server id is rejected")
		void invalidServerId() {
			ConfigLoadResult result = load(VALID.replace("  - id: west", "  - id: \"has space\""));
			assertTrue(result.result().contains(IssueCode.SERVER_ID_INVALID),
					() -> result.result().formatWithSummary());
		}

		@Test
		@DisplayName("an inverted region is rejected with an explanation")
		void invertedBounds() {
			ConfigLoadResult result = load(VALID.replace("      min-x: -100000", "      min-x: 50000"));
			assertTrue(result.result().contains(IssueCode.BOUNDS_INVALID),
					() -> result.result().formatWithSummary());
		}

		@Test
		@DisplayName("a future config-version is refused rather than misread")
		void futureVersion() {
			ConfigLoadResult result = load(VALID.replace("config-version: 1", "config-version: 99"));
			assertTrue(result.result().contains(IssueCode.UNSUPPORTED_CONFIG_VERSION),
					() -> result.result().formatWithSummary());
			assertTrue(result.config().isEmpty());
		}

		@Test
		@DisplayName("an invalid enum value lists the accepted spellings")
		void invalidEnum() {
			ConfigLoadResult result = load(VALID + "transfer:\n  on-transfer-failure: explode\n");
			assertTrue(result.result().contains(IssueCode.INVALID_VALUE));
			ValidationIssue issue = result.result().errors().stream()
					.filter(candidate -> candidate.path().equals("transfer.on-transfer-failure"))
					.findFirst().orElseThrow();
			assertTrue(issue.message().contains("revert"), issue.message());
		}

		@Test
		@DisplayName("an out-of-range value is rejected by the model's own validation")
		void outOfRange() {
			ConfigLoadResult result = load(VALID + "bridge:\n  timestamp-tolerance-seconds: 100000\n");
			assertTrue(result.result().contains(IssueCode.INVALID_VALUE),
					() -> result.result().formatWithSummary());
		}

		@Test
		@DisplayName("an empty servers list is reported as a fatal finding")
		void noServers() {
			ConfigLoadResult result = load("""
					world:
					  name: world
					  seed: 1
					  border:
					    size: 16
					servers: []
					""");
			assertTrue(result.result().contains(IssueCode.NO_SERVERS),
					() -> result.result().formatWithSummary());
		}

		@Test
		@DisplayName("several problems are all reported in one pass")
		void reportsEveryProblem() {
			String yaml = VALID
					.replace("  seed: 12345", "  seed: not-a-seed")
					.replace("\"127.0.0.1:25566\"", "\"nope\"")
					.replace("      max-z: 100000\n  - id: east".replace("\n", "\n"), "      max-z: 100000\n  - id: east");
			ConfigLoadResult result = load(yaml);
			assertTrue(result.result().count(Severity.ERROR) >= 2,
					() -> "expected at least two errors, got:\n" + result.result().formatWithSummary());
		}
	}

	@Nested
	@DisplayName("border particles")
	class BorderParticles {

		@Test
		@DisplayName("reads every field, and only the fields it has")
		void readsTheSection() {
			ConfigLoadResult result = load(VALID + """
					border-particles:
					  enabled: true
					  particle: minecraft:end_rod
					  radius: 12
					  interval-ticks: 2
					  max-per-side: 512
					""");
			assertTrue(result.isUsable(), () -> result.result().formatWithSummary());
			BorderParticleSettings particles = result.config().orElseThrow().borderParticles();
			assertTrue(particles.enabled());
			assertEquals("minecraft:end_rod", particles.particle());
			assertEquals("minecraft:end_rod", particles.qualifiedParticleName());
			assertEquals(12, particles.radius());
			assertEquals(2, particles.intervalTicks());
			assertEquals(512, particles.maxPerSide());
		}

		@Test
		@DisplayName("can be switched off without supplying the rest")
		void canBeDisabled() {
			ConfigLoadResult result = load(VALID + "border-particles:\n  enabled: false\n");
			assertTrue(result.isUsable(), () -> result.result().formatWithSummary());
			BorderParticleSettings particles = result.config().orElseThrow().borderParticles();
			assertFalse(particles.enabled());
			assertEquals(9, particles.radius(), "the other values keep their defaults");
			assertTrue(particles.describe().contains("disabled"), particles.describe());
		}

		@Test
		@DisplayName("rejects a radius outside the supported range")
		void rejectsAnImpossibleRadius() {
			ConfigLoadResult result = load(VALID + "border-particles:\n  radius: 999\n");
			assertTrue(result.result().contains(IssueCode.INVALID_VALUE),
					() -> result.result().formatWithSummary());
			// The default is kept, so a typo cannot produce a wall that floods every connection.
			assertEquals(9, result.config().orElseThrow().borderParticles().radius());
		}

		@Test
		@DisplayName("rejects a particle name that is not a registry name")
		void rejectsAnImpossibleParticle() {
			ConfigLoadResult result = load(VALID + "border-particles:\n  particle: \"End Rod!\"\n");
			assertTrue(result.result().contains(IssueCode.INVALID_VALUE),
					() -> result.result().formatWithSummary());
			assertTrue(result.result().errors().stream()
							.anyMatch(issue -> issue.message().contains("registry name")),
					() -> result.result().formatWithSummary());
		}

		@Test
		@DisplayName("a mistyped key is reported like any other unknown key")
		void reportsUnknownKeys() {
			ConfigLoadResult result = load(VALID + "border-particles:\n  radius-b: 9\n");
			assertTrue(result.result().contains(IssueCode.UNKNOWN_FIELD),
					() -> result.result().formatWithSummary());
			assertTrue(result.isUsable(), "an unknown cosmetic key must not stop a server");
		}
	}

	@Nested
	@DisplayName("unknown keys")
	class UnknownKeys {

		@Test
		@DisplayName("are warnings by default, and the suggestion names the intended key")
		void warnsByDefault() {
			// A misspelled key fails in two distinct ways, and both are reported: the unrecognised name,
			// and the required value that never arrived. That pairing is what makes a typo obvious.
			ConfigLoadResult result = load(VALID.replace("      max-x: 0", "      max_x: 0"));
			assertTrue(result.result().contains(IssueCode.UNKNOWN_FIELD, Severity.WARNING),
					() -> result.result().formatWithSummary());
			ValidationIssue issue = result.result().warnings().stream()
					.filter(candidate -> candidate.code() == IssueCode.UNKNOWN_FIELD)
					.findFirst().orElseThrow();
			assertEquals("servers[0].bounds.max_x", issue.path());
			assertTrue(issue.message().contains("max-x"), "must suggest the intended key: " + issue.message());
			assertTrue(result.result().contains(IssueCode.MISSING_FIELD, Severity.ERROR),
					"the value the typo replaced is also reported as missing");
		}

		@Test
		@DisplayName("an extra key that replaces nothing is only a warning")
		void extraKeyDoesNotBreakTheConfiguration() {
			ConfigLoadResult result = load(VALID.replace("      max-x: 0",
					"      max-x: 0\n      chunk-size: 16"));
			assertTrue(result.result().contains(IssueCode.UNKNOWN_FIELD, Severity.WARNING),
					() -> result.result().formatWithSummary());
			assertTrue(result.isUsable(), () -> "an unrecognised extra key must not stop the server:\n"
					+ result.result().formatWithSummary());
		}

		@Test
		@DisplayName("can be ignored")
		void canBeIgnored() {
			ConfigLoadResult result = load(VALID + "validation:\n  unknown-fields: ignore\n");
			assertFalse(result.result().contains(IssueCode.UNKNOWN_FIELD),
					() -> result.result().formatWithSummary());
		}

		@Test
		@DisplayName("can be made fatal")
		void canBeFatal() {
			ConfigLoadResult result = load(VALID + "validation:\n  unknown-fields: error\n"
					+ "surprise: true\n");
			assertTrue(result.result().contains(IssueCode.UNKNOWN_FIELD, Severity.ERROR),
					() -> result.result().formatWithSummary());
			assertFalse(result.isUsable());
		}

		@Test
		@DisplayName("the policy itself is read before the rest of the document is checked")
		void policyAppliesToTheWholeDocument() {
			// The unknown key is in a section that is parsed before `validation` in the file, proving the
			// policy is not order dependent.
			String yaml = VALID.replace("config-version: 1", "config-version: 1\nmystery: 1")
					+ "validation:\n  unknown-fields: error\n";
			assertTrue(load(yaml).result().contains(IssueCode.UNKNOWN_FIELD, Severity.ERROR));
		}
	}

	@Nested
	@DisplayName("finding order and formatting")
	class Reporting {

		@Test
		@DisplayName("errors are listed before warnings and informational messages")
		void severityOrdering() {
			ConfigLoadResult result = load(VALID.replace("      max-x: 0", "      max_x: 0"));
			Severity first = result.result().issues().get(0).severity();
			Severity last = result.result().issues().get(result.result().issues().size() - 1).severity();
			assertEquals(Severity.INFO, last, "informational messages come last");
			assertTrue(first == Severity.ERROR || first == Severity.WARNING);
			assertEquals(Severity.INFO, result.result().infos().get(0).severity());
		}

		@Test
		@DisplayName("the same document always produces the same report")
		void deterministic() {
			String first = load(VALID).result().format();
			String second = load(VALID).result().format();
			assertEquals(first, second);
		}

		@Test
		@DisplayName("a report is human readable and carries the summary counts")
		void formatting() {
			ConfigLoadResult result = load(VALID);
			String report = result.result().formatWithSummary();
			assertTrue(report.contains("error"), report);
			assertTrue(report.contains("warning"), report);
			assertTrue(report.contains("coverage"), "the derived coverage line should be present");
		}

		@Test
		@DisplayName("requireUsable throws with the whole report attached")
		void requireUsableReportsEverything() {
			ConfigLoadResult result = load(VALID.replace("\"127.0.0.1:25566\"", "\"oops\""));
			IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
					IllegalStateException.class, result::requireUsable);
			assertTrue(e.getMessage().contains("test.yml"), e.getMessage());
			assertTrue(e.getMessage().contains("port"), e.getMessage());
		}
	}

	@Nested
	@DisplayName("file level errors")
	class FileLevel {

		@Test
		@DisplayName("a missing file is reported with advice")
		void missingFile() {
			ConfigLoadResult result = ConfigLoader.load(java.nio.file.Path.of("does-not-exist-opendonut.yml"));
			assertTrue(result.result().contains(IssueCode.FILE_NOT_FOUND));
			assertTrue(result.config().isEmpty());
			assertTrue(result.result().errors().get(0).message().contains("does not exist"));
		}

		@Test
		@DisplayName("a directory is not a configuration file")
		void directory() throws java.io.IOException {
			java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("opendonut-test");
			try {
				ConfigLoadResult result = ConfigLoader.load(directory);
				assertTrue(result.result().contains(IssueCode.FILE_UNREADABLE));
			} finally {
				java.nio.file.Files.deleteIfExists(directory);
			}
		}
	}
}

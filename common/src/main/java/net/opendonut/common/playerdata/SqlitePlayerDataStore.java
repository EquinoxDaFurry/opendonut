package net.opendonut.common.playerdata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The production {@link PlayerDataStore} for a network whose backends are all reachable through one proxy
 * machine: a single SQLite file, opened by the proxy only, so the backends never need database credentials
 * or network reachability.
 *
 * <h2>Schema</h2>
 * Four tables, mirroring the design in {@code docs/plan-player-data-sync.md}:
 * <ul>
 *   <li>{@code player_data} — the current snapshot per player, with its revision, the writing server's
 *       Minecraft data version, mod fingerprint, payload (the {@link SnapshotCodec} envelope, compressed),
 *       a SHA-256 of it, and bookkeeping columns;</li>
 *   <li>{@code player_lease} — one row per player naming the server that may write, and when that right
 *       expires;</li>
 *   <li>{@code player_data_history} — the last {@code keep-history} snapshots per player, so "my items
 *       vanished" is a question with an answer;</li>
 *   <li>{@code player_advancements} — the current advancement progress per player (the raw
 *       {@code world/advancements/<uuid>.json} bytes) with its own revision counter, written under the
 *       same lease as the snapshot.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 * One connection, and every public method is {@code synchronized}: SQLite serialises writers anyway, and a
 * single lock makes the check-then-act sequences (lease acquisition, compare-and-set save) atomic without
 * relying on database isolation levels. The traffic is a handful of operations per player per minute, so the
 * lock is never contended in practice.
 *
 * <h2>Durability</h2>
 * WAL mode plus a transaction around every multi-statement write (snapshot + history). A proxy crash can
 * lose an uncommitted write, never a committed one, which is the guarantee the transfer ordering needs.
 *
 * <p>The driver is loaded through {@code DriverManager}'s service loading, so this class has no compile-time
 * dependency on the SQLite driver: the module that actually runs the store (the Velocity plugin) must bundle
 * {@code sqlite-jdbc}, and the tests add it to their classpath.
 */
public final class SqlitePlayerDataStore implements PlayerDataStore {

	private static final String SCHEMA = """
			CREATE TABLE IF NOT EXISTS player_data (
			    player_uuid      TEXT PRIMARY KEY,
			    revision         INTEGER NOT NULL DEFAULT 0,
			    data_version     INTEGER NOT NULL,
			    mod_fingerprint  TEXT NOT NULL,
			    taken_at         INTEGER NOT NULL,
			    payload          BLOB NOT NULL,
			    payload_sha256   BLOB NOT NULL,
			    updated_at       INTEGER NOT NULL,
			    updated_by       TEXT NOT NULL
			);
			CREATE TABLE IF NOT EXISTS player_lease (
			    player_uuid   TEXT PRIMARY KEY,
			    owner_server  TEXT NOT NULL,
			    revision      INTEGER NOT NULL,
			    acquired_at   INTEGER NOT NULL,
			    expires_at    INTEGER NOT NULL
			);
			CREATE TABLE IF NOT EXISTS player_data_history (
			    id          INTEGER PRIMARY KEY AUTOINCREMENT,
			    player_uuid TEXT NOT NULL,
			    revision    INTEGER NOT NULL,
			    payload     BLOB NOT NULL,
			    written_at  INTEGER NOT NULL,
			    written_by  TEXT NOT NULL
			);
			CREATE INDEX IF NOT EXISTS player_data_history_player
			    ON player_data_history (player_uuid, revision DESC);
			CREATE TABLE IF NOT EXISTS player_advancements (
			    player_uuid  TEXT PRIMARY KEY,
			    revision     INTEGER NOT NULL DEFAULT 0,
			    payload      BLOB NOT NULL,
			    updated_at   INTEGER NOT NULL,
			    updated_by   TEXT NOT NULL
			);
			""";

	private final Connection connection;
	private final Path file;

	/**
	 * Opens (creating if necessary) the store at a file.
	 *
	 * <p>The driver is looked up with this class's own class loader, not {@link DriverManager}'s global
	 * lookup: the store runs inside a plugin whose jar bundles (and relocates) the driver, and a container's
	 * thread-context class loader usually cannot see a plugin jar's {@code META-INF/services}, which is
	 * exactly the "No suitable driver found" failure that only appears at runtime. Connecting through the
	 * driver instance directly also avoids DriverManager's caller-class permission check.
	 *
	 * @param file the database file
	 * @return the store
	 * @throws SQLException if the database cannot be opened or initialised, including when no JDBC driver
	 *                      is on this class loader
	 * @throws IOException if the parent directory cannot be created
	 */
	public static SqlitePlayerDataStore open(Path file) throws SQLException, IOException {
		Path absolute = file.toAbsolutePath();
		Path parent = absolute.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		java.sql.Driver driver = java.util.ServiceLoader.load(java.sql.Driver.class,
						SqlitePlayerDataStore.class.getClassLoader())
				.findFirst()
				.orElseThrow(() -> new SQLException("no JDBC driver is visible to " + SqlitePlayerDataStore.class
						+ "'s class loader; the component that opens the player-data store must bundle "
						+ "sqlite-jdbc on its own class path (the Velocity plugin does; the Fabric mod must "
						+ "not open the store)"));
		Connection connection = driver.connect("jdbc:sqlite:" + absolute, new java.util.Properties());
		if (connection == null) {
			throw new SQLException("the JDBC driver on this class loader does not accept jdbc:sqlite: URLs");
		}
		try (Statement statement = connection.createStatement()) {
			statement.execute("PRAGMA journal_mode=WAL");
			statement.execute("PRAGMA foreign_keys=ON");
			for (String statementSql : SCHEMA.split(";")) {
				if (!statementSql.isBlank()) {
					statement.execute(statementSql);
				}
			}
		}
		return new SqlitePlayerDataStore(connection, absolute);
	}

	private SqlitePlayerDataStore(Connection connection, Path file) {
		this.connection = connection;
		this.file = file;
	}

	@Override
	public synchronized Optional<StoredSnapshot> load(UUID playerId) {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT revision, payload, payload_sha256, updated_at, updated_by "
						+ "FROM player_data WHERE player_uuid = ?")) {
			statement.setString(1, playerId.toString());
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					return Optional.empty();
				}
				byte[] envelope = rows.getBytes("payload");
				SnapshotCodec.Result decoded = SnapshotCodec.decode(envelope);
				if (!decoded.isOk()) {
					throw new IllegalStateException("the stored snapshot for " + playerId + " does not decode ("
							+ decoded.error() + ": " + decoded.detail() + ")");
				}
				PlayerDataSnapshot snapshot = decoded.requireSnapshot();
				byte[] recordedSha = rows.getBytes("payload_sha256");
				if (!java.security.MessageDigest.isEqual(recordedSha, snapshot.sha256())) {
					throw new IllegalStateException("the stored snapshot for " + playerId
							+ " does not match its recorded SHA-256");
				}
				return Optional.of(new StoredSnapshot(
						rows.getLong("revision"), snapshot, rows.getLong("updated_at"), rows.getString("updated_by")));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("could not read the snapshot for " + playerId + ": " + e.getMessage(), e);
		}
	}

	@Override
	public synchronized SaveOutcome save(UUID playerId, long baseRevision, PlayerDataSnapshot snapshot,
			String serverId, long nowMillis, int keepHistory) {
		boolean previousAutoCommit;
		try {
			previousAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				long currentRevision;
				try (PreparedStatement statement = connection.prepareStatement(
						"SELECT revision FROM player_data WHERE player_uuid = ?")) {
					statement.setString(1, playerId.toString());
					try (ResultSet rows = statement.executeQuery()) {
						currentRevision = rows.next() ? rows.getLong("revision") : 0L;
					}
				}

				if (currentRevision != baseRevision) {
					connection.rollback();
					return SaveOutcome.refused(currentRevision, "the store holds revision " + currentRevision
							+ " but the writer was based on " + baseRevision
							+ "; the snapshot it produced is stale and was not applied");
				}

				long newRevision = currentRevision + 1;
				byte[] envelope = SnapshotCodec.encode(snapshot);
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO player_data (player_uuid, revision, data_version, mod_fingerprint, taken_at,"
								+ " payload, payload_sha256, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
								+ " ON CONFLICT(player_uuid) DO UPDATE SET revision = excluded.revision,"
								+ " data_version = excluded.data_version, mod_fingerprint = excluded.mod_fingerprint,"
								+ " taken_at = excluded.taken_at, payload = excluded.payload,"
								+ " payload_sha256 = excluded.payload_sha256, updated_at = excluded.updated_at,"
								+ " updated_by = excluded.updated_by")) {
					statement.setString(1, playerId.toString());
					statement.setLong(2, newRevision);
					statement.setInt(3, snapshot.dataVersion());
					statement.setString(4, snapshot.modFingerprint());
					statement.setLong(5, snapshot.takenAtMillis());
					statement.setBytes(6, envelope);
					statement.setBytes(7, snapshot.sha256());
					statement.setLong(8, nowMillis);
					statement.setString(9, serverId);
					statement.executeUpdate();
				}

				if (keepHistory > 0) {
					try (PreparedStatement statement = connection.prepareStatement(
							"INSERT INTO player_data_history (player_uuid, revision, payload, written_at, written_by)"
									+ " VALUES (?, ?, ?, ?, ?)")) {
						statement.setString(1, playerId.toString());
						statement.setLong(2, newRevision);
						statement.setBytes(3, envelope);
						statement.setLong(4, nowMillis);
						statement.setString(5, serverId);
						statement.executeUpdate();
					}
					try (PreparedStatement statement = connection.prepareStatement(
							"DELETE FROM player_data_history WHERE player_uuid = ? AND revision < "
									+ "(SELECT MIN(revision) FROM (SELECT revision FROM player_data_history"
									+ " WHERE player_uuid = ? ORDER BY revision DESC LIMIT ?))")) {
						statement.setString(1, playerId.toString());
						statement.setString(2, playerId.toString());
						statement.setInt(3, keepHistory);
						statement.executeUpdate();
					}
				}

				connection.commit();
				return SaveOutcome.accepted(newRevision);
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw new IllegalStateException("could not store the snapshot for " + playerId + ": "
						+ e.getMessage(), e);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("could not configure the transaction: " + e.getMessage(), e);
		} finally {
			try {
				connection.setAutoCommit(true);
			} catch (SQLException e) {
				throw new IllegalStateException("could not restore auto-commit: " + e.getMessage(), e);
			}
		}
	}

	@Override
	public synchronized LeaseOutcome acquireLease(UUID playerId, String serverId, long nowMillis, long leaseMillis) {
		long expiresAt = nowMillis + leaseMillis;
		long revision = currentRevision(playerId);
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT owner_server, revision, expires_at FROM player_lease WHERE player_uuid = ?")) {
			statement.setString(1, playerId.toString());
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					insertLease(playerId, serverId, revision, nowMillis, expiresAt);
					return new LeaseOutcome(true, serverId, revision, false, "granted");
				}
				String owner = rows.getString("owner_server");
				long leaseRevision = rows.getLong("revision");
				long leaseExpiresAt = rows.getLong("expires_at");
				if (owner.equals(serverId)) {
					updateLease(playerId, serverId, revision, nowMillis, expiresAt);
					return new LeaseOutcome(true, serverId, revision, false, "renewed");
				}
				if (leaseExpiresAt <= nowMillis) {
					// The previous holder stopped responding. Taking over is the documented recovery path,
					// and it is reported so that a crash is visible in the log rather than silent.
					updateLease(playerId, serverId, revision, nowMillis, expiresAt);
					return new LeaseOutcome(true, serverId, revision, true,
							"taken over from '" + owner + "', whose lease expired");
				}
				return new LeaseOutcome(false, owner, leaseRevision, false,
						"'" + owner + "' holds the lease for another " + (leaseExpiresAt - nowMillis) + " ms");
			}
		} catch (SQLException e) {
			throw new IllegalStateException("could not acquire the lease for " + playerId + ": " + e.getMessage(), e);
		}
	}

	@Override
	public synchronized Optional<LeaseState> lease(UUID playerId) {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT owner_server, revision, expires_at FROM player_lease WHERE player_uuid = ?")) {
			statement.setString(1, playerId.toString());
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					return Optional.empty();
				}
				return Optional.of(new LeaseState(
						rows.getString("owner_server"), rows.getLong("revision"), rows.getLong("expires_at")));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("could not read the lease for " + playerId + ": " + e.getMessage(), e);
		}
	}

	@Override
	public synchronized boolean releaseLease(UUID playerId, String serverId) {
		try (PreparedStatement statement = connection.prepareStatement(
				"DELETE FROM player_lease WHERE player_uuid = ? AND owner_server = ?")) {
			statement.setString(1, playerId.toString());
			statement.setString(2, serverId);
			return statement.executeUpdate() > 0;
		} catch (SQLException e) {
			throw new IllegalStateException("could not release the lease for " + playerId + ": " + e.getMessage(), e);
		}
	}

	@Override
	public synchronized List<StoredSnapshot> history(UUID playerId, int limit) {
		if (limit <= 0) {
			return List.of();
		}
		List<StoredSnapshot> result = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT revision, payload, written_at, written_by FROM player_data_history "
						+ "WHERE player_uuid = ? ORDER BY revision DESC LIMIT ?")) {
			statement.setString(1, playerId.toString());
			statement.setInt(2, limit);
			try (ResultSet rows = statement.executeQuery()) {
				while (rows.next()) {
					SnapshotCodec.Result decoded = SnapshotCodec.decode(rows.getBytes("payload"));
					if (!decoded.isOk()) {
						continue;
					}
					result.add(new StoredSnapshot(rows.getLong("revision"), decoded.requireSnapshot(),
							rows.getLong("written_at"), rows.getString("written_by")));
				}
			}
			return List.copyOf(result);
		} catch (SQLException e) {
			throw new IllegalStateException("could not read the history for " + playerId + ": " + e.getMessage(), e);
		}
	}

	@Override
	public synchronized Optional<StoredAdvancements> loadAdvancements(UUID playerId) {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT revision, payload, updated_at, updated_by FROM player_advancements "
						+ "WHERE player_uuid = ?")) {
			statement.setString(1, playerId.toString());
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					return Optional.empty();
				}
				return Optional.of(new StoredAdvancements(rows.getLong("revision"), rows.getBytes("payload"),
						rows.getLong("updated_at"), rows.getString("updated_by")));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("could not read the advancements for " + playerId + ": "
					+ e.getMessage(), e);
		}
	}

	@Override
	public synchronized SaveOutcome saveAdvancements(UUID playerId, long baseRevision, byte[] payload,
			String serverId, long nowMillis) {
		boolean previousAutoCommit;
		try {
			previousAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				long currentRevision;
				try (PreparedStatement statement = connection.prepareStatement(
						"SELECT revision FROM player_advancements WHERE player_uuid = ?")) {
					statement.setString(1, playerId.toString());
					try (ResultSet rows = statement.executeQuery()) {
						currentRevision = rows.next() ? rows.getLong("revision") : 0L;
					}
				}

				if (currentRevision != baseRevision) {
					connection.rollback();
					return SaveOutcome.refused(currentRevision, "the store holds revision " + currentRevision
							+ " but the writer was based on " + baseRevision
							+ "; the advancement payload it produced is stale and was not applied");
				}

				long newRevision = currentRevision + 1;
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO player_advancements (player_uuid, revision, payload, updated_at, updated_by)"
								+ " VALUES (?, ?, ?, ?, ?)"
								+ " ON CONFLICT(player_uuid) DO UPDATE SET revision = excluded.revision,"
								+ " payload = excluded.payload, updated_at = excluded.updated_at,"
								+ " updated_by = excluded.updated_by")) {
					statement.setString(1, playerId.toString());
					statement.setLong(2, newRevision);
					statement.setBytes(3, payload);
					statement.setLong(4, nowMillis);
					statement.setString(5, serverId);
					statement.executeUpdate();
				}

				connection.commit();
				return SaveOutcome.accepted(newRevision);
			} catch (SQLException | RuntimeException e) {
				connection.rollback();
				throw new IllegalStateException("could not store the advancements for " + playerId + ": "
						+ e.getMessage(), e);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("could not configure the transaction: " + e.getMessage(), e);
		} finally {
			try {
				connection.setAutoCommit(true);
			} catch (SQLException e) {
				throw new IllegalStateException("could not restore auto-commit: " + e.getMessage(), e);
			}
		}
	}

	@Override
	public synchronized String describe() {
		int snapshots = 0;
		int leases = 0;
		try (Statement statement = connection.createStatement()) {
			try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM player_data")) {
				snapshots = rows.next() ? rows.getInt(1) : 0;
			}
			try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM player_lease")) {
				leases = rows.next() ? rows.getInt(1) : 0;
			}
		} catch (SQLException e) {
			return "sqlite store at " + file + " (unreadable: " + e.getMessage() + ")";
		}
		return "sqlite store at " + file + ": " + snapshots + " snapshot(s), " + leases + " lease(s)";
	}

	@Override
	public synchronized void close() {
		try {
			connection.close();
		} catch (SQLException e) {
			throw new IllegalStateException("could not close the player-data store: " + e.getMessage(), e);
		}
	}

	/**
	 * The current revision of a player's snapshot.
	 *
	 * @param playerId the player
	 * @return the revision, 0 when none exists
	 */
	private long currentRevision(UUID playerId) {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT revision FROM player_data WHERE player_uuid = ?")) {
			statement.setString(1, playerId.toString());
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? rows.getLong("revision") : 0L;
			}
		} catch (SQLException e) {
			throw new IllegalStateException("could not read the revision for " + playerId + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Inserts a fresh lease row.
	 *
	 * @param playerId the player
	 * @param serverId the owner
	 * @param revision the revision recorded with the lease
	 * @param acquiredAt when it was acquired
	 * @param expiresAt when it expires
	 * @throws SQLException if the insert fails
	 */
	private void insertLease(UUID playerId, String serverId, long revision, long acquiredAt, long expiresAt)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO player_lease (player_uuid, owner_server, revision, acquired_at, expires_at) "
						+ "VALUES (?, ?, ?, ?, ?)")) {
			statement.setString(1, playerId.toString());
			statement.setString(2, serverId);
			statement.setLong(3, revision);
			statement.setLong(4, acquiredAt);
			statement.setLong(5, expiresAt);
			statement.executeUpdate();
		}
	}

	/**
	 * Replaces an existing lease row.
	 *
	 * @param playerId the player
	 * @param serverId the owner
	 * @param revision the revision recorded with the lease
	 * @param acquiredAt when it was acquired
	 * @param expiresAt when it expires
	 * @throws SQLException if the update fails
	 */
	private void updateLease(UUID playerId, String serverId, long revision, long acquiredAt, long expiresAt)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"UPDATE player_lease SET owner_server = ?, revision = ?, acquired_at = ?, expires_at = ? "
						+ "WHERE player_uuid = ?")) {
			statement.setString(1, serverId);
			statement.setLong(2, revision);
			statement.setLong(3, acquiredAt);
			statement.setLong(4, expiresAt);
			statement.setString(5, playerId.toString());
			statement.executeUpdate();
		}
	}
}

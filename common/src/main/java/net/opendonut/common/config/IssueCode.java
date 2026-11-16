package net.opendonut.common.config;

/**
 * Machine readable identifiers for configuration findings.
 *
 * <p>Stable codes let a deployment script assert on a specific problem — "fail the rollout if
 * {@code REGION_OVERLAP} appears" — without parsing English, and let the documentation link to one
 * anchor per problem. Codes are part of the public interface of OpenDonut and are not renamed
 * without a deprecation.
 */
public enum IssueCode {

	// --- file level ---------------------------------------------------------
	/** The configuration file does not exist. */
	FILE_NOT_FOUND(Severity.ERROR),
	/** The file could not be read, for example because of permissions. */
	FILE_UNREADABLE(Severity.ERROR),
	/** The file is empty or contains only comments. */
	FILE_EMPTY(Severity.ERROR),
	/** The YAML is syntactically invalid. */
	YAML_INVALID(Severity.ERROR),
	/** A YAML mapping contains the same key twice. */
	YAML_DUPLICATE_KEY(Severity.ERROR),
	/** A key is not part of the OpenDonut schema. */
	UNKNOWN_FIELD(Severity.WARNING),
	/** A required key is absent. */
	MISSING_FIELD(Severity.ERROR),
	/** A value has the wrong YAML type, for example a string where a number is required. */
	INVALID_TYPE(Severity.ERROR),
	/** A value has the right type but an illegal value. */
	INVALID_VALUE(Severity.ERROR),
	/** The configuration version declares a schema this build does not understand. */
	UNSUPPORTED_CONFIG_VERSION(Severity.ERROR),

	// --- world --------------------------------------------------------------
	/** The world name is missing or unusable. */
	WORLD_NAME_INVALID(Severity.ERROR),
	/** The seed value is neither a number nor {@code random}. */
	SEED_INVALID(Severity.ERROR),
	/** The world border size is not a positive multiple of 16 blocks. */
	BORDER_INVALID(Severity.ERROR),
	/** The world border centre does not fall on a chunk boundary. */
	BORDER_NOT_CHUNK_ALIGNED(Severity.ERROR),

	// --- servers ------------------------------------------------------------
	/** No servers are configured at all. */
	NO_SERVERS(Severity.ERROR),
	/** Two servers use the same id. */
	DUPLICATE_SERVER_ID(Severity.ERROR),
	/** Two servers are configured on the same address. */
	DUPLICATE_SERVER_ADDRESS(Severity.ERROR),
	/** A server id is missing or contains unsupported characters. */
	SERVER_ID_INVALID(Severity.ERROR),
	/** An address is missing or cannot be parsed. */
	ADDRESS_INVALID(Severity.ERROR),
	/** A region's minimum is not below its maximum. */
	BOUNDS_INVALID(Severity.ERROR),
	/** A region's edges do not line up with chunk boundaries. */
	BOUNDS_NOT_CHUNK_ALIGNED(Severity.ERROR),
	/** A region reaches outside the world border. */
	REGION_OUTSIDE_BORDER(Severity.ERROR),
	/** Two regions claim the same block. */
	REGION_OVERLAP(Severity.ERROR),
	/** Part of the world border is not owned by any server. */
	WORLD_UNCOVERED(Severity.WARNING),
	/** A server's configured region differs from what the backend reported at startup. */
	REGION_MISMATCH_WITH_BACKEND(Severity.ERROR),

	// --- backend identity ---------------------------------------------------
	/** This instance could not work out which server entry describes it. */
	BACKEND_IDENTITY_UNRESOLVED(Severity.ERROR),
	/** More than one entry could describe this instance. */
	BACKEND_IDENTITY_AMBIGUOUS(Severity.ERROR),

	// --- bridge and security ------------------------------------------------
	/** The shared secret file is missing. */
	BRIDGE_SECRET_MISSING(Severity.ERROR),
	/** The shared secret file exists but is unusable. */
	BRIDGE_SECRET_INVALID(Severity.ERROR),
	/** The shared secret file permissions are too permissive. */
	BRIDGE_SECRET_WORLD_READABLE(Severity.WARNING),
	/** The bridge is disabled, so players cannot be transferred between servers. */
	BRIDGE_DISABLED(Severity.WARNING),
	/** A bridge message failed authentication. */
	BRIDGE_AUTHENTICATION_FAILED(Severity.ERROR),

	// --- world consistency --------------------------------------------------
	/** The backend's world seed differs from the centrally configured seed. */
	SEED_MISMATCH(Severity.ERROR),
	/** A generated seed was persisted for the first time. */
	SEED_GENERATED(Severity.INFO),
	/** An explicitly configured seed differs from the persisted seed. */
	SEED_STATE_MISMATCH(Severity.WARNING),
	/** The backend's level name differs from the configured world name. */
	WORLD_NAME_MISMATCH(Severity.ERROR),
	/** The backend does not accept player transfers, so proxy transfers will fail. */
	ACCEPTS_TRANSFERS_DISABLED(Severity.ERROR),
	/** The backend runs a different Minecraft version from the configured one. */
	MINECRAFT_VERSION_MISMATCH(Severity.ERROR),

	// --- informational ------------------------------------------------------
	/** Derived values worth showing, such as route lookup results. */
	DERIVED_VALUE(Severity.INFO);

	private final Severity defaultSeverity;

	IssueCode(Severity defaultSeverity) {
		this.defaultSeverity = defaultSeverity;
	}

	/**
	 * The severity this code normally carries.
	 *
	 * <p>Some codes can be raised or lowered by configuration options. For example
	 * {@link #WORLD_UNCOVERED} is a warning by default and an error when
	 * {@code validation.require-full-coverage} is {@code true}, and
	 * {@link #BOUNDS_NOT_CHUNK_ALIGNED} is an error unless
	 * {@code validation.strict-chunk-alignment} is {@code false}.
	 *
	 * @return the default severity
	 */
	public Severity defaultSeverity() {
		return defaultSeverity;
	}
}

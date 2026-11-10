package dev.sentinel.data;

import org.sqlite.SQLiteDataSource;

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
 * Account links and active bans, stored in an SQLite database (config/sentinel/sentinel.db).
 * Used from several threads over one connection, so every method is synchronized.
 * The server thread must not call it directly; see {@link dev.sentinel.Sentinel#database}.
 */
public final class SentinelData implements AutoCloseable {
	/** One Discord user linked to one Minecraft account. */
	public record Link(String discordId, UUID uuid, String name) {}

	/**
	 * A ban made with /sban. {@code discordId} is null when the player has no linked account.
	 * {@code roleId} is the banned role configured when the ban was made, null if there was none
	 * or the ban is older than this column. {@code createdAt} tells bans of the same player apart.
	 * {@code lifted} is true once the Minecraft ban is gone and only the Discord role still has to be removed.
	 */
	public record Ban(UUID uuid, String name, String discordId, String roleId, long createdAt, long expiresAt,
					  String reason, String staff, boolean lifted) {}

	public enum LinkResult { LINKED, DISCORD_ALREADY_LINKED, ACCOUNT_TAKEN }

	private static final int SCHEMA_VERSION = 2;

	private final Path file;
	private Connection connection;

	public SentinelData(Path file) {
		this.file = file;
	}

	public synchronized void open() throws IOException, SQLException {
		Files.createDirectories(file.getParent());
		SQLiteDataSource source = new SQLiteDataSource();
		source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
		connection = source.getConnection();
		try (Statement st = connection.createStatement()) {
			st.execute("PRAGMA journal_mode = WAL");
			st.execute("PRAGMA busy_timeout = 5000");
			// Migrate in one transaction, so a crash can't leave a half-upgraded schema behind.
			connection.setAutoCommit(false);
			int version;
			try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
				version = rs.next() ? rs.getInt(1) : 0;
			}
			if (version < 1) {
				st.execute("""
						CREATE TABLE IF NOT EXISTS links (
							discord_id TEXT PRIMARY KEY,
							uuid       TEXT NOT NULL UNIQUE,
							name       TEXT NOT NULL,
							linked_at  INTEGER NOT NULL
						)""");
				st.execute("""
						CREATE TABLE IF NOT EXISTS bans (
							uuid       TEXT PRIMARY KEY,
							name       TEXT NOT NULL,
							discord_id TEXT,
							created_at INTEGER NOT NULL,
							expires_at INTEGER NOT NULL,
							reason     TEXT NOT NULL,
							staff      TEXT NOT NULL,
							lifted     INTEGER NOT NULL DEFAULT 0
						)""");
			}
			if (version < 2) st.execute("ALTER TABLE bans ADD COLUMN banned_role_id TEXT");
			st.execute("PRAGMA user_version = " + SCHEMA_VERSION);
			connection.commit();
			connection.setAutoCommit(true);
		}
	}

	@Override
	public synchronized void close() {
		if (connection == null) return;
		try {
			connection.close();
		} catch (SQLException ignored) {
		}
		connection = null;
	}

	// Links

	public synchronized Optional<Link> linkByDiscord(String discordId) {
		return queryLink("SELECT discord_id, uuid, name FROM links WHERE discord_id = ?", discordId);
	}

	public synchronized Optional<Link> linkByUuid(UUID uuid) {
		return queryLink("SELECT discord_id, uuid, name FROM links WHERE uuid = ?", uuid.toString());
	}

	/** Links the accounts unless either side is already linked. */
	public synchronized LinkResult tryLink(String discordId, UUID uuid, String name) {
		if (linkByDiscord(discordId).isPresent()) return LinkResult.DISCORD_ALREADY_LINKED;
		if (linkByUuid(uuid).isPresent()) return LinkResult.ACCOUNT_TAKEN;
		update("INSERT INTO links (discord_id, uuid, name, linked_at) VALUES (?, ?, ?, ?)",
				discordId, uuid.toString(), name, System.currentTimeMillis());
		return LinkResult.LINKED;
	}

	public synchronized void unlink(String discordId) {
		update("DELETE FROM links WHERE discord_id = ?", discordId);
	}

	/** Keeps the stored name current after a player renames their account. */
	public synchronized void updateName(UUID uuid, String name) {
		update("UPDATE links SET name = ? WHERE uuid = ? AND name <> ?", name, uuid.toString(), name);
		update("UPDATE bans SET name = ? WHERE uuid = ? AND name <> ?", name, uuid.toString(), name);
	}

	// Bans

	/** Stores the ban, replacing an earlier one for the same player. */
	public synchronized void putBan(Ban ban) {
		update("""
						INSERT OR REPLACE INTO bans
							(uuid, name, discord_id, banned_role_id, created_at, expires_at, reason, staff, lifted)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				ban.uuid().toString(), ban.name(), ban.discordId(), ban.roleId(), ban.createdAt(), ban.expiresAt(),
				ban.reason(), ban.staff(), ban.lifted() ? 1 : 0);
	}

	public synchronized List<Ban> bans() {
		List<Ban> bans = new ArrayList<>();
		try (PreparedStatement ps = connection().prepareStatement("""
				SELECT uuid, name, discord_id, banned_role_id, created_at, expires_at, reason, staff, lifted
				FROM bans""");
			 ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				bans.add(new Ban(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getInt(9) != 0));
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		return bans;
	}

	/** Marks the ban lifted. Returns false when a newer /sban has replaced it meanwhile. */
	public synchronized boolean markLifted(Ban ban) {
		return update("UPDATE bans SET lifted = 1 WHERE uuid = ? AND created_at = ?", ban.uuid().toString(), ban.createdAt());
	}

	/** Removes the ban, unless a newer /sban has replaced it meanwhile. */
	public synchronized void removeBan(Ban ban) {
		update("DELETE FROM bans WHERE uuid = ? AND created_at = ?", ban.uuid().toString(), ban.createdAt());
	}

	// Helpers

	private Connection connection() {
		if (connection == null) throw new IllegalStateException("the Sentinel database is closed");
		return connection;
	}

	private Optional<Link> queryLink(String sql, String param) {
		try (PreparedStatement ps = connection().prepareStatement(sql)) {
			ps.setString(1, param);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) return Optional.empty();
				return Optional.of(new Link(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3)));
			}
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}

	/** Returns whether any row changed. */
	private boolean update(String sql, Object... params) {
		try (PreparedStatement ps = connection().prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
			return ps.executeUpdate() > 0;
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
	}
}

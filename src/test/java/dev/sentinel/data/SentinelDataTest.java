package dev.sentinel.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentinelDataTest {
	private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@TempDir
	Path dir;

	private static SentinelData.Ban ban(long createdAt) {
		return new SentinelData.Ban(PLAYER, "Steve", "123456789012345678", "223456789012345678",
				createdAt, createdAt + 1000, "reason", "staff", false);
	}

	@Test
	void workForAReplacedBanLeavesTheNewOneAlone() throws Exception {
		try (SentinelData data = new SentinelData(dir.resolve("sentinel.db"))) {
			data.open();
			SentinelData.Ban old = ban(1_000);
			SentinelData.Ban replacement = ban(1_001);
			data.putBan(old);
			data.putBan(replacement);

			assertFalse(data.markLifted(old));
			data.removeBan(old);

			assertEquals(List.of(replacement), data.bans());
			assertTrue(data.markLifted(replacement));
			data.removeBan(replacement);
			assertEquals(List.of(), data.bans());
		}
	}

	@Test
	void upgradesVersionOneDatabase() throws Exception {
		Path file = dir.resolve("sentinel.db");
		try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
			 Statement st = connection.createStatement()) {
			st.execute("""
					CREATE TABLE bans (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, discord_id TEXT,
						created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, reason TEXT NOT NULL,
						staff TEXT NOT NULL, lifted INTEGER NOT NULL DEFAULT 0)""");
			st.execute("CREATE TABLE links (discord_id TEXT PRIMARY KEY, uuid TEXT NOT NULL UNIQUE, "
					+ "name TEXT NOT NULL, linked_at INTEGER NOT NULL)");
			st.execute("INSERT INTO bans VALUES ('" + PLAYER + "', 'Steve', NULL, 1, 2, 'r', 's', 1)");
			st.execute("PRAGMA user_version = 1");
		}

		try (SentinelData data = new SentinelData(file)) {
			data.open();
			SentinelData.Ban migrated = data.bans().getFirst();
			assertNull(migrated.roleId());
			assertTrue(migrated.lifted());
		}
		// Opening again must not re-run the migration.
		try (SentinelData data = new SentinelData(file)) {
			data.open();
			assertEquals(1, data.bans().size());
		}
	}

	@Test
	void closedDatabaseFailsClearly() throws Exception {
		SentinelData data = new SentinelData(dir.resolve("sentinel.db"));
		data.open();
		data.close();
		assertThrows(IllegalStateException.class, data::bans);
	}
}

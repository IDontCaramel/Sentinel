package dev.sentinel.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentinelConfigTest {
	@Test
	void unconfiguredDefaultsAreValid() {
		assertEquals(java.util.List.of(), new SentinelConfig().validate());
	}

	@Test
	void rejectsCheckIntervalOutOfRange() {
		for (int seconds : new int[] {-1, 0, 4, 86_401, Integer.MAX_VALUE}) {
			SentinelConfig config = new SentinelConfig();
			config.bans.expiryCheckInterval = seconds;
			assertFalse(config.validate().isEmpty(), "interval " + seconds);
		}
	}

	@Test
	void rejectsUnusableDateFormatAndTimezone() {
		SentinelConfig badFormat = new SentinelConfig();
		badFormat.bans.dateFormat = "dd-MM-yyyy {";
		assertFalse(badFormat.validate().isEmpty());

		SentinelConfig badZone = new SentinelConfig();
		badZone.bans.timezone = "Mars/Olympus";
		assertFalse(badZone.validate().isEmpty());
	}

	@Test
	void rejectsMalformedIds() {
		SentinelConfig config = new SentinelConfig();
		config.bans.bannedRoleId = "not-an-id";
		assertTrue(config.validate().getFirst().contains("bans.banned_role_id"));
	}
}

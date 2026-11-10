package dev.sentinel.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigEditorTest {
	@Test
	void replacesKeyInItsSection() {
		String toml = """
				[bans]
				channel_id = "1"

				[whitelist]
				channel_id = ""
				""";
		assertEquals("""
				[bans]
				channel_id = "1"

				[whitelist]
				channel_id = "42"
				""", ConfigEditor.set(toml, "whitelist", "channel_id", "42"));
	}

	@Test
	void findsHeaderWithSpacesAndComment() {
		String toml = """
				[ whitelist ] # players post here
				channel_id = ""
				""";
		assertEquals("""
				[ whitelist ] # players post here
				channel_id = "42"
				""", ConfigEditor.set(toml, "whitelist", "channel_id", "42"));
	}

	@Test
	void commentWithTripleQuoteDoesNotHideLaterKeys() {
		String toml = """
				[bans]
				# use \""" for messages over several lines
				log_channel_id = ""
				""";
		assertEquals("""
				[bans]
				# use \""" for messages over several lines
				log_channel_id = "42"
				""", ConfigEditor.set(toml, "bans", "log_channel_id", "42"));
	}

	@Test
	void skipsLinesInsideMultiLineStrings() {
		String toml = """
				[bans]
				log_message = \"""
				log_channel_id = "inside the message"
				[whitelist]\"""
				log_channel_id = ""
				""";
		assertEquals("""
				[bans]
				log_message = \"""
				log_channel_id = "inside the message"
				[whitelist]\"""
				log_channel_id = "42"
				""", ConfigEditor.set(toml, "bans", "log_channel_id", "42"));
	}

	@Test
	void addsMissingKeyAtEndOfSection() {
		String toml = """
				[whitelist]
				add_role_id = ""

				[bans]
				""";
		assertEquals("""
				[whitelist]
				add_role_id = ""
				channel_id = "42"

				[bans]
				""", ConfigEditor.set(toml, "whitelist", "channel_id", "42"));
	}
}

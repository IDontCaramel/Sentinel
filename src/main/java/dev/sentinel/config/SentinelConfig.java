package dev.sentinel.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Settings read from config/sentinel.toml. Fields are filled by Jackson. */
public final class SentinelConfig {
	public Discord discord = new Discord();
	public Whitelist whitelist = new Whitelist();
	public Bans bans = new Bans();
	public Permissions permissions = new Permissions();

	public static final class Discord {
		public String token = "";
		public String guildId = "";
	}

	public static final class Whitelist {
		public String channelId = "";
		public String addRoleId = "";
		public String removeRoleId = "";
		public String successEmoji = "✅";
		public String notFoundMessage = "username not found";
		public String alreadyLinkedMessage = "You already whitelisted **{linked}**. You can't whitelist another account.";
		public String accountTakenMessage = "**{username}** is already linked to another Discord account.";
		public String errorMessage = "Something went wrong while whitelisting. Please ask a staff member.";
	}

	public static final class Bans {
		public String logChannelId = "";
		public String bannedRoleId = "";
		public int maxDays = 365;
		public int expiryCheckInterval = 60;
		public String dateFormat = "dd-MM-yyyy HH:mm z";
		public String timezone = "";
		public String logMessage = "{user}\nBan ends: {ends}\nReason: {reason}";
		public String kickMessage = "You are banned from this server until {ends}.\nReason: {reason}";
	}

	public static final class Permissions {
		public int sbanLevel = 3;
		public int reloadLevel = 4;
		/** Roles allowed to use /sentinel in Discord besides members with Manage Server. */
		public List<String> discordAdminRoleIds = new ArrayList<>();
	}

	private static final int MIN_CHECK_INTERVAL = 5;
	private static final int MAX_CHECK_INTERVAL = 24 * 60 * 60;

	private static final TomlMapper MAPPER = TomlMapper.builder()
			.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.build();

	/** Reads the config, writing the commented default file first if there is none. */
	public static SentinelConfig load(Path file) throws IOException {
		if (Files.notExists(file)) {
			Files.createDirectories(file.getParent());
			try (InputStream in = SentinelConfig.class.getResourceAsStream("/sentinel-default.toml")) {
				if (in == null) throw new IOException("sentinel-default.toml missing from the mod jar");
				Files.copy(in, file);
			}
		}
		SentinelConfig config = MAPPER.readValue(file.toFile(), SentinelConfig.class);
		String envToken = System.getenv("SENTINEL_TOKEN");
		if (envToken != null && !envToken.isBlank()) config.discord.token = envToken.strip();
		return config;
	}

	/**
	 * Writes one ID into the config file, keeping its comments and layout.
	 * The caller reloads the config afterwards.
	 */
	public static void writeId(Path file, IdSetting setting, String value) throws IOException {
		String text = Files.readString(file);
		Files.writeString(file, ConfigEditor.set(text, setting.section, setting.key, value));
	}

	/**
	 * Settings that can't be used as they are. Empty when the config can be applied.
	 * An empty token or guild ID is not an error: it only turns Discord off.
	 */
	public List<String> validate() {
		List<String> problems = new ArrayList<>();
		checkId(problems, "discord.guild_id", discord.guildId, false);
		checkId(problems, "whitelist.channel_id", whitelist.channelId, false);
		checkId(problems, "whitelist.add_role_id", whitelist.addRoleId, false);
		checkId(problems, "whitelist.remove_role_id", whitelist.removeRoleId, false);
		checkId(problems, "bans.log_channel_id", bans.logChannelId, false);
		checkId(problems, "bans.banned_role_id", bans.bannedRoleId, false);
		for (String id : permissions.discordAdminRoleIds) {
			checkId(problems, "permissions.discord_admin_role_ids", id, true);
		}
		if (bans.maxDays < 1) problems.add("bans.max_days must be at least 1");
		if (bans.expiryCheckInterval < MIN_CHECK_INTERVAL || bans.expiryCheckInterval > MAX_CHECK_INTERVAL) {
			problems.add("bans.expiry_check_interval must be between " + MIN_CHECK_INTERVAL + " and "
					+ MAX_CHECK_INTERVAL + " seconds");
		}
		try {
			dateFormatter().format(Instant.now());
		} catch (RuntimeException e) {
			problems.add("bans.date_format or bans.timezone is invalid: " + e.getMessage());
		}
		if (permissions.sbanLevel < 0 || permissions.sbanLevel > 4) problems.add("permissions.sban_level must be 0-4");
		if (permissions.reloadLevel < 0 || permissions.reloadLevel > 4) problems.add("permissions.reload_level must be 0-4");
		return problems;
	}

	public ZoneId zone() {
		return bans.timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(bans.timezone);
	}

	public DateTimeFormatter dateFormatter() {
		return DateTimeFormatter.ofPattern(bans.dateFormat).withZone(zone());
	}

	private static void checkId(List<String> problems, String key, String value, boolean required) {
		if (value.isBlank()) {
			if (required) problems.add(key + " is empty");
		} else if (!value.matches("\\d{17,20}")) {
			problems.add(key + " is not a valid Discord ID: " + value);
		}
	}
}

package dev.sentinel.config;

import java.util.function.Function;

/** The channel and role IDs that can be changed from Discord with /sentinel. */
public enum IdSetting {
	WHITELIST_CHANNEL("whitelist", "channel_id", Kind.CHANNEL, "Whitelist channel", c -> c.whitelist.channelId),
	BAN_LOG_CHANNEL("bans", "log_channel_id", Kind.CHANNEL, "Ban log channel", c -> c.bans.logChannelId),
	WHITELIST_ADD_ROLE("whitelist", "add_role_id", Kind.ROLE, "Role given after whitelisting", c -> c.whitelist.addRoleId),
	WHITELIST_REMOVE_ROLE("whitelist", "remove_role_id", Kind.ROLE, "Role removed after whitelisting", c -> c.whitelist.removeRoleId),
	BANNED_ROLE("bans", "banned_role_id", Kind.ROLE, "Banned role", c -> c.bans.bannedRoleId);

	public enum Kind { CHANNEL, ROLE }

	public final String section;
	public final String key;
	public final Kind kind;
	public final String label;
	private final Function<SentinelConfig, String> getter;

	IdSetting(String section, String key, Kind kind, String label, Function<SentinelConfig, String> getter) {
		this.section = section;
		this.key = key;
		this.kind = kind;
		this.label = label;
		this.getter = getter;
	}

	public String get(SentinelConfig config) {
		return getter.apply(config);
	}

	/** The key as written in the config file, for example {@code whitelist.channel_id}. */
	public String path() {
		return section + "." + key;
	}
}

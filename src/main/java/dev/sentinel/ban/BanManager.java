package dev.sentinel.ban;

import dev.sentinel.Sentinel;
import dev.sentinel.config.SentinelConfig;
import dev.sentinel.data.SentinelData;
import dev.sentinel.discord.DiscordBot;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanList;
import net.minecraft.server.players.UserBanListEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Issues timed bans and undoes them when they run out. Call from the server thread;
 * the database work runs on Sentinel's database thread.
 */
public final class BanManager {
	private static final long DAY_MS = 24L * 60 * 60 * 1000;

	/** Players whose banned role is being removed right now, so the check doesn't send it twice. */
	private final Set<UUID> removingRole = ConcurrentHashMap.newKeySet();
	/** Set while a check runs, so a slow database doesn't pile checks up. */
	private final AtomicBoolean checking = new AtomicBoolean();
	/** Only used on the server thread. */
	private long lastCreatedAt;

	/**
	 * Bans the player for the given number of days.
	 *
	 * @param warn receives problems with saving the ban or with the Discord side, on the server thread;
	 *             the Minecraft ban itself still applies
	 */
	public void ban(MinecraftServer server, NameAndId target, int days, String reason, String staff, Consumer<String> warn) {
		SentinelConfig config = Sentinel.config();
		long now = System.currentTimeMillis();
		// Whole seconds, because banned-players.json stores dates without milliseconds
		// and the expiry check compares the stored date with ours.
		long start = now / 1000 * 1000;
		long expires = start + days * DAY_MS;
		// Unique per ban, so work left over from an earlier ban of the same player can't touch this one.
		long createdAt = lastCreatedAt = Math.max(now, lastCreatedAt + 1);
		String roleId = config.bans.bannedRoleId.isBlank() ? null : config.bans.bannedRoleId;

		server.getPlayerList().getBans().add(
				new UserBanListEntry(target, new Date(start), staff, new Date(expires), reason));

		SentinelData data = Sentinel.data();
		Sentinel.database(() -> {
			String discordId = data.linkByUuid(target.id()).map(SentinelData.Link::discordId).orElse(null);
			SentinelData.Ban ban = new SentinelData.Ban(target.id(), target.name(), discordId, roleId,
					createdAt, expires, reason, staff, false);
			data.putBan(ban);
			return ban;
		}).whenComplete((ban, error) -> Sentinel.onServerThread(() -> {
			if (error != null) {
				Sentinel.LOGGER.error("Could not save the ban of {}", target.name(), error);
				warn.accept("Saving the ban failed, so it was not posted and no role was given. See the server log.");
				kick(server, new SentinelData.Ban(target.id(), target.name(), null, null,
						createdAt, expires, reason, staff, false), days, config);
				return;
			}
			kick(server, ban, days, config);
			announce(ban, days, config, warn);
		}));
	}

	private static void kick(MinecraftServer server, SentinelData.Ban ban, int days, SentinelConfig config) {
		ServerPlayer online = server.getPlayerList().getPlayer(ban.uuid());
		if (online != null) {
			online.connection.disconnect(Component.literal(format(config.bans.kickMessage, ban, days, config)));
		}
	}

	private static void announce(SentinelData.Ban ban, int days, SentinelConfig config, Consumer<String> warn) {
		DiscordBot bot = Sentinel.bot();
		if (bot == null) {
			warn.accept("Discord is not connected, so the ban was not posted and no role was given.");
			return;
		}
		if (!config.bans.logChannelId.isBlank()) {
			bot.post(config.bans.logChannelId, format(config.bans.logMessage, ban, days, config)).exceptionally(e -> {
				Sentinel.LOGGER.error("Could not post ban of {} to Discord", ban.name(), e);
				Sentinel.onServerThread(() -> warn.accept("Posting the ban to Discord failed, see the server log."));
				return null;
			});
		}
		if (ban.discordId() == null) {
			warn.accept(ban.name() + " has no linked Discord account, so no role was given.");
		} else if (ban.roleId() != null) {
			bot.setRole(ban.discordId(), ban.roleId(), true).exceptionally(e -> {
				Sentinel.LOGGER.error("Could not give the banned role to {}", ban.name(), e);
				Sentinel.onServerThread(() -> warn.accept("Giving the banned role failed, see the server log."));
				return null;
			});
		}
	}

	/** Lifts bans that ran out or were pardoned, and takes the banned role away again. */
	public void checkExpired(MinecraftServer server) {
		if (!checking.compareAndSet(false, true)) return;
		SentinelData data = Sentinel.data();
		CompletableFuture<List<SentinelData.Ban>> lifted = new CompletableFuture<>();
		Sentinel.database(data::bans).whenComplete((bans, error) -> {
			if (error != null) {
				lifted.completeExceptionally(error);
			} else if (bans.isEmpty()) {
				lifted.complete(bans);
			} else {
				Sentinel.onServerThread(() -> {
					try {
						lifted.complete(liftMinecraftBans(server, bans));
					} catch (RuntimeException e) {
						lifted.completeExceptionally(e);
					}
				});
			}
		});
		lifted.thenCompose(bans -> Sentinel.database(() -> removeRoles(data, bans))).whenComplete((ok, error) -> {
			checking.set(false);
			if (error != null) Sentinel.LOGGER.error("Checking for expired bans failed", error);
		});
	}

	/** Runs on the server thread. Returns the bans that are no longer in force. */
	private static List<SentinelData.Ban> liftMinecraftBans(MinecraftServer server, List<SentinelData.Ban> bans) {
		UserBanList banList = server.getPlayerList().getBans();
		// get() hides expired entries, so index the raw list.
		Map<UUID, UserBanListEntry> entries = new HashMap<>();
		for (UserBanListEntry entry : banList.getEntries()) {
			if (entry.getUser() != null) entries.put(entry.getUser().id(), entry);
		}
		long now = System.currentTimeMillis();
		List<SentinelData.Ban> lifted = new ArrayList<>();
		for (SentinelData.Ban ban : bans) {
			if (ban.lifted()) {
				lifted.add(ban);
				continue;
			}
			UserBanListEntry entry = entries.get(ban.uuid());
			boolean expired = now >= ban.expiresAt();
			if (!expired && entry != null) continue;

			// Only remove the Minecraft ban if it is ours or ran out; someone may have replaced it with /ban.
			if (entry != null && (entry.hasExpired()
					|| (entry.getExpires() != null && entry.getExpires().getTime() == ban.expiresAt()))) {
				banList.remove(entry);
			}
			Sentinel.LOGGER.info("Ban of {} {}", ban.name(), expired ? "expired" : "was pardoned");
			lifted.add(ban);
		}
		return lifted;
	}

	/**
	 * Runs on the database thread. Role removals are queued from here, after the database confirms the ban
	 * still exists, so they can't be ordered after the role given for a newer ban of the same player.
	 */
	private void removeRoles(SentinelData data, List<SentinelData.Ban> lifted) {
		String configuredRole = Sentinel.config().bans.bannedRoleId;
		for (SentinelData.Ban ban : lifted) {
			if (!data.markLifted(ban)) continue;
			// Bans saved before the role was stored use the configured one.
			String roleId = ban.roleId() != null ? ban.roleId() : configuredRole;
			if (ban.discordId() == null || roleId.isBlank()) {
				data.removeBan(ban);
				continue;
			}
			DiscordBot bot = Sentinel.bot();
			// Without Discord, keep the ban marked lifted and retry the role on a later check.
			if (bot == null || !removingRole.add(ban.uuid())) continue;
			bot.setRole(ban.discordId(), roleId, false).whenComplete((ok, error) -> {
				removingRole.remove(ban.uuid());
				if (error != null) {
					Sentinel.LOGGER.warn("Could not remove the banned role from {}, will retry", ban.name(), error);
					return;
				}
				Sentinel.database(() -> data.removeBan(ban)).exceptionally(e -> {
					Sentinel.LOGGER.warn("Could not delete the lifted ban of {}, will retry", ban.name(), e);
					return null;
				});
			});
		}
	}

	private static String format(String template, SentinelData.Ban ban, int days, SentinelConfig config) {
		String player = ban.name();
		String user = ban.discordId() == null ? player : "<@" + ban.discordId() + "> | " + player;
		return template
				.replace("{user}", user)
				.replace("{player}", player)
				.replace("{ends}", config.dateFormatter().format(Instant.ofEpochMilli(ban.expiresAt())))
				.replace("{days}", Integer.toString(days))
				.replace("{reason}", ban.reason())
				.replace("{staff}", ban.staff());
	}
}

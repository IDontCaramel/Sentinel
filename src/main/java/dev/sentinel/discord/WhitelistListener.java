package dev.sentinel.discord;

import dev.sentinel.Sentinel;
import dev.sentinel.config.SentinelConfig;
import dev.sentinel.data.SentinelData;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserWhiteListEntry;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Whitelists the Minecraft account named in a message in the whitelist channel. */
public final class WhitelistListener extends ListenerAdapter {
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
	/** Requests handled at once. Each one can wait on Mojang and write the whitelist file. */
	private static final int MAX_PENDING = 4;

	private final Semaphore pending = new Semaphore(MAX_PENDING);
	/** Discord users with a request in progress. Their further messages are ignored until it is done. */
	private final Set<String> pendingUsers = ConcurrentHashMap.newKeySet();

	@Override
	public void onMessageReceived(MessageReceivedEvent event) {
		SentinelConfig config = Sentinel.config();
		if (!event.isFromGuild() || config.whitelist.channelId.isBlank()) return;
		if (!event.getChannel().getId().equals(config.whitelist.channelId)) return;
		if (event.getAuthor().isBot() || event.getAuthor().isSystem() || event.isWebhookMessage()) return;

		Message message = event.getMessage();
		String discordId = event.getAuthor().getId();
		String username = message.getContentRaw().strip();
		if (!pendingUsers.add(discordId)) return;
		if (!pending.tryAcquire()) {
			pendingUsers.remove(discordId);
			reply(message, config.whitelist.errorMessage, username, "");
			return;
		}

		// Database and Mojang lookups block, so keep them off the Discord and server threads.
		Sentinel.EXECUTOR.execute(() -> {
			try {
				whitelist(message, discordId, username);
			} catch (Exception e) {
				Sentinel.LOGGER.error("Whitelisting {} for Discord user {} failed", username, discordId, e);
				reply(message, Sentinel.config().whitelist.errorMessage, username, "");
			} finally {
				pendingUsers.remove(discordId);
				pending.release();
			}
		});
	}

	/** Waits up to the timeout for requests in progress to finish. */
	public void awaitIdle(Duration timeout) {
		try {
			if (pending.tryAcquire(MAX_PENDING, timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				pending.release(MAX_PENDING);
			} else {
				Sentinel.LOGGER.warn("Whitelist requests still running after {} seconds", timeout.toSeconds());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void whitelist(Message message, String discordId, String username) throws Exception {
		SentinelConfig config = Sentinel.config();
		SentinelData data = Sentinel.data();
		Optional<SentinelData.Link> existing = data.linkByDiscord(discordId);
		if (existing.isPresent()) {
			reply(message, config.whitelist.alreadyLinkedMessage, username, existing.get().name());
			return;
		}
		if (!VALID_NAME.matcher(username).matches()) {
			reply(message, config.whitelist.notFoundMessage, username, "");
			return;
		}
		MinecraftServer server = Sentinel.server();
		if (server == null) {
			reply(message, config.whitelist.errorMessage, username, "");
			return;
		}

		var profile = server.services().profileResolver().fetchByName(username);
		if (profile.isEmpty()) {
			reply(message, config.whitelist.notFoundMessage, username, "");
			return;
		}
		NameAndId account = new NameAndId(profile.get());
		if (!server.usesAuthentication()) {
			// Offline-mode servers identify players by a UUID derived from the name.
			account = NameAndId.createOffline(account.name());
		}

		switch (data.tryLink(discordId, account.id(), account.name())) {
			case DISCORD_ALREADY_LINKED -> {
				String linked = data.linkByDiscord(discordId).map(SentinelData.Link::name).orElse("");
				reply(message, config.whitelist.alreadyLinkedMessage, account.name(), linked);
				return;
			}
			case ACCOUNT_TAKEN -> {
				reply(message, config.whitelist.accountTakenMessage, account.name(), "");
				return;
			}
			case LINKED -> {
			}
		}

		try {
			addToWhitelist(server, account);
		} catch (Exception e) {
			data.unlink(discordId);
			throw e;
		}
		Sentinel.LOGGER.info("Whitelisted {} for Discord user {}", account.name(), discordId);

		DiscordBot bot = Sentinel.bot();
		if (bot != null) {
			CompletableFuture.allOf(
					bot.setRole(discordId, config.whitelist.addRoleId, true),
					bot.setRole(discordId, config.whitelist.removeRoleId, false)
			).exceptionally(e -> {
				Sentinel.LOGGER.warn("Could not update whitelist roles for Discord user {}", discordId, e);
				return null;
			});
		}
		message.addReaction(Emoji.fromFormatted(config.whitelist.successEmoji)).queue(null,
				e -> Sentinel.LOGGER.warn("Could not react to whitelist message", e));
	}

	/**
	 * Adds the account to the whitelist on the server thread and waits for it.
	 * Throws if the server stops first, in which case the account is not added.
	 */
	private static void addToWhitelist(MinecraftServer server, NameAndId account) throws Exception {
		// Whoever claims first decides: the server task adds the account, or this thread gives up.
		AtomicBoolean claimed = new AtomicBoolean();
		CompletableFuture<Void> added = new CompletableFuture<>();
		Sentinel.onServerThread(() -> {
			if (!claimed.compareAndSet(false, true)) return;
			try {
				server.getPlayerList().getWhiteList().add(new UserWhiteListEntry(account));
				added.complete(null);
			} catch (RuntimeException e) {
				added.completeExceptionally(e);
			}
		});
		// The server drops queued tasks once it is stopping, so stop waiting then.
		while (Sentinel.server() == server) {
			try {
				added.get(1, TimeUnit.SECONDS);
				return;
			} catch (TimeoutException ignored) {
			}
		}
		if (claimed.compareAndSet(false, true)) throw new IllegalStateException("the server stopped");
		added.get();
	}

	private static void reply(Message message, String template, String username, String linked) {
		String text = template.replace("{username}", username).replace("{linked}", linked);
		message.reply(text).mentionRepliedUser(false).queue(null,
				e -> Sentinel.LOGGER.warn("Could not reply to whitelist message", e));
	}
}

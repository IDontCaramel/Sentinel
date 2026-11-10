package dev.sentinel.discord;

import dev.sentinel.Sentinel;
import dev.sentinel.config.SentinelConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;

import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns the JDA connection and the Discord actions the rest of the mod needs. */
public final class DiscordBot {
	/**
	 * Role changes run one at a time, across bot restarts too, so they reach Discord in the order
	 * they were made. Otherwise taking the banned role away for an old ban could land after
	 * giving it for a new one.
	 */
	private static final ExecutorService ROLE_CHANGES =
			Executors.newSingleThreadExecutor(Thread.ofVirtual().name("sentinel-roles").factory());

	private final SentinelConfig config;
	private final JDA jda;

	private DiscordBot(SentinelConfig config, JDA jda) {
		this.config = config;
		this.jda = jda;
	}

	/** Starts connecting in the background. Throws if the token is rejected outright. */
	public static DiscordBot start(SentinelConfig config, Object... listeners) {
		JDA jda = JDABuilder.createLight(config.discord.token,
						EnumSet.of(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT))
				.addEventListeners(listeners)
				.build();
		return new DiscordBot(config, jda);
	}

	public void shutdown() {
		jda.shutdown();
		try {
			if (!jda.awaitShutdown(5, TimeUnit.SECONDS)) jda.shutdownNow();
		} catch (InterruptedException e) {
			jda.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	/** The configured guild, waiting for the connection to be ready first. */
	private Guild guild() throws InterruptedException {
		jda.awaitReady();
		Guild guild = jda.getGuildById(config.discord.guildId);
		if (guild == null) throw new IllegalStateException("Bot is not in the guild " + config.discord.guildId);
		return guild;
	}

	/**
	 * Gives or takes a role. Completes normally when the member has left the guild,
	 * or when taking away a role that no longer exists in the guild, since there is nothing left to change.
	 */
	public CompletableFuture<Void> setRole(String userId, String roleId, boolean give) {
		if (roleId.isBlank()) return CompletableFuture.completedFuture(null);
		return CompletableFuture.supplyAsync(() -> {
			try {
				Guild guild = guild();
				Role role = guild.getRoleById(roleId);
				if (role == null) {
					if (give) throw new IllegalStateException("Role " + roleId + " not found in " + guild.getName());
					Sentinel.LOGGER.info("Role {} is not in {} anymore, so there is nothing to take from {}",
							roleId, guild.getName(), userId);
					return null;
				}
				UserSnowflake user = UserSnowflake.fromId(userId);
				(give ? guild.addRoleToMember(user, role) : guild.removeRoleFromMember(user, role)).complete();
				return null;
			} catch (ErrorResponseException e) {
				if (e.getErrorResponse() == ErrorResponse.UNKNOWN_MEMBER) {
					Sentinel.LOGGER.info("Discord user {} is no longer in the guild, skipping role change", userId);
					return null;
				}
				throw new RuntimeException(e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}
		}, ROLE_CHANGES);
	}

	/** Posts a message in the given channel. User mentions in it ping the user. */
	public CompletableFuture<Message> post(String channelId, String text) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				GuildMessageChannel channel = guild().getChannelById(GuildMessageChannel.class, channelId);
				if (channel == null) throw new IllegalStateException("Channel " + channelId + " not found");
				return channel.sendMessage(new MessageCreateBuilder()
						.setContent(text)
						.setAllowedMentions(EnumSet.of(Message.MentionType.USER))
						.build()).complete();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new RuntimeException(e);
			}
		}, Sentinel.EXECUTOR);
	}
}

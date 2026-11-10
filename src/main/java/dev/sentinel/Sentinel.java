package dev.sentinel;

import dev.sentinel.ban.BanManager;
import dev.sentinel.compat.LuckPermsCompat;
import dev.sentinel.config.IdSetting;
import dev.sentinel.config.SentinelConfig;
import dev.sentinel.data.SentinelData;
import dev.sentinel.discord.DiscordBot;
import dev.sentinel.discord.SettingsCommand;
import dev.sentinel.discord.WhitelistListener;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class Sentinel implements DedicatedServerModInitializer {
	public static final String MOD_ID = "sentinel";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** For blocking work (Mojang lookups, Discord requests) that must not run on the server thread. */
	public static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
	/** Runs database work for the server thread one task at a time, in the order it was handed in. */
	private static final ExecutorService DATABASE =
			Executors.newSingleThreadExecutor(Thread.ofPlatform().name("sentinel-database").daemon().factory());

	private static final Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("sentinel.toml");
	private static final Path DATA_FILE = FabricLoader.getInstance().getConfigDir().resolve("sentinel/sentinel.db");

	private static volatile SentinelConfig config = new SentinelConfig();
	private static volatile MinecraftServer server;
	private static volatile DiscordBot bot;
	/** Held while starting or stopping the bot, so a reload can't race with the server stopping. */
	private static final Object BOT_LOCK = new Object();
	private static final SentinelData data = new SentinelData(DATA_FILE);
	private static final BanManager bans = new BanManager();
	private static final WhitelistListener whitelist = new WhitelistListener();
	private static final boolean LUCKPERMS = FabricLoader.getInstance().isModLoaded("luckperms");
	/** Only used on the server thread. */
	private static int ticksSinceCheck;

	public static SentinelConfig config() { return config; }
	public static SentinelData data() { return data; }
	public static BanManager bans() { return bans; }
	/** Null while the server is not running. */
	public static MinecraftServer server() { return server; }
	/** Null when Discord is disabled or failed to start. */
	public static DiscordBot bot() { return bot; }

	/**
	 * Runs database work on the database thread, so the server thread never waits on SQLite.
	 * Fails once the server has stopped.
	 */
	public static <T> CompletableFuture<T> database(Supplier<T> task) {
		try {
			return CompletableFuture.supplyAsync(task, DATABASE);
		} catch (RejectedExecutionException e) {
			return CompletableFuture.failedFuture(new IllegalStateException("the server has stopped", e));
		}
	}

	public static CompletableFuture<Void> database(Runnable task) {
		return database(() -> {
			task.run();
			return null;
		});
	}

	/**
	 * Runs the task on the server thread, or drops it once the server is stopping.
	 * Unlike {@link MinecraftServer#execute}, never runs it on the calling thread after the server stopped.
	 */
	public static void onServerThread(Runnable task) {
		MinecraftServer current = server;
		if (current == null) return;
		current.execute(() -> {
			if (server == current) task.run();
		});
	}

	@Override
	public void onInitializeServer() {
		if (!loadConfig()) LOGGER.error("Discord features are off until {} is fixed and reloaded", CONFIG_FILE);
		try {
			data.open();
		} catch (Exception e) {
			throw new IllegalStateException("Sentinel could not open its database " + DATA_FILE, e);
		}

		CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) ->
				SentinelCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			// LuckPerms registers its API while the server is starting, so it is ready here.
			if (LUCKPERMS) LuckPermsCompat.enable(s);
			synchronized (BOT_LOCK) {
				server = s;
				startBot();
			}
			bans.checkExpired(s);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			synchronized (BOT_LOCK) {
				server = null;
				stopBot();
			}
			if (LUCKPERMS) LuckPermsCompat.disable();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			// Let queued work finish before the database closes under it.
			whitelist.awaitIdle(Duration.ofSeconds(5));
			DATABASE.shutdown();
			try {
				if (!DATABASE.awaitTermination(5, TimeUnit.SECONDS)) {
					LOGGER.warn("Database work did not finish in time, closing the database anyway");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			data.close();
		});

		ServerTickEvents.END_SERVER_TICK.register(s -> {
			// Compared with the current setting each tick, so a reload applies a new interval right away.
			if (++ticksSinceCheck < config.bans.expiryCheckInterval * 20) return;
			ticksSinceCheck = 0;
			bans.checkExpired(s);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			var player = handler.getPlayer().nameAndId();
			database(() -> data.updateName(player.id(), player.name())).exceptionally(e -> {
				LOGGER.warn("Could not update the stored name of {}", player.name(), e);
				return null;
			});
		});
	}

	/**
	 * Re-reads the config and reconnects to Discord. Returns a short status for the command.
	 * Blocks while the old connection shuts down, so call it off the server thread.
	 */
	static String reload() {
		if (!loadConfig()) return "Config has errors, kept the old settings. See the server log.";
		synchronized (BOT_LOCK) {
			if (server == null) return "The server is stopping, Discord was not reconnected.";
			stopBot();
			startBot();
			onServerThread(() -> bans.checkExpired(server));
			return bot != null ? "Sentinel reloaded." : "Sentinel reloaded, but Discord is not connected. See the server log.";
		}
	}

	/**
	 * Saves a channel or role ID to the config file and applies it right away.
	 * The Discord connection stays up, since only IDs change. On failure the file and the
	 * running config are left as they were.
	 */
	public static synchronized void setId(IdSetting setting, String value) throws IOException {
		String before = Files.readString(CONFIG_FILE);
		try {
			SentinelConfig.writeId(CONFIG_FILE, setting, value);
		} catch (IOException | RuntimeException e) {
			Files.writeString(CONFIG_FILE, before);
			throw e;
		}
		SentinelConfig updated = readConfig();
		if (updated != null && setting.get(updated).equals(value)) {
			config = updated;
			return;
		}
		Files.writeString(CONFIG_FILE, before);
		throw new IOException("could not update " + setting.path() + " in " + CONFIG_FILE + ", see the server log");
	}

	private static synchronized boolean loadConfig() {
		SentinelConfig loaded = readConfig();
		if (loaded == null) return false;
		config = loaded;
		return true;
	}

	/** The config file, or null after logging why it can't be used. */
	private static SentinelConfig readConfig() {
		SentinelConfig loaded;
		try {
			loaded = SentinelConfig.load(CONFIG_FILE);
		} catch (Exception e) {
			LOGGER.error("Could not read {}: {}", CONFIG_FILE, e.getMessage());
			return null;
		}
		List<String> problems = loaded.validate();
		if (problems.isEmpty()) return loaded;
		problems.forEach(p -> LOGGER.error("Config: {}", p));
		return null;
	}

	/** Call while holding {@link #BOT_LOCK}. */
	private static void startBot() {
		if (config.discord.token.isBlank() || config.discord.guildId.isBlank()) {
			LOGGER.warn("Discord token or guild ID not set in {}, Discord features are off", CONFIG_FILE);
			return;
		}
		try {
			bot = DiscordBot.start(config, whitelist, new SettingsCommand());
			LOGGER.info("Connecting to Discord");
		} catch (Exception e) {
			LOGGER.error("Could not start the Discord bot: {}", e.getMessage());
			bot = null;
		}
	}

	/** Call while holding {@link #BOT_LOCK}. */
	private static void stopBot() {
		DiscordBot current = bot;
		bot = null;
		if (current != null) current.shutdown();
	}
}

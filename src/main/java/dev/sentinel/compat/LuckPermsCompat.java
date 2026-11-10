package dev.sentinel.compat;

import dev.sentinel.Sentinel;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LuckPerms already answers Sentinel's permission checks through Fabric's permission API.
 * This class only fixes what the player sees: when their permissions change, the server sends
 * them the command list again, so {@code /sban} shows up or disappears without a relog.
 *
 * <p>Only load this class when LuckPerms is installed.
 */
public final class LuckPermsCompat {
	private static final Set<UUID> pending = ConcurrentHashMap.newKeySet();
	private static EventSubscription<UserDataRecalculateEvent> subscription;

	private LuckPermsCompat() {
	}

	public static void enable(MinecraftServer server) {
		if (subscription != null) return;
		try {
			subscription = LuckPermsProvider.get().getEventBus().subscribe(UserDataRecalculateEvent.class,
					event -> refresh(server, event.getUser().getUniqueId()));
			Sentinel.LOGGER.info("LuckPerms found, command lists will update when permissions change");
		} catch (IllegalStateException e) {
			Sentinel.LOGGER.warn("LuckPerms is installed but its API is not loaded: {}", e.getMessage());
		}
	}

	public static void disable() {
		if (subscription != null) subscription.close();
		subscription = null;
		pending.clear();
	}

	// LuckPerms can recalculate one user several times in a row (for example when a group changes),
	// so one command list is sent per burst.
	private static void refresh(MinecraftServer server, UUID id) {
		if (!pending.add(id)) return;
		server.execute(() -> {
			pending.remove(id);
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) server.getCommands().sendCommands(player);
		});
	}
}

package dev.sentinel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.permission.v1.PermissionPredicates;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

final class SentinelCommands {
	private SentinelCommands() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(literal("sban")
				.requires(permission("command.sban", () -> Sentinel.config().permissions.sbanLevel))
				.then(argument("player", GameProfileArgument.gameProfile())
						.then(argument("days", IntegerArgumentType.integer(1))
								.then(argument("reason", StringArgumentType.greedyString())
										.executes(SentinelCommands::sban)))));

		dispatcher.register(literal("sentinel")
				.requires(permission("command.reload", () -> Sentinel.config().permissions.reloadLevel))
				.then(literal("reload").executes(SentinelCommands::reload)));
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		source.sendSuccess(() -> Component.literal("Reloading Sentinel..."), true);
		// Reconnecting to Discord can take seconds, so keep it off the server thread.
		CompletableFuture.supplyAsync(Sentinel::reload, Sentinel.EXECUTOR).whenComplete((result, error) -> {
			if (error != null) Sentinel.LOGGER.error("Reloading Sentinel failed", error);
			Sentinel.onServerThread(() -> {
				if (error == null) {
					source.sendSuccess(() -> Component.literal(result), true);
				} else {
					source.sendFailure(Component.literal("Reloading Sentinel failed, see the server log."));
				}
			});
		});
		return 1;
	}

	/** Permission node from a permissions mod, falling back to the configured operator level. */
	private static Predicate<CommandSourceStack> permission(String node, IntSupplier level) {
		Identifier id = Identifier.fromNamespaceAndPath(Sentinel.MOD_ID, node);
		return source -> PermissionPredicates.<CommandSourceStack>require(id, PermissionLevel.byId(level.getAsInt()))
				.test(source);
	}

	private static int sban(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		Collection<NameAndId> targets = GameProfileArgument.getGameProfiles(ctx, "player");
		if (targets.size() != 1) {
			source.sendFailure(Component.literal("Pick exactly one player."));
			return 0;
		}
		NameAndId target = targets.iterator().next();
		int days = IntegerArgumentType.getInteger(ctx, "days");
		int maxDays = Sentinel.config().bans.maxDays;
		if (days > maxDays) {
			source.sendFailure(Component.literal("A ban can last at most " + maxDays + " days."));
			return 0;
		}
		String reason = StringArgumentType.getString(ctx, "reason");

		Sentinel.bans().ban(source.getServer(), target, days, reason, source.getTextName(),
				warning -> source.sendFailure(Component.literal(warning)));
		source.sendSuccess(() -> Component.literal("Banned " + target.name() + " for " + days + " days: " + reason), true);
		return 1;
	}
}

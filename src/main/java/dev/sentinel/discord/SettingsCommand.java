package dev.sentinel.discord;

import dev.sentinel.Sentinel;
import dev.sentinel.config.IdSetting;
import dev.sentinel.config.SentinelConfig;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Discord command {@code /sentinel}, which lets staff pick the whitelist and ban channels and roles
 * without editing the config file.
 *
 * <p>Access is checked twice. Discord only shows the command to members with Manage Server
 * (server admins can change that under Server Settings &gt; Integrations), and the bot checks again
 * itself before changing anything, in case the Integrations settings were opened up too far.
 */
public final class SettingsCommand extends ListenerAdapter {
	private static final String NAME = "sentinel";

	@Override
	public void onReady(ReadyEvent event) {
		Guild guild = event.getJDA().getGuildById(Sentinel.config().discord.guildId);
		if (guild == null) {
			Sentinel.LOGGER.error("The bot is not in the Discord server {}, so /sentinel was not registered",
					Sentinel.config().discord.guildId);
			return;
		}

		OptionData channelSetting = new OptionData(OptionType.STRING, "setting", "Which channel to set", true);
		OptionData roleSetting = new OptionData(OptionType.STRING, "setting", "Which role to set", true);
		for (IdSetting setting : IdSetting.values()) {
			(setting.kind == IdSetting.Kind.CHANNEL ? channelSetting : roleSetting).addChoice(setting.label, setting.name());
		}

		guild.updateCommands().addCommands(Commands.slash(NAME, "Configure Sentinel")
				.setContexts(InteractionContextType.GUILD)
				.setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
				.addSubcommands(
						new SubcommandData("channel", "Set the whitelist or ban log channel")
								.addOptions(channelSetting, new OptionData(OptionType.CHANNEL, "channel",
										"The channel to use. Leave empty to turn this off.")
										.setChannelTypes(ChannelType.TEXT, ChannelType.NEWS)),
						new SubcommandData("role", "Set a role the bot gives or takes away")
								.addOptions(roleSetting, new OptionData(OptionType.ROLE, "role",
										"The role to use. Leave empty to turn this off.")),
						new SubcommandData("settings", "Show the current channels and roles")
				)).queue(
				ok -> Sentinel.LOGGER.info("Registered the /sentinel command in {}", guild.getName()),
				e -> Sentinel.LOGGER.error("Could not register the /sentinel command. "
						+ "Was the bot invited with the applications.commands scope?", e));
	}

	@Override
	public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
		if (!event.getName().equals(NAME)) return;
		SentinelConfig config = Sentinel.config();
		Guild guild = event.getGuild();
		Member member = event.getMember();
		if (guild == null || member == null || !guild.getId().equals(config.discord.guildId)) {
			reply(event, "This command only works in the server Sentinel is set up for.");
			return;
		}
		if (!isAllowed(member, config)) {
			Sentinel.LOGGER.warn("Discord user {} ({}) tried to use /sentinel without permission",
					member.getUser().getName(), member.getId());
			reply(event, "You need the Manage Server permission to use this command.");
			return;
		}

		switch (event.getSubcommandName()) {
			case "channel" -> setChannel(event, member);
			case "role" -> setRole(event, member);
			case "settings" -> reply(event, describe(config));
			case null, default -> reply(event, "Unknown subcommand.");
		}
	}

	private static boolean isAllowed(Member member, SentinelConfig config) {
		if (member.hasPermission(Permission.MANAGE_SERVER)) return true;
		List<String> adminRoles = config.permissions.discordAdminRoleIds;
		return member.getRoles().stream().anyMatch(role -> adminRoles.contains(role.getId()));
	}

	private static void setChannel(SlashCommandInteractionEvent event, Member member) {
		IdSetting setting = setting(event, IdSetting.Kind.CHANNEL);
		if (setting == null) return;
		OptionMapping option = event.getOption("channel");
		if (option == null) {
			save(event, member, setting, "", "turned off");
			return;
		}

		GuildChannel channel = option.getAsChannel();
		if (!(channel instanceof GuildMessageChannel)) {
			reply(event, "Pick a text channel.");
			return;
		}
		Set<Permission> needed = setting == IdSetting.WHITELIST_CHANNEL
				? EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
				Permission.MESSAGE_ADD_REACTION)
				: EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND);
		Member self = event.getGuild().getSelfMember();
		Set<Permission> missing = needed.stream()
				.filter(p -> !self.hasPermission(channel, p))
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
		if (!missing.isEmpty()) {
			reply(event, "I'm missing these permissions in " + channel.getAsMention() + ": "
					+ missing.stream().map(Permission::getName).collect(Collectors.joining(", ")) + ".");
			return;
		}
		save(event, member, setting, channel.getId(), "set to " + channel.getAsMention());
	}

	private static void setRole(SlashCommandInteractionEvent event, Member member) {
		IdSetting setting = setting(event, IdSetting.Kind.ROLE);
		if (setting == null) return;
		OptionMapping option = event.getOption("role");
		if (option == null) {
			save(event, member, setting, "", "turned off");
			return;
		}

		Role role = option.getAsRole();
		if (role.isPublicRole() || role.isManaged()) {
			reply(event, role.getAsMention() + " can't be given or taken away by a bot. Pick a normal role.");
			return;
		}
		// Without this, anyone with Manage Server could make the bot hand out roles above their own,
		// for example an admin role to everyone who whitelists.
		if (!member.canInteract(role)) {
			reply(event, "You can only pick roles below your own highest role.");
			return;
		}
		if (!event.getGuild().getSelfMember().canInteract(role)) {
			reply(event, "I can't manage " + role.getAsMention() + ". Drag my role above it in the server's "
					+ "role list, and make sure I have the Manage Roles permission.");
			return;
		}
		save(event, member, setting, role.getId(), "set to " + role.getAsMention());
	}

	/** The chosen setting, or null after replying when it is not one of the given kind. */
	private static IdSetting setting(SlashCommandInteractionEvent event, IdSetting.Kind kind) {
		String name = event.getOption("setting", OptionMapping::getAsString);
		for (IdSetting setting : IdSetting.values()) {
			if (setting.name().equals(name) && setting.kind == kind) return setting;
		}
		reply(event, "Unknown setting.");
		return null;
	}

	private static void save(SlashCommandInteractionEvent event, Member member, IdSetting setting,
							 String value, String outcome) {
		try {
			Sentinel.setId(setting, value);
		} catch (IOException | RuntimeException e) {
			Sentinel.LOGGER.error("Could not save {} from Discord", setting.path(), e);
			reply(event, "Saving the setting failed, see the server log.");
			return;
		}
		Sentinel.LOGGER.info("Discord user {} ({}) set {} to \"{}\"",
				member.getUser().getName(), member.getId(), setting.path(), value);
		reply(event, setting.label + " " + outcome + ".");
	}

	private static String describe(SentinelConfig config) {
		StringBuilder text = new StringBuilder("**Sentinel settings**");
		for (IdSetting setting : IdSetting.values()) {
			String id = setting.get(config);
			String value = id.isBlank() ? "not set"
					: setting.kind == IdSetting.Kind.CHANNEL ? "<#" + id + ">" : "<@&" + id + ">";
			text.append("\n").append(setting.label).append(": ").append(value);
		}
		return text.toString();
	}

	/** Only the person who ran the command sees the reply, and it never pings anyone. */
	private static void reply(SlashCommandInteractionEvent event, String text) {
		event.reply(text).setEphemeral(true).setAllowedMentions(List.of()).queue(null,
				e -> Sentinel.LOGGER.warn("Could not answer /sentinel", e));
	}
}

package dev.ftb.mods.ftbteams.data.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.architectury.platform.Platform;
import dev.ftb.mods.ftbteams.FTBTeamsAPIImpl;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import dev.ftb.mods.ftbteams.api.TeamManager;
import dev.ftb.mods.ftbteams.api.TeamRank;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import dev.ftb.mods.ftbteams.api.event.TeamInfoEvent;
import dev.ftb.mods.ftbteams.api.property.TeamPropertyArgument;
import dev.ftb.mods.ftbteams.data.*;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;

import java.util.Collection;
import java.util.Comparator;
import java.util.UUID;
import java.util.function.Predicate;

public class FTBTeamsCommands {
    /**
     * Important concepts
     *  - Player-Party:
     *      - A unique-per-player team generated for all players upon first-join.
     *      - The fallback team for the play if not a member of a Party-Team
     *  - Party-Team:
     *      - A created party, owned/attributed by a player.
     *      - A player can only be a member of One party at a time, and cannot create
     *        a new Party-Team if currently a member of another (NB, Party-Team != Player-Party).
     *  - Server-Team:
     *      - A non player-joinable party, creatable only by server admins.
     *
     */
    private static class CommandBuilders {
        /**
         * /ftbteams party (...)
         *  - Should encompass all regular-player party functions, eg:
         *      - Party creation/management/configuration, joining/leaving, etc.
         *  - Management/config commands require appropriate permissions for the relevant party
         *  - Admin (server op) management is handled under the /ftbteams admin (...) node.
         */
        static LiteralArgumentBuilder<CommandSourceStack> party = Commands.literal("party")
                /**
                 * /ftbteams party create <party name...>
                 *  - Should create a new Party-Team owned by the calling player
                 *  - Not shown to players who are already a member of a Party-Team
                 *
                 *  Relevant function: FTBTeamsCommands::tryCreateParty
                 */
                .then(Commands.literal("create")
                        .requires(FTBTeamsCommands::hasNoPartyTeam)
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(FTBTeamsCommands::tryCreateParty)
                        )
                        .executes(FTBTeamsCommands::tryCreateParty)
                )
                /**
                 * /ftbteams party join {team}
                 *  - Joins the specified team.
                 *  - {team} will be prepopulated with teams the player has active invites to.
                 */
                .then(Commands.literal("join")
                        // TODO: Check, is this correct? does hasNoPartyTeam return true if you're an 'invited' 'member' of a team?
                        .requires(FTBTeamsCommands::hasNoPartyTeam)
                        .then(Commands.argument("team", TeamArgumentType.party())
                                .executes(ctx -> {
                                    // Die if the command executor isn't a player - eg, the server console, or a command block.
                                    Entity sourceExecutor = ctx.getSource().getEntity();
                                    if(!(sourceExecutor instanceof ServerPlayer player)){
                                        // TODO: On second pass - Should commands be throwing? Maybe rather we ctx.getSource().sendFailure()
                                        throw EntityArgument.ERROR_ONLY_PLAYERS_ALLOWED.create();
                                    }

                                    PartyTeam team = (PartyTeam) TeamArgumentType.getTeam(ctx, "team");
                                    if (team.getRankForPlayer(player.getUUID()) != TeamRank.INVITED){
                                        throw TeamArgumentType.NOT_INVITED.create(team.getName());
                                    }

                                    // I'm not sure what this return value implies, but I'm keeping it the way it was for now.
                                    return team.join(player);
                                })
                        )
                )
                /**
                 * /ftbteams party decline {team}
                 *  - Declines an invite from the specified team.
                 *  - {team} will be prepopulated with teams the player has active invites to.
                 */
                .then(Commands.literal("decline")
                        // TODO: Predicate for having standing party invites. - ArgumentType for teams w/ standing invites.
                        .then(Commands.argument("team", TeamArgumentType.party())
                                .executes(ctx -> {
                                    // Die if the command executor isn't a player - eg, the server console, or a command block.
                                    Entity sourceExecutor = ctx.getSource().getEntity();
                                    if(!(sourceExecutor instanceof ServerPlayer player)){
                                        throw EntityArgument.ERROR_ONLY_PLAYERS_ALLOWED.create();
                                    }

                                    PartyTeam team = (PartyTeam) TeamArgumentType.getTeam(ctx, "team");
                                    if (team.getRankForPlayer(player.getUUID()) != TeamRank.INVITED){
                                        throw TeamArgumentType.NOT_INVITED.create(team.getName());
                                    }

                                    boolean result = team.declineInvitation(player);
                                    if(result){
                                        ctx.getSource().sendSuccess(() -> Component.translatable("ftbteams.message.declined"), true);
                                    }

                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("leave")
                        //TODO: predicate for BEING a member of a team (don't show leave unless a member of a party)
                        //      + ArgumentType for current team memberships.
                        .executes(ctx -> {
                            // Die if the command executor isn't a player - eg, the server console, or a command block.
                            Entity sourceExecutor = ctx.getSource().getEntity();
                            if(!(sourceExecutor instanceof ServerPlayer player)){
                                throw EntityArgument.ERROR_ONLY_PLAYERS_ALLOWED.create();
                            }

                            // I hate that the only option is to throw here.
                            PartyTeam team = (PartyTeam) TeamManagerImpl.INSTANCE.getTeamForPlayer(player).orElseThrow();
                            // MemberOrBetter implies an actual member of the team. (ie NOT: Enemy,Ally,None,Invited)
                            if(team.getRankForPlayer(player.getUUID()).isMemberOrBetter()){
                                team.leave(player.getUUID());
                            };
                            return 1;
                        })
                );


        static LiteralArgumentBuilder<CommandSourceStack> admin = Commands.literal("admin");
    }
    public void register(CommandDispatcher<CommandSourceStack> dispatcher){
        dispatcher.register(Commands.literal("ftbteams")
                .then(CommandBuilders.party)
                .then(CommandBuilders.admin)
        );
    }
	public void oldRegister(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("ftbteams")
				.then(Commands.literal("party")
						.then(Commands.literal("invite")
								.requires(source -> hasParty(source, TeamRank.OFFICER))
								.then(Commands.argument("players", GameProfileArgument.gameProfile())
										.executes(ctx -> getPartyTeam(ctx, TeamRank.OFFICER).invite(ctx.getSource().getPlayerOrException(), GameProfileArgument.getGameProfiles(ctx, "players")))
								)
						)
						.then(Commands.literal("kick")
								.requires(source -> hasParty(source, TeamRank.OFFICER))
								.then(Commands.argument("players", GameProfileArgument.gameProfile())
										.executes(ctx -> getPartyTeam(ctx, TeamRank.OFFICER).kick(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "players")))
								)
						)
						.then(Commands.literal("transfer_ownership")
								.requires(source -> hasParty(source, TeamRank.OWNER))
								.then(Commands.argument("player_id", GameProfileArgument.gameProfile())
										.executes(ctx -> partyTeamArg(ctx, TeamRank.OWNER).transferOwnership(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "player_id")))
								)
						)
						.then(Commands.literal("transfer_ownership_for")
								.requires(requiresOPorSP())
								.then(createTeamArg(TeamType.PARTY)
										.then(Commands.argument("player_id", GameProfileArgument.gameProfile())
												.executes(ctx -> partyTeamArg(ctx, TeamRank.NONE).transferOwnership(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "player_id")))
										)
								)
						)
						.then(Commands.literal("settings")
								.requires(source -> hasParty(source, TeamRank.OWNER))
								.then(Commands.argument("key", TeamPropertyArgument.create())
										.then(Commands.argument("value", StringArgumentType.greedyString())
												.executes(ctx -> getPartyTeam(ctx, TeamRank.OWNER).settings(ctx.getSource(), TeamPropertyArgument.get(ctx, "key"), string(ctx, "value")))
										)
										.executes(ctx -> getPartyTeam(ctx, TeamRank.OWNER).settings(ctx.getSource(), TeamPropertyArgument.get(ctx, "key"), ""))
								)
						)
						.then(Commands.literal("settings_for")
								.requires(requiresOPorSP())
								.then(createTeamArg(TeamType.PARTY)
										.then(Commands.argument("key", TeamPropertyArgument.create())
												.then(Commands.argument("value", StringArgumentType.greedyString())
														.executes(ctx -> partyTeamArg(ctx, TeamRank.NONE).settings(ctx.getSource(), TeamPropertyArgument.get(ctx, "key"), string(ctx, "value")))
												)
												.executes(ctx -> partyTeamArg(ctx, TeamRank.NONE).settings(ctx.getSource(), TeamPropertyArgument.get(ctx, "key"), ""))
										)
								)
						)
						.then(Commands.literal("allies")
								.requires(source -> hasParty(source, TeamRank.MEMBER))
								.then(Commands.literal("add")
										.requires(source -> hasParty(source, TeamRank.OFFICER))
										.then(Commands.argument("player", GameProfileArgument.gameProfile())
												.executes(ctx -> getPartyTeam(ctx, TeamRank.OFFICER).addAlly(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "player")))
										)
								)
								.then(Commands.literal("remove")
										.requires(source -> hasParty(source, TeamRank.OFFICER))
										.then(Commands.argument("player", GameProfileArgument.gameProfile())
												.executes(ctx -> getPartyTeam(ctx, TeamRank.OFFICER).removeAlly(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "player")))
										)
								)
								.then(Commands.literal("list")
										.requires(source -> hasParty(source, TeamRank.MEMBER))
										.executes(ctx -> getPartyTeam(ctx, TeamRank.MEMBER).listAllies(ctx.getSource()))
								)
						)
				)
				.then(Commands.literal("server")
						.requires(requiresOPorSP())
						.then(Commands.literal("create")
								.then(Commands.argument("name", StringArgumentType.greedyString())
										.executes(ctx -> TeamManagerImpl.INSTANCE.createServer(ctx.getSource(), string(ctx, "name")).getLeft())
								)
						)
						.then(Commands.literal("delete")
								.then(createTeamArg(TeamType.SERVER)
										.executes(ctx -> serverTeamArg(ctx).delete(ctx.getSource()))
								)
						)
						.then(Commands.literal("settings")
								.then(createTeamArg(TeamType.SERVER)
										.then(Commands.argument("key", TeamPropertyArgument.create())
												.then(Commands.argument("value", StringArgumentType.greedyString())
														.executes(ctx -> serverTeamArg(ctx).settings(ctx.getSource(), TeamPropertyArgument.get(ctx, "key"), string(ctx, "value")))
												)
												.executes(ctx -> serverTeamArg(ctx).settings(ctx.getSource(), TeamPropertyArgument.get(ctx, "key"), ""))
										)
								)
						)
				)
				.then(Commands.literal("msg")
						.then(Commands.argument("text", StringArgumentType.greedyString())
								.executes(ctx -> {
									getTeam(ctx).sendMessage(ctx.getSource().getPlayerOrException().getUUID(), StringArgumentType.getString(ctx, "text"));
									return Command.SINGLE_SUCCESS;
								})
						)
				)
				.then(Commands.literal("info")
						.then(Commands.literal("server_id")
								.executes(ctx -> serverId(ctx.getSource()))
						)
						.then(createTeamArg()
								.executes(ctx -> info(ctx.getSource(), teamArg(ctx)))
						)
						.executes(ctx -> info(ctx.getSource(), getTeam(ctx)))
				)
				.then(Commands.literal("list")
						.executes(ctx -> list(ctx.getSource(), t -> true))
						.then(Commands.literal("parties")
								.executes(ctx -> list(ctx.getSource(), Team::isPartyTeam))
						)
						.then(Commands.literal("server_teams")
								.executes(ctx -> list(ctx.getSource(), Team::isServerTeam))
						)
						.then(Commands.literal("players")
								.executes(ctx -> list(ctx.getSource(), Team::isPlayerTeam))
						)
				)
				.then(Commands.literal("force-disband")
						.requires(source -> source.hasPermission(2))
						.then(createTeamArg(TeamType.PARTY)
								.executes(ctx -> partyTeamArg(ctx, TeamRank.NONE).forceDisband(ctx.getSource()))
						)
				)
				.then(Commands.literal("redirect_chat")
						.executes(FTBTeamsCommands::redirectChatToggle)
				)
		);

		if (Platform.isDevelopmentEnvironment()) {
			dispatcher.register(Commands.literal("ftbteams_add_fake_player")
					.requires(source -> source.hasPermission(2))
					.then(Commands.argument("profile", GameProfileArgument.gameProfile())
							.executes(ctx -> addFakePlayer(GameProfileArgument.getGameProfiles(ctx, "profile")))
					)
			);
		}
	}

	private static Predicate<CommandSourceStack> requiresOPorSP() {
		return source -> source.getServer().isSingleplayer() || source.hasPermission(2);
	}

	private static RequiredArgumentBuilder<CommandSourceStack, TeamArgumentProvider> createTeamArg() {
		return createTeamArg(null);
	}

	private static RequiredArgumentBuilder<CommandSourceStack, TeamArgumentProvider> createTeamArg(TeamType type) {
		return Commands.argument("team", TeamArgumentType.create(type));
	}

	private static String string(CommandContext<?> context, String name) {
		return StringArgumentType.getString(context, name);
	}

    /**
     * Determines party-membership of a CommandSourceStack's executing entity.
     *
     * @param source
     * @return <b>True if:</b>
     *         <p>- Player is not a member of a Party-team
     *         <p><b>False if:</b>
     *         <p>- Player is only part of their own Player-Party
     *         <p>- Executor is not a ServerPlayer
     */
	private static boolean hasNoPartyTeam(CommandSourceStack source) {
		if (source.getEntity() instanceof ServerPlayer) {
			return FTBTeamsAPI.api().getManager().getTeamForPlayerID(source.getEntity().getUUID())
					.map(team -> !team.isPartyTeam())
					.orElse(false);
		}

		return false;
	}

    private static boolean isMemberOfParty(CommandSourceStack source){

    }

	private boolean hasParty(CommandSourceStack source, TeamRank rank) {
		if (source.getEntity() instanceof ServerPlayer) {
			UUID playerId = source.getEntity().getUUID();
			return FTBTeamsAPI.api().getManager().getTeamForPlayerID(playerId)
					.map(team -> team.isPartyTeam() && team.getRankForPlayer(playerId).isAtLeast(rank))
					.orElse(false);
		}

		return false;
	}

	private static Team getTeam(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		return FTBTeamsAPI.api().getManager().getTeamForPlayer(player)
				.orElseThrow(() -> TeamArgumentType.TEAM_NOT_FOUND.create(player.getUUID()));
	}

	private static PartyTeam getPartyTeam(CommandContext<CommandSourceStack> context, TeamRank minRank) throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		Team team = FTBTeamsAPI.api().getManager().getTeamForPlayer(player)
				.orElseThrow(() -> TeamArgumentType.TEAM_NOT_FOUND.create(player.getUUID()));

		if (!(team instanceof PartyTeam partyTeam)) {
			throw TeamArgumentType.NOT_IN_PARTY.create();
		}

		if (!partyTeam.getRankForPlayer(player.getUUID()).isAtLeast(minRank)) {
			throw TeamArgumentType.CANT_EDIT.create(team.getName());
		}

		return partyTeam;
	}

	private static Team teamArg(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return TeamArgumentType.getTeam(context, "team");
	}

	private static Team teamArg(CommandContext<CommandSourceStack> context, Predicate<Team> predicate) throws CommandSyntaxException {
		Team team = teamArg(context);

		if (!predicate.test(team)) {
			throw TeamArgumentType.TEAM_NOT_FOUND.create(team.getName());
		}

		return team;
	}

	private static ServerTeam serverTeamArg(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		return (ServerTeam) teamArg(context, Team::isServerTeam);
	}

	private static PartyTeam partyTeamArg(CommandContext<CommandSourceStack> context, TeamRank rank) throws CommandSyntaxException {
		PartyTeam team = (PartyTeam) teamArg(context, Team::isPartyTeam);

		if (rank != TeamRank.NONE && !team.getRankForPlayer(context.getSource().getPlayerOrException().getUUID()).isAtLeast(rank)) {
			throw TeamArgumentType.NOT_INVITED.create(team.getName());
		}

		return team;
	}

	private static int tryCreateParty(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (FTBTeamsAPIImpl.INSTANCE.isPartyCreationFromAPIOnly()) {
            throw TeamArgumentType.API_OVERRIDE.create();
        }

        // Die if the command executor isn't a player - eg, the server console, or a command block.
        Entity sourceExecutor = ctx.getSource().getEntity();
        if(!(sourceExecutor instanceof ServerPlayer player)){
            throw EntityArgument.ERROR_ONLY_PLAYERS_ALLOWED.create();
        }

        // If the incoming command has a "name" parameter, use that. Otherwise, default to ""
        // StringArgumentType.getString() calls CommandContext.getArgument(). That function throws
        // if the specified argument (here it's "name") doesn't exist.
        // We do this dance to avoid an expensive exception throw.
        // We could also just have a separate function for the no-name case, but I'm trying to keep to one-function per 'actual' command
        String partyName = ctx.getNodes().stream().anyMatch(e -> e.getNode().getName().equals("name"))
                ? StringArgumentType.getString(ctx, "name")
                : "";

        return TeamManagerImpl.INSTANCE.createParty(player, partyName).getLeft();
	}

	private static int info(CommandSourceStack source, Team team) {
		team.getTeamInfo().forEach(line -> source.sendSuccess(() -> line, false));

		TeamEvent.INFO.invoker().accept(new TeamInfoEvent(team, source));

		return Command.SINGLE_SUCCESS;
	}

	private static int serverId(CommandSourceStack source) {
		UUID managerId = FTBTeamsAPI.api().getManager().getId();
		source.sendSuccess(() -> Component.literal("Server ID: ")
				.append(FTBTUtils.makeCopyableComponent(managerId.toString()).withStyle(ChatFormatting.YELLOW)),
				false);
		return Command.SINGLE_SUCCESS;
	}

	private int list(CommandSourceStack source, Predicate<Team> predicate) {
		Component teams = FTBTeamsAPI.api().getManager().getTeams().stream()
				.filter(predicate)
				.sorted(Comparator.comparing(Team::getShortName))
				.map(Team::getName)
				.reduce((c1, c2) -> c1.copy().append(", ").append(c2))
				.orElse(Component.literal("<none>"));

		source.sendSuccess(() -> Component.translatable("ftbteams.list", teams), false);
		return Command.SINGLE_SUCCESS;
	}

	private static int redirectChatToggle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		TeamManager mgr = FTBTeamsAPI.api().getManager();
		mgr.setChatRedirected(player, !mgr.isChatRedirected(player));
		String key = "ftbteams.message.chat_redirected." + (mgr.isChatRedirected(player) ? "on" : "off");
		ctx.getSource().sendSuccess(() -> Component.translatable(key).withStyle(ChatFormatting.ITALIC, ChatFormatting.GOLD), false);
		return Command.SINGLE_SUCCESS;
	}

	private int addFakePlayer(Collection<GameProfile> profiles) {
		for (GameProfile profile : profiles) {
			TeamManagerImpl.INSTANCE.playerLoggedIn(null, profile.getId(), profile.getName());
		}

		return Command.SINGLE_SUCCESS;
	}
}

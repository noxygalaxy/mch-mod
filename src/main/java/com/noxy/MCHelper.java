package com.noxy;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.TypedActionResult;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class MCHelper implements ModInitializer {
	public static final String MOD_ID = "mchelper";
	public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
	private static final Map<UUID, Long> lastCommandExecutions = new HashMap<>();
	private static final Map<UUID, ServerBossBar> playerBossBars = new HashMap<>();
	private static final int COMMAND_COOLDOWN_MS = 50;
	private static final int BOSSBAR_DISPLAY_TIME_MS = 50;

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing MCHelper mod");
		registerCommands();
		registerEvents();
	}

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
			registerSetItemCommand(dispatcher);
			registerRemoveItemCommand(dispatcher);
			registerListBindingsCommand(dispatcher);
		});
	}

	private void registerSetItemCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(literal("setitem")
				.then(literal("LMB")
						.then(argument("command", StringArgumentType.greedyString())
								.executes(context -> executeSetItem(
										context,
										"LMB",
										StringArgumentType.getString(context, "command")
								)))));
	}

	private void registerRemoveItemCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(literal("removeitem")
				.then(literal("LMB")
						.executes(context -> executeRemoveItem(context, "LMB"))));
	}

	private void registerListBindingsCommand(CommandDispatcher<ServerCommandSource> dispatcher) {
		dispatcher.register(literal("listbindings")
				.executes(context -> executeListBindings(context)));
	}

	private int executeListBindings(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayer();
		ItemStack heldItem = player.getMainHandStack();

		if (heldItem.isEmpty()) {
			context.getSource().sendError(new LiteralText("You must hold an item to see its bindings"));
			return 0;
		}

		NbtCompound tag = heldItem.getTag();
		if (tag == null) {
			context.getSource().sendFeedback(new LiteralText("This item has no command bindings"), false);
			return 1;
		}

		String lmbCommand = tag.getString("BoundCommandLMB");
		context.getSource().sendFeedback(new LiteralText("Command Bindings for held item:"), false);
		context.getSource().sendFeedback(new LiteralText("LMB: " + (lmbCommand.isEmpty() ? "None" : lmbCommand)), false);
		return 1;
	}

	private void showBossBar(ServerPlayerEntity player, String message, boolean success) {
		UUID playerUUID = player.getUuid();
		ServerBossBar existingBossBar = playerBossBars.get(playerUUID);

		if (existingBossBar != null) {
			existingBossBar.clearPlayers();
			existingBossBar.setVisible(false);
		}

		final ServerBossBar newBossBar = new ServerBossBar(
				new LiteralText(message),
				success ? BossBar.Color.GREEN : BossBar.Color.RED,
				BossBar.Style.PROGRESS
		);

		newBossBar.addPlayer(player);
		newBossBar.setPercent(1.0f);
		playerBossBars.put(playerUUID, newBossBar);

		player.getServer().execute(() -> {
			try {
				Thread.sleep(BOSSBAR_DISPLAY_TIME_MS);
				newBossBar.clearPlayers();
				newBossBar.setVisible(false);
				playerBossBars.remove(playerUUID);
			} catch (InterruptedException e) {
				LOGGER.error("Error while handling boss bar display", e);
			}
		});
	}

	private int executeSetItem(CommandContext<ServerCommandSource> context, String mouseButton, String command) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayer();
		ItemStack heldItem = player.getMainHandStack();

		if (heldItem.isEmpty()) {
			showBossBar(player, "Hold an item to bind a command!", false);
			return 0;
		}

		NbtCompound tag = heldItem.getOrCreateTag();
		tag.putString("BoundCommand" + mouseButton, command);

		showBossBar(player, "Command bound to " + mouseButton + " successfully!", true);
		return 1;
	}

	private int executeRemoveItem(CommandContext<ServerCommandSource> context, String mouseButton) throws CommandSyntaxException {
		ServerPlayerEntity player = context.getSource().getPlayer();
		ItemStack heldItem = player.getMainHandStack();

		if (heldItem.isEmpty()) {
			showBossBar(player, "Hold an item to remove its binding!", false);
			return 0;
		}

		NbtCompound tag = heldItem.getTag();
		if (tag != null) {
			tag.remove("BoundCommand" + mouseButton);
		}

		showBossBar(player, "Command binding removed for " + mouseButton, true);
		return 1;
	}

	private void registerEvents() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (world.isClient) return TypedActionResult.pass(ItemStack.EMPTY);

			ItemStack stack = player.getStackInHand(hand);
			NbtCompound tag = stack.getTag();
			if (tag == null) return TypedActionResult.pass(stack);

			String command = tag.getString("BoundCommandLMB");

			if (!command.isEmpty()) {
				UUID playerUUID = player.getUuid();
				long currentTime = System.currentTimeMillis();
				Long lastExecution = lastCommandExecutions.get(playerUUID);

				if (lastExecution == null || currentTime - lastExecution >= COMMAND_COOLDOWN_MS) {
					if (player.getServer() != null) {
						player.getServer().getCommandManager().execute(
								player.getCommandSource().withSilent(),
								command
						);
						lastCommandExecutions.put(playerUUID, currentTime);
						showBossBar((ServerPlayerEntity)player, "Executed: " + command, true);
					}
				} else {
					showBossBar((ServerPlayerEntity)player, "Cooldown: " +
							((COMMAND_COOLDOWN_MS - (currentTime - lastExecution)) / 1000.0) + "s", false);
				}
			}

			return TypedActionResult.success(stack);
		});
	}
}
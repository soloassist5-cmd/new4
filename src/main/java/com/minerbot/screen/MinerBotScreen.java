package com.minerbot.screen;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.minerbot.blueprint.Blueprint;
import com.minerbot.bot.BotController;
import com.minerbot.bot.TaskLauncher;
import com.minerbot.config.BotSettings;
import com.minerbot.config.BuildDirection;
import com.minerbot.selection.Region;
import com.minerbot.selection.SelectionManager;
import com.minerbot.task.BotTask;
import com.minerbot.util.Registries;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The whole bot behind one key, so nothing has to be typed.
 *
 * <p>The block under the crosshair is read once, when the menu opens, and shown on the corner
 * buttons. Picking it live would be unpredictable: the camera stops moving while the menu is up but
 * the game keeps ray casting every frame, so a working bot turning its head would quietly change
 * what the button does.
 *
 * <p>Not a pause screen, so the integrated server keeps ticking and a running job stays visible in
 * the status line instead of freezing the moment the player looks at it.
 */
public class MinerBotScreen extends Screen {
	private static final int COLUMN_WIDTH = 152;
	private static final int FIELD_WIDTH = 104;
	private static final int PICK_WIDTH = 44;
	private static final int ROW_HEIGHT = 20;
	private static final int DETAIL_LINES = 2;
	/**
	 * Text colours carry alpha in this version: a plain {@code 0xRRGGBB} is fully transparent, so
	 * the field would silently render nothing at all.
	 */
	private static final int TEXT_OK = 0xFFE0E0E0;
	private static final int TEXT_BAD = 0xFFFF5555;

	/** The block the player was looking at when the menu opened, if any. */
	@Nullable
	private final BlockPos lookedAt;

	private final LinearLayout layout = LinearLayout.vertical().spacing(3);

	private StringWidget crosshairLine;
	private StringWidget selectionLine;
	private StringWidget clipboardLine;
	private StringWidget statusLine;
	private final StringWidget[] detailLines = new StringWidget[DETAIL_LINES];

	private EditBox blockField;
	private EditBox toolField;
	private EditBox copiesField;
	private Button mineButton;
	private Button buildButton;
	private Button copyButton;
	private Button stopButton;

	public MinerBotScreen(@Nullable BlockPos lookedAt) {
		super(Component.translatable("minerbot.screen.title"));
		this.lookedAt = lookedAt;
	}

	/** Reads the crosshair target and opens the menu for it. */
	public static MinerBotScreen open(net.minecraft.client.Minecraft client) {
		BlockPos target = null;

		if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			target = hit.getBlockPos();
		}

		return new MinerBotScreen(target);
	}

	@Override
	protected void init() {
		buildLayout();
		this.layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		refresh();
	}

	private void buildLayout() {
		this.layout.addChild(new StringWidget(getTitle(), this.font));
		this.crosshairLine = this.layout.addChild(new StringWidget(312, 9, crosshairLabel(), this.font));
		this.layout.addChild(cornerRow());
		this.selectionLine = this.layout.addChild(new StringWidget(312, 9, Component.empty(), this.font));

		LinearLayout columns = LinearLayout.horizontal().spacing(8);
		columns.addChild(mineColumn());
		columns.addChild(buildColumn());
		this.layout.addChild(columns);

		this.layout.addChild(footerRow());
		this.statusLine = this.layout.addChild(new StringWidget(312, 9, Component.empty(), this.font));

		for (int i = 0; i < DETAIL_LINES; i++) {
			this.detailLines[i] = this.layout.addChild(new StringWidget(312, 9, Component.empty(), this.font));
		}
	}

	/**
	 * The label says which block the corner buttons will take, rather than baking the coordinates
	 * into the buttons: it keeps them a readable width, and leaves their text fixed so a test can
	 * find them.
	 */
	private Component crosshairLabel() {
		if (this.lookedAt == null) {
			return Component.translatable("minerbot.screen.crosshair.none").withStyle(ChatFormatting.DARK_GRAY);
		}

		return Component.translatable("minerbot.screen.crosshair", Component.literal(
				"%d %d %d".formatted(this.lookedAt.getX(), this.lookedAt.getY(), this.lookedAt.getZ())))
				.withStyle(ChatFormatting.GRAY);
	}

	private LinearLayout cornerRow() {
		LinearLayout row = LinearLayout.horizontal().spacing(4);
		row.addChild(Button.builder(
				Component.translatable("minerbot.screen.corner1"),
				button -> setCorner(true)).width(126).build());
		row.addChild(Button.builder(
				Component.translatable("minerbot.screen.corner2"),
				button -> setCorner(false)).width(126).build());
		row.addChild(Button.builder(
				Component.translatable("minerbot.screen.clear"),
				button -> {
					SelectionManager.get().clear();
					say(Component.translatable("minerbot.screen.cleared").withStyle(ChatFormatting.GRAY), List.of());
				}).width(52).build());
		return row;
	}

	private LinearLayout mineColumn() {
		LinearLayout column = LinearLayout.vertical().spacing(3);
		column.addChild(new StringWidget(COLUMN_WIDTH, 9,
				Component.translatable("minerbot.screen.mine.header").withStyle(ChatFormatting.YELLOW), this.font));

		LinearLayout blockRow = LinearLayout.horizontal().spacing(4);
		this.blockField = blockRow.addChild(new EditBox(this.font, FIELD_WIDTH, ROW_HEIGHT,
				Component.translatable("minerbot.screen.mine.block")));
		this.blockField.setMaxLength(64);
		this.blockField.setHint(Component.translatable("minerbot.screen.mine.block.hint"));
		this.blockField.setTooltip(Tooltip.create(Component.translatable("minerbot.screen.mine.block.tip")));
		this.blockField.setValue(BotSettings.blockName());
		this.blockField.setResponder(value -> {
			BotSettings.setBlockName(value);
			refresh();
		});
		blockRow.addChild(Button.builder(
				Component.translatable("minerbot.screen.from_crosshair"),
				button -> fillBlockFromCrosshair())
				.tooltip(Tooltip.create(Component.translatable("minerbot.screen.from_crosshair.tip")))
				.width(PICK_WIDTH).build());
		column.addChild(blockRow);

		LinearLayout toolRow = LinearLayout.horizontal().spacing(4);
		this.toolField = toolRow.addChild(new EditBox(this.font, FIELD_WIDTH, ROW_HEIGHT,
				Component.translatable("minerbot.screen.mine.tool")));
		this.toolField.setMaxLength(64);
		this.toolField.setHint(Component.translatable("minerbot.screen.mine.tool.hint"));
		this.toolField.setTooltip(Tooltip.create(Component.translatable("minerbot.screen.mine.tool.tip")));
		this.toolField.setValue(BotSettings.toolName());
		this.toolField.setResponder(value -> {
			BotSettings.setToolName(value);
			refresh();
		});
		toolRow.addChild(Button.builder(
				Component.translatable("minerbot.screen.from_hand"),
				button -> fillToolFromHand())
				.tooltip(Tooltip.create(Component.translatable("minerbot.screen.from_hand.tip")))
				.width(PICK_WIDTH).build());
		column.addChild(toolRow);

		column.addChild(CycleButton.onOffBuilder(BotSettings.tunnelling())
				.withTooltip(value -> Tooltip.create(Component.translatable("minerbot.screen.mine.tunnel.tip")))
				.create(0, 0, COLUMN_WIDTH, ROW_HEIGHT,
						Component.translatable("minerbot.screen.mine.tunnel"),
						(button, value) -> BotSettings.setTunnelling(value)));

		this.mineButton = column.addChild(Button.builder(
				Component.translatable("minerbot.screen.mine.start"),
				button -> run(TaskLauncher.startMining(requirePlayer())))
				.tooltip(Tooltip.create(Component.translatable("minerbot.screen.mine.start.tip")))
				.width(COLUMN_WIDTH).build());
		return column;
	}

	private LinearLayout buildColumn() {
		LinearLayout column = LinearLayout.vertical().spacing(3);
		column.addChild(new StringWidget(COLUMN_WIDTH, 9,
				Component.translatable("minerbot.screen.build.header").withStyle(ChatFormatting.YELLOW), this.font));

		this.copyButton = column.addChild(Button.builder(
				Component.translatable("minerbot.screen.build.copy"),
				button -> run(TaskLauncher.copySelection(requirePlayer())))
				.tooltip(Tooltip.create(Component.translatable("minerbot.screen.build.copy.tip")))
				.width(COLUMN_WIDTH).build());
		this.clipboardLine = column.addChild(new StringWidget(COLUMN_WIDTH, 9, Component.empty(), this.font));

		column.addChild(CycleButton.builder(BuildDirection::label, BotSettings.buildDirection())
				.withValues(BuildDirection.values())
				.withTooltip(value -> Tooltip.create(Component.translatable("minerbot.screen.build.direction.tip")))
				.create(0, 0, COLUMN_WIDTH, ROW_HEIGHT,
						Component.translatable("minerbot.screen.build.direction"),
						(button, value) -> BotSettings.setBuildDirection(value)));

		LinearLayout copiesRow = LinearLayout.horizontal().spacing(4);
		copiesRow.addChild(new StringWidget(FIELD_WIDTH, ROW_HEIGHT,
				Component.translatable("minerbot.screen.build.copies"), this.font));
		this.copiesField = copiesRow.addChild(new EditBox(this.font, PICK_WIDTH, ROW_HEIGHT,
				Component.translatable("minerbot.screen.build.copies")));
		this.copiesField.setMaxLength(2);
		this.copiesField.setValue(String.valueOf(BotSettings.copies()));
		this.copiesField.setTooltip(Tooltip.create(
				Component.translatable("minerbot.screen.build.copies.tip", BotSettings.MAX_COPIES)));
		this.copiesField.setResponder(value -> {
			Integer parsed = parseCopies(value);

			if (parsed != null) {
				BotSettings.setCopies(parsed);
			}

			refresh();
		});
		column.addChild(copiesRow);

		this.buildButton = column.addChild(Button.builder(
				Component.translatable("minerbot.screen.build.start"),
				button -> run(TaskLauncher.startBuilding(requirePlayer())))
				.tooltip(Tooltip.create(Component.translatable("minerbot.screen.build.start.tip")))
				.width(COLUMN_WIDTH).build());
		return column;
	}

	private LinearLayout footerRow() {
		LinearLayout row = LinearLayout.horizontal().spacing(4);
		this.stopButton = row.addChild(Button.builder(
				Component.translatable("minerbot.screen.stop"),
				button -> run(TaskLauncher.stop())).width(152).build());
		row.addChild(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(152).build());
		return row;
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		FrameLayout.centerInRectangle(this.layout, getRectangle());
	}

	@Override
	public void tick() {
		refresh();
	}

	/** Brings the lines and the button states back in step with the world and the settings. */
	private void refresh() {
		Region region = SelectionManager.get().region();
		this.selectionLine.setMessage(region == null
				? Component.translatable("minerbot.screen.selection.none").withStyle(ChatFormatting.DARK_GRAY)
				: Component.translatable("minerbot.screen.selection", region.toString(), region.volume())
						.withStyle(ChatFormatting.GRAY));

		Blueprint clipboard = BotSettings.clipboard();
		this.clipboardLine.setMessage(clipboard == null
				? Component.translatable("minerbot.screen.build.clipboard.empty").withStyle(ChatFormatting.DARK_GRAY)
				: Component.translatable("minerbot.screen.build.clipboard", clipboard.blockCount())
						.withStyle(ChatFormatting.GRAY));

		BotTask task = BotController.get().task();
		boolean running = task != null;

		if (running) {
			this.statusLine.setMessage(task.status());
		}

		boolean blockKnown = BotSettings.blockName().isEmpty()
				|| BotSettings.blockName().equals("all")
				|| Registries.block(BotSettings.blockName()) != null;
		boolean toolKnown = BotSettings.toolName().isEmpty()
				|| BotSettings.toolName().equals("auto")
				|| Registries.item(BotSettings.toolName()) != null;

		this.blockField.setTextColor(blockKnown ? TEXT_OK : TEXT_BAD);
		this.toolField.setTextColor(toolKnown ? TEXT_OK : TEXT_BAD);

		boolean copiesValid = parseCopies(this.copiesField.getValue()) != null;
		this.copiesField.setTextColor(copiesValid ? TEXT_OK : TEXT_BAD);

		this.mineButton.active = !running && region != null && blockKnown && toolKnown;
		this.copyButton.active = !running && region != null;
		this.buildButton.active = !running && clipboard != null && copiesValid;
		this.stopButton.active = running;
	}

	private void setCorner(boolean first) {
		BlockPos pos = this.lookedAt != null ? this.lookedAt : requirePlayer().blockPosition();

		if (first) {
			SelectionManager.get().setFirst(pos);
		} else {
			SelectionManager.get().setSecond(pos);
		}

		say(Component.translatable("minerbot.screen.corner_set", first ? 1 : 2,
				pos.getX(), pos.getY(), pos.getZ()).withStyle(ChatFormatting.GRAY), List.of());
	}

	private void fillBlockFromCrosshair() {
		if (this.lookedAt == null) {
			say(Component.translatable("minerbot.screen.no_crosshair").withStyle(ChatFormatting.RED), List.of());
			return;
		}

		String name = Registries.nameOf(requirePlayer().level().getBlockState(this.lookedAt).getBlock());
		this.blockField.setValue(name);
		say(Component.translatable("minerbot.screen.picked_block", name).withStyle(ChatFormatting.GRAY), List.of());
	}

	private void fillToolFromHand() {
		ItemStack held = requirePlayer().getInventory().getSelectedItem();

		if (held.isEmpty()) {
			say(Component.translatable("minerbot.screen.empty_hand").withStyle(ChatFormatting.RED), List.of());
			return;
		}

		String name = Registries.nameOf(held.getItem());
		this.toolField.setValue(name);
		say(Component.translatable("minerbot.screen.picked_tool", name).withStyle(ChatFormatting.GRAY), List.of());
	}

	/** Shows the outcome, and closes the menu when a job actually started. */
	private void run(TaskLauncher.Outcome outcome) {
		say(outcome.message(), outcome.details());

		if (outcome.ok() && BotController.get().isRunning()) {
			onClose();
		}
	}

	private void say(Component message, List<Component> details) {
		this.statusLine.setMessage(message);

		for (int i = 0; i < DETAIL_LINES; i++) {
			this.detailLines[i].setMessage(i < details.size() ? details.get(i) : Component.empty());
		}

		if (details.size() > DETAIL_LINES) {
			this.detailLines[DETAIL_LINES - 1].setMessage(Component.translatable(
					"minerbot.screen.and_more", details.size() - (DETAIL_LINES - 1)).withStyle(ChatFormatting.RED));
		}

		refresh();
	}

	/** {@code null} only after the world has gone, in which case the menu is already closing. */
	private LocalPlayer requirePlayer() {
		LocalPlayer player = this.minecraft.player;

		if (player == null) {
			throw new IllegalStateException("MinerBot menu open without a player");
		}

		return player;
	}

	@Nullable
	private static Integer parseCopies(String value) {
		try {
			int parsed = Integer.parseInt(value.trim());
			return parsed >= 1 && parsed <= BotSettings.MAX_COPIES ? parsed : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

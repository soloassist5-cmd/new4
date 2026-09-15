package com.minerbot.util;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Name lookups for the ids players type into commands.
 *
 * <p>The block and item registries are defaulted, so an unknown id silently yields air instead of
 * failing; every lookup here checks membership first and reports the miss.
 */
public final class Registries {
	private Registries() {
	}

	/** Resolves {@code stone} or {@code minecraft:stone}; returns {@code null} if unknown. */
	@Nullable
	public static Block block(String name) {
		Identifier id = parse(name);

		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
			return null;
		}

		return BuiltInRegistries.BLOCK.getValue(id);
	}

	/** Resolves {@code diamond_pickaxe} or {@code minecraft:diamond_pickaxe}. */
	@Nullable
	public static Item item(String name) {
		Identifier id = parse(name);

		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
			return null;
		}

		return BuiltInRegistries.ITEM.getValue(id);
	}

	public static String nameOf(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	public static String nameOf(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	@Nullable
	private static Identifier parse(String name) {
		return Identifier.tryParse(name.indexOf(':') >= 0 ? name : "minecraft:" + name);
	}
}

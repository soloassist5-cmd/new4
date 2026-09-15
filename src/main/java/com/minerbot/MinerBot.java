package com.minerbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.resources.Identifier;

public final class MinerBot {
	public static final String MOD_ID = "minerbot";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private MinerBot() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

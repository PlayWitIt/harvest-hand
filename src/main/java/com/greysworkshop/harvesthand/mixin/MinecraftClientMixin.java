package com.greysworkshop.harvesthand.mixin;

import com.greysworkshop.harvesthand.Harvester;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla stops any block-breaking every tick unless the attack button is held. While Harvest Hand breaks its shulker
 * box (which takes a few seconds, unlike a crop) that check is skipped, so the box comes back even with the game window
 * in the background.
 */
@Mixin(MinecraftClient.class)
abstract class MinecraftClientMixin {
	@Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
	private void harvesthand$keepBreaking(boolean breaking, CallbackInfo ci) {
		if (Harvester.breakingBox()) ci.cancel();
	}
}

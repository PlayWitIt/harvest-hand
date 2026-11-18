package com.greysworkshop.harvesthand.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Tells the server which hotbar slot is held. Vanilla only does this when you use an item or keep mining; a crop
 * breaks on the first hit, so without it the server breaks crops with whatever was held last (the seeds) and the
 * Fortune hoe does nothing.
 */
@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionManagerAccess {
	@Invoker("syncSelectedSlot")
	void harvesthand$syncSelectedSlot();
}

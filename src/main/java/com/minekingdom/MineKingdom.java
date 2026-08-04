package com.minekingdom;

import com.minekingdom.registry.ModEntities;
import com.minekingdom.registry.ModItems;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MineKingdom.MOD_ID)
public class MineKingdom {
    public static final String MOD_ID = "minekingdom";

    public MineKingdom() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
    }
}

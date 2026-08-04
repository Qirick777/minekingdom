package com.minekingdom.registry;

import com.minekingdom.MineKingdom;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MineKingdom.MOD_ID);

    public static final RegistryObject<Item> CITIZEN_SPAWN_EGG =
            ITEMS.register("citizen_spawn_egg", () -> new ForgeSpawnEggItem(
                    ModEntities.CITIZEN, 0xC8A279, 0x3B5DC9, new Item.Properties()));
}

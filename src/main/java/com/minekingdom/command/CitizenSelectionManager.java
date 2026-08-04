package com.minekingdom.command;

import com.minekingdom.entity.CitizenEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Remembers which citizens a command source last selected.
 *
 * <p>Selection is a convenience for driving citizens by hand and is deliberately
 * kept out of the entity: anything that already knows which citizens it wants can
 * call {@link CitizenEntity#setTask} directly without going through here.
 */
public final class CitizenSelectionManager {
    /** Stand-in owner for sources that are not a player, such as the console or a command block. */
    private static final UUID NON_PLAYER_OWNER = new UUID(0L, 0L);

    private static final Map<UUID, Set<UUID>> SELECTIONS = new HashMap<>();

    private CitizenSelectionManager() {
    }

    public static void select(CommandSourceStack source, List<CitizenEntity> citizens) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (CitizenEntity citizen : citizens) {
            ids.add(citizen.getUUID());
        }
        SELECTIONS.put(ownerOf(source), ids);
    }

    /** Resolves the stored selection, dropping citizens that have since died or unloaded. */
    public static List<CitizenEntity> resolve(CommandSourceStack source) {
        Set<UUID> ids = SELECTIONS.get(ownerOf(source));
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }

        MinecraftServer server = source.getServer();
        List<CitizenEntity> citizens = new ArrayList<>();
        ids.removeIf(id -> {
            CitizenEntity citizen = find(server, id);
            if (citizen == null) {
                return true;
            }
            citizens.add(citizen);
            return false;
        });
        return citizens;
    }

    public static void clear(CommandSourceStack source) {
        SELECTIONS.remove(ownerOf(source));
    }

    /** Selections are per session; nothing should survive a server restart. */
    public static void clearAll() {
        SELECTIONS.clear();
    }

    private static CitizenEntity find(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof CitizenEntity citizen && citizen.isAlive()) {
                return citizen;
            }
        }
        return null;
    }

    private static UUID ownerOf(CommandSourceStack source) {
        Entity entity = source.getEntity();
        return entity != null ? entity.getUUID() : NON_PLAYER_OWNER;
    }
}

package com.minekingdom.entity.task;

import net.minecraft.util.StringRepresentable;

/**
 * What a citizen is currently assigned to do.
 *
 * <p>Tasks are plain state on the entity: commands, and later jobs or
 * workplace blocks, all drive citizens by calling
 * {@link com.minekingdom.entity.CitizenEntity#setTask}. Nothing about a
 * task is tied to how it was assigned.
 */
public enum CitizenTask implements StringRepresentable {
    IDLE("idle"),
    MINING("mining"),
    RETURNING("returning");

    private final String name;

    CitizenTask(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    public static CitizenTask byName(String name) {
        for (CitizenTask task : values()) {
            if (task.name.equals(name)) {
                return task;
            }
        }
        return IDLE;
    }
}

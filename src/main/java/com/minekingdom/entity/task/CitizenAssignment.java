package com.minekingdom.entity.task;

import net.minecraft.util.StringRepresentable;

/**
 * What a citizen has been put to, as opposed to what it happens to be doing this minute.
 *
 * <p>{@link CitizenTask} is the activity in progress and changes as work goes on; an
 * assignment outlives it. A citizen told to mine and come back alternates between mining
 * and returning, and it is the assignment that says to pick the work up again afterwards.
 * Later jobs get to reuse the same split.
 */
public enum CitizenAssignment implements StringRepresentable {
    NONE("none"),
    /**
     * Mine, heading back to the return point every so often and whenever there is nowhere
     * left to put anything. Mining without ever coming back was a separate job while the
     * round trip was being proved out; it is the only mining there is now.
     */
    MINING("mining");

    private final String name;

    CitizenAssignment(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    public static CitizenAssignment byName(String name) {
        for (CitizenAssignment assignment : values()) {
            if (assignment.name.equals(name)) {
                return assignment;
            }
        }
        return NONE;
    }
}

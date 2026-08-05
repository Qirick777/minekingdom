package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Answers "why is this citizen not going anywhere" without anyone having to guess.
 *
 * <p>Every way out of a spot is tried one at a time and the reason each is refused is
 * spelled out. The route planner collapses all of those reasons into a single no, which is
 * enough to plan with and useless to diagnose with: a citizen walled in by gravel and one
 * walled in by a player's bricks look identical from the outside.
 */
public final class CitizenDiagnostics {
    private CitizenDiagnostics() {
    }

    /** Every step out of the citizen's own square, with the verdict on each. */
    public static List<String> explainExits(CitizenEntity citizen) {
        Level level = citizen.level();
        BlockPos from = citizen.blockPosition();
        List<String> lines = new ArrayList<>();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = 1; dy >= -1; dy--) {
                BlockPos target = from.relative(direction).above(dy);
                lines.add(String.format("%-5s dy=%+d %s -> %s",
                        direction.getName(), dy, target.toShortString(),
                        stepVerdict(citizen, level, from, target, dy)));
            }
        }

        lines.add("up(build)     " + from.above().toShortString() + " -> " + buildVerdict(citizen, level, from));
        lines.add("down(own)     " + from.below().toShortString() + " -> " + downVerdict(citizen, level, from));
        return lines;
    }

    private static String stepVerdict(CitizenEntity citizen, Level level, BlockPos from, BlockPos target, int dy) {
        BlockPos floor = target.below();
        BlockState floorState = level.getBlockState(floor);
        if (!floorState.isFaceSturdy(level, floor, Direction.UP)) {
            return "no floor (" + name(level, floor) + " at " + floor.toShortString() + ")";
        }
        String body = clearVerdict(citizen, level, target, "body");
        if (body != null) {
            return body;
        }
        String head = clearVerdict(citizen, level, target.above(), "head");
        if (head != null) {
            return head;
        }
        if (dy > 0) {
            String room = clearVerdict(citizen, level, from.above(2), "climb room");
            if (room != null) {
                return room;
            }
        }
        return "OK";
    }

    private static String buildVerdict(CitizenEntity citizen, Level level, BlockPos from) {
        if (!new CitizenPillarBuilder(citizen).canBuild()) {
            return "nothing to stack on";
        }
        String body = clearVerdict(citizen, level, from.above(), "body");
        if (body != null) {
            return body;
        }
        String head = clearVerdict(citizen, level, from.above(2), "head");
        if (head != null) {
            return head;
        }
        return "OK";
    }

    private static String downVerdict(CitizenEntity citizen, Level level, BlockPos from) {
        BlockPos below = from.below();
        CitizenRoutePlanner.DigVerdict verdict =
                CitizenRoutePlanner.digVerdict(level, below, citizen::placedOwnBlock);
        if (verdict != CitizenRoutePlanner.DigVerdict.OK) {
            return "cannot cut footing: " + verdict + " (" + name(level, below) + ")";
        }
        BlockPos landing = below.below();
        if (!level.getBlockState(landing).isFaceSturdy(level, landing, Direction.UP)) {
            return "nothing to land on at " + landing.toShortString();
        }
        return "OK";
    }

    /** Null when the space is already clear or may be opened; otherwise why it may not. */
    private static String clearVerdict(CitizenEntity citizen, Level level, BlockPos pos, String what) {
        if (ReversibleWalk.isPassable(level, pos, null)) {
            return null;
        }
        CitizenRoutePlanner.DigVerdict verdict =
                CitizenRoutePlanner.digVerdict(level, pos, citizen::placedOwnBlock);
        if (verdict == CitizenRoutePlanner.DigVerdict.OK) {
            return null;
        }
        return what + " blocked: " + verdict + " (" + name(level, pos) + " at " + pos.toShortString() + ")";
    }

    private static String name(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock().getDescriptionId().replace("block.minecraft.", "");
    }
}

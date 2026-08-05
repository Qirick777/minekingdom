package com.minekingdom.entity.ai;

import com.minekingdom.MineKingdom;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The ground a citizen has walked over, kept so it can be walked again.
 *
 * <p>This is about movement rather than any particular job: whatever a citizen is doing,
 * the way it came is worth keeping open. Two things use it — the floors under the trail
 * are off limits to digging, and the trail supplies proven-good ground to check a route
 * against before a block is taken out.
 *
 * <p>The trail is trimmed rather than kept forever. When somewhere old is directly
 * reachable again, everything recorded since is no longer needed to get back to it and is
 * dropped, so what is held scales with how convoluted the route is rather than how long
 * the citizen has been at it. Callers clear it outright once a trip is done.
 */
public class CitizenPathMemory {
    /**
     * Trimming normally keeps this far out of reach. Passing it means the route is not
     * collapsing the way it should, which is worth knowing about rather than silently
     * dropping the oldest ground and with it the way home.
     */
    private static final int SOFT_LIMIT = 2048;

    private final Deque<BlockPos> trail = new ArrayDeque<>();
    private final Set<BlockPos> floors = new HashSet<>();
    private boolean warned;

    public void record(BlockPos standing) {
        if (standing.equals(this.trail.peekLast())) {
            return;
        }
        BlockPos pos = standing.immutable();
        this.trail.addLast(pos);
        this.floors.add(pos.below());

        if (this.trail.size() > SOFT_LIMIT) {
            if (!this.warned) {
                MineKingdom.LOGGER.warn("Citizen path memory passed {} entries around {}; "
                        + "the oldest ground is being forgotten", SOFT_LIMIT, pos);
                this.warned = true;
            }
            BlockPos dropped = this.trail.removeFirst();
            this.floors.remove(dropped.below());
        }
    }

    /** Ground the citizen stood on, which digging it away would take out from under the route. */
    public boolean isFloorOfRoute(BlockPos pos) {
        return this.floors.contains(pos);
    }

    /**
     * The oldest place in the trail that is still inside the given box, which is the
     * furthest back a bounded check can reasonably require a route to.
     */
    @Nullable
    public BlockPos oldestWithin(BlockPos centre, int horizontal, int vertical) {
        for (BlockPos pos : this.trail) {
            if (pos.equals(centre)) {
                continue;
            }
            if (Math.abs(pos.getX() - centre.getX()) <= horizontal
                    && Math.abs(pos.getZ() - centre.getZ()) <= horizontal
                    && Math.abs(pos.getY() - centre.getY()) <= vertical) {
                return pos;
            }
        }
        return null;
    }

    /**
     * Drops the stretch of trail that a shortcut has made redundant. If somewhere old is
     * in {@code reachable}, the wandering recorded since then is not needed to get back to
     * it. The newest entry is kept because that is where the citizen is standing.
     */
    public void compact(Set<BlockPos> reachable) {
        if (this.trail.size() < 3) {
            return;
        }
        List<BlockPos> entries = new ArrayList<>(this.trail);
        int oldest = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (reachable.contains(entries.get(i))) {
                oldest = i;
                break;
            }
        }
        if (oldest < 0 || oldest >= entries.size() - 2) {
            return;
        }

        List<BlockPos> kept = new ArrayList<>(entries.subList(0, oldest + 1));
        kept.add(entries.get(entries.size() - 1));

        this.trail.clear();
        this.floors.clear();
        for (BlockPos pos : kept) {
            this.trail.addLast(pos);
            this.floors.add(pos.below());
        }
    }

    /** Forgets the route entirely, for when the trip it belonged to is over. */
    public void clear() {
        this.trail.clear();
        this.floors.clear();
        this.warned = false;
    }

    public int size() {
        return this.trail.size();
    }
}

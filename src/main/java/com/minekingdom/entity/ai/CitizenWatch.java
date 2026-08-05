package com.minekingdom.entity.ai;

import com.minekingdom.entity.CitizenEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Follows citizens over time and says afterwards what each of them was really doing.
 *
 * <p>A single look tells you where a citizen is, and nothing at all about whether that is
 * where it was a moment ago. Frozen, shuffling on the spot, wandering in circles and slowly
 * getting there look the same in a snapshot, and telling them apart by eye across repeated
 * snapshots is how a citizen covering nine blocks in the wrong direction got read as stuck.
 */
public final class CitizenWatch {
    /** One sample a second is plenty to tell the four cases apart and costs nothing. */
    private static final int SAMPLE_INTERVAL = 20;
    /** Ten minutes of samples, after which the oldest go. */
    private static final int MAX_SAMPLES = 600;
    /** Ground covered below this over the whole watch counts as not having moved. */
    private static final double FROZEN = 2.0D;
    /** Net displacement below this, having covered ground, means it went nowhere. */
    private static final double OSCILLATING = 2.0D;
    /** Distance to the target closed by more than this counts as getting somewhere. */
    private static final double PROGRESS = 2.0D;

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private CitizenWatch() {
    }

    public static void start(List<CitizenEntity> citizens, int ticks) {
        for (CitizenEntity citizen : citizens) {
            TRACKS.put(citizen.getUUID(), new Track(citizen, citizen.level().getGameTime() + ticks));
        }
    }

    public static void clear() {
        TRACKS.clear();
    }

    public static boolean watching() {
        return !TRACKS.isEmpty();
    }

    /** Called every tick by the citizen; does nothing unless that citizen is being watched. */
    public static void sample(CitizenEntity citizen) {
        Track track = TRACKS.get(citizen.getUUID());
        if (track != null) {
            track.sample(citizen);
        }
    }

    /** One line per watched citizen, and the verdict on each. */
    public static List<String> report(long gameTime) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<UUID, Track> entry : TRACKS.entrySet()) {
            lines.add("uuid=" + shortId(entry.getKey()) + " " + entry.getValue().describe(gameTime));
        }
        return lines;
    }

    private static String shortId(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    private static final class Track {
        private final Vec3 start;
        @Nullable
        private final BlockPos target;
        private final double startDistance;
        private final long endsAt;
        private final int minedAtStart;

        private Vec3 last;
        private double pathLength;
        private int samples;
        private int ticks;
        private final EnumMap<CitizenJourney.Mode, Integer> modes = new EnumMap<>(CitizenJourney.Mode.class);

        private Vec3 end;
        private double endDistance;
        private int minedAtEnd;

        private Track(CitizenEntity citizen, long endsAt) {
            this.start = citizen.position();
            this.last = this.start;
            this.end = this.start;
            this.target = citizen.getReturnPoint();
            this.startDistance = this.distanceTo(this.start);
            this.endDistance = this.startDistance;
            this.endsAt = endsAt;
            this.minedAtStart = citizen.getMinedBlocks();
            this.minedAtEnd = this.minedAtStart;
        }

        private double distanceTo(Vec3 pos) {
            return this.target == null ? Double.NaN : pos.distanceTo(Vec3.atCenterOf(this.target));
        }

        private void sample(CitizenEntity citizen) {
            if (citizen.level().getGameTime() > this.endsAt) {
                return;
            }
            if (++this.ticks % SAMPLE_INTERVAL != 0) {
                return;
            }
            Vec3 now = citizen.position();
            this.pathLength += now.distanceTo(this.last);
            this.last = now;
            this.end = now;
            this.endDistance = this.distanceTo(now);
            this.minedAtEnd = citizen.getMinedBlocks();
            if (this.samples < MAX_SAMPLES) {
                this.samples++;
            }
            this.modes.merge(citizen.getJourney().mode(), 1, Integer::sum);
        }

        private String describe(long gameTime) {
            double net = this.end.distanceTo(this.start);
            double closed = Double.isNaN(this.startDistance) ? 0.0D : this.startDistance - this.endDistance;
            String verdict;
            if (this.pathLength < FROZEN) {
                verdict = "FROZEN";
            } else if (closed > PROGRESS) {
                verdict = "PROGRESSING";
            } else if (net < OSCILLATING) {
                verdict = "OSCILLATING";
            } else {
                verdict = "WANDERING";
            }

            Map<CitizenJourney.Mode, Integer> ordered = new LinkedHashMap<>(this.modes);
            StringBuilder histogram = new StringBuilder();
            for (Map.Entry<CitizenJourney.Mode, Integer> entry : ordered.entrySet()) {
                if (histogram.length() > 0) {
                    histogram.append(',');
                }
                histogram.append(entry.getKey()).append(':').append(entry.getValue());
            }

            return String.format("verdict=%s samples=%d path=%.1f net=%.1f closed=%+.1f mined=%d "
                            + "from=%.1f,%.1f,%.1f to=%.1f,%.1f,%.1f target=%s left=%d modes=[%s]",
                    verdict, this.samples, this.pathLength, net, closed,
                    this.minedAtEnd - this.minedAtStart,
                    this.start.x, this.start.y, this.start.z,
                    this.end.x, this.end.y, this.end.z,
                    this.target == null ? "none" : this.target.toShortString(),
                    Math.max(0L, this.endsAt - gameTime),
                    histogram);
        }
    }
}

package com.minekingdom.command;

import com.minekingdom.MineKingdom;
import com.minekingdom.entity.CitizenEntity;
import com.minekingdom.entity.ai.CitizenDiagnostics;
import com.minekingdom.entity.ai.CitizenWatch;
import com.minekingdom.entity.ai.CitizenTravel;
import com.minekingdom.entity.task.CitizenAssignment;
import com.minekingdom.entity.task.CitizenTask;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;

/**
 * Test harness for driving citizens by hand.
 *
 * <pre>
 * /ctest select [range] [count]   pick the nearest citizens around the source
 * /ctest mining start|stop        put the selection to work, or call it off
 * </pre>
 *
 * <p>The command only picks citizens and flips their task. All behaviour lives on
 * the entity, so other systems can assign the same work without a command.
 */
public final class CTestCommand {
    private static final double DEFAULT_RANGE = 16.0D;

    private CTestCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ctest")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("select")
                        .executes(context -> select(context, DEFAULT_RANGE, Integer.MAX_VALUE))
                        .then(Commands.argument("range", DoubleArgumentType.doubleArg(1.0D, 128.0D))
                                .executes(context -> select(context, range(context), Integer.MAX_VALUE))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 256))
                                        .executes(context -> select(context, range(context),
                                                IntegerArgumentType.getInteger(context, "count"))))))
                .then(Commands.literal("mining")
                        .then(Commands.literal("start")
                                .executes(context -> put(context, CitizenAssignment.MINING)))
                        .then(Commands.literal("stop")
                                .executes(context -> put(context, CitizenAssignment.NONE)))
                        .then(Commands.literal("report")
                                .executes(CTestCommand::minedReport))
                        .then(Commands.literal("reset")
                                .executes(CTestCommand::resetMined)))
                // Experimental: the same work, but heading home now and then and whenever
                // there is nowhere left to put anything.
                .then(Commands.literal("mining2")
                        .then(Commands.literal("start")
                                .executes(context -> put(context, CitizenAssignment.MINING_WITH_RETURN)))
                        .then(Commands.literal("stop")
                                .executes(context -> put(context, CitizenAssignment.NONE))))
                .then(Commands.literal("home")
                        .then(Commands.literal("set")
                                .executes(CTestCommand::setHome))
                        .then(Commands.literal("return")
                                .executes(context -> assign(context, CitizenTask.RETURNING)))
                        .then(Commands.literal("report")
                                .executes(CTestCommand::report)))
                .then(Commands.literal("debug")
                        .executes(CTestCommand::debug))
                // What each selected citizen is actually doing about getting somewhere.
                .then(Commands.literal("why")
                        .executes(CTestCommand::why)
                        // Every way out of the nearest selected citizen's square, and the
                        // reason each one is refused.
                        .then(Commands.literal("blocked")
                                .executes(CTestCommand::whyBlocked)))
                .then(Commands.literal("watch")
                        .then(Commands.literal("stop")
                                .executes(context -> watchStop(context, true)))
                        .then(Commands.literal("report")
                                .executes(context -> watchStop(context, false)))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(20, 24000))
                                .executes(CTestCommand::watchStart))));
    }

    private static double range(CommandContext<CommandSourceStack> context) {
        return DoubleArgumentType.getDouble(context, "range");
    }

    private static int select(CommandContext<CommandSourceStack> context, double range, int limit) {
        CommandSourceStack source = context.getSource();
        Vec3 origin = source.getPosition();
        double rangeSqr = range * range;

        List<CitizenEntity> found = source.getLevel().getEntitiesOfClass(CitizenEntity.class,
                new AABB(origin, origin).inflate(range),
                citizen -> citizen.isAlive() && citizen.distanceToSqr(origin) <= rangeSqr);
        found.sort(Comparator.comparingDouble(citizen -> citizen.distanceToSqr(origin)));

        List<CitizenEntity> selected = found.size() > limit ? List.copyOf(found.subList(0, limit)) : found;
        CitizenSelectionManager.select(source, selected);

        int count = selected.size();
        if (count == 0) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.select.none"));
        } else {
            source.sendSuccess(() -> Component.translatable("commands.minekingdom.ctest.select.success", count), false);
        }
        return count;
    }

    /** Prints how many blocks each selected citizen has mined, so idle ones stand out. */
    private static int minedReport(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CitizenEntity> citizens = CitizenSelectionManager.resolve(source);
        if (citizens.isEmpty()) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.no_selection"));
            return 0;
        }

        int total = 0;
        int idle = 0;
        int stuck = 0;
        for (CitizenEntity citizen : citizens) {
            int mined = citizen.getMinedBlocks();
            total += mined;
            if (mined == 0) {
                idle++;
            }
            if (citizen.isStuck()) {
                stuck++;
            }
            String line = String.format("mined=%d job=%s task=%s trips=%d/%d stuck=%b pos=%.1f,%.1f,%.1f",
                    mined, citizen.getAssignment().getSerializedName(), citizen.getTask().getSerializedName(),
                    citizen.getReturnArrivals(), citizen.getReturnTrips(), citizen.isStuck(),
                    citizen.getX(), citizen.getY(), citizen.getZ());
            source.sendSuccess(() -> Component.literal(line), false);
        }

        int trips = 0;
        int arrivals = 0;
        for (CitizenEntity citizen : citizens) {
            trips += citizen.getReturnTrips();
            arrivals += citizen.getReturnArrivals();
        }
        String summary = String.format("MINED_SUMMARY total=%d citizens=%d mined_none=%d stuck=%d "
                        + "trips=%d arrived=%d rate=%s",
                total, citizens.size(), idle, stuck, trips, arrivals,
                trips == 0 ? "n/a" : String.format("%.1f%%", 100.0 * arrivals / trips));
        source.sendSuccess(() -> Component.literal(summary), false);
        return total;
    }

    private static int resetMined(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CitizenEntity> citizens = CitizenSelectionManager.resolve(source);
        if (citizens.isEmpty()) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.no_selection"));
            return 0;
        }
        citizens.forEach(CitizenEntity::resetMinedBlocks);
        int count = citizens.size();
        source.sendSuccess(() -> Component.literal("Reset mined counter for " + count + " citizen(s)."), false);
        return count;
    }

    /** Records each selected citizen's current position as the place it must be able to walk back to. */
    private static int setHome(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CitizenEntity> citizens = CitizenSelectionManager.resolve(source);
        if (citizens.isEmpty()) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.no_selection"));
            return 0;
        }

        citizens.forEach(citizen -> citizen.setReturnPoint(citizen.blockPosition()));

        int count = citizens.size();
        source.sendSuccess(() -> Component.translatable("commands.minekingdom.ctest.home.set", count), true);
        return count;
    }

    /**
     * Prints how far each selected citizen is from its return point. Distances are
     * measured from the citizens themselves, so a citizen that never moved shows up
     * as such instead of being counted as a successful return.
     */
    private static int report(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CitizenEntity> citizens = CitizenSelectionManager.resolve(source);
        if (citizens.isEmpty()) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.no_selection"));
            return 0;
        }

        int home = 0;
        int exact = 0;
        int away = 0;
        int unset = 0;
        int placed = 0;
        int made = 0;
        int failed = 0;
        for (CitizenEntity citizen : citizens) {
            double distance = citizen.distanceToReturnPoint();
            if (Double.isNaN(distance)) {
                unset++;
                source.sendSuccess(() -> Component.literal("home=unset task=" + citizen.getTask().getSerializedName()), false);
                continue;
            }
            boolean arrived = distance <= CitizenEntity.RETURN_ARRIVAL_DISTANCE;
            boolean onTheSpot = citizen.isExactlyAtReturnPoint();
            if (arrived) {
                home++;
            } else {
                away++;
            }
            if (onTheSpot) {
                exact++;
            }
            placed += citizen.getReturnBlocksPlaced();
            // How the trip ended, which is not the same as where the citizen is standing
            // now: one that got home goes idle and strolls off, and was being counted as a
            // failure for being 1.8 blocks away half an hour later.
            if ("arrived".equals(citizen.getReturnOutcome())) {
                made++;
            } else if ("gave_up".equals(citizen.getReturnOutcome())) {
                failed++;
            }
            String line = String.format("dist=%.2f %s exact=%b dy=%+d outcome=%s placed=%d task=%s home=%s pos=%.1f,%.1f,%.1f",
                    distance, arrived ? "AT_HOME" : "AWAY", onTheSpot,
                    citizen.blockPosition().getY() - citizen.getReturnPoint().getY(),
                    citizen.getReturnOutcome(), citizen.getReturnBlocksPlaced(),
                    citizen.getTask().getSerializedName(), citizen.getReturnPoint(),
                    citizen.getX(), citizen.getY(), citizen.getZ());
            source.sendSuccess(() -> Component.literal(line), false);
        }

        String summary = String.format("SUMMARY total=%d arrived=%d gave_up=%d at_home=%d exact=%d "
                        + "away=%d unset=%d placed=%d tolerance=%.1f",
                citizens.size(), made, failed, home, exact, away, unset, placed,
                CitizenEntity.RETURN_ARRIVAL_DISTANCE);
        source.sendSuccess(() -> Component.literal(summary), false);
        return home;
    }

    /** Reports what each selected citizen is doing and which goals hold it. */
    private static int debug(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CitizenEntity> citizens = CitizenSelectionManager.resolve(source);
        if (citizens.isEmpty()) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.no_selection"));
            return 0;
        }
        for (CitizenEntity citizen : citizens) {
            BlockPos home = citizen.getReturnPoint();
            String route = home == null ? "home=unset"
                    : CitizenTravel.describeRoute(citizen, home, CitizenEntity.RETURN_ARRIVAL_DISTANCE);
            BlockPos anchor = citizen.getPathMemory().oldestWithin(citizen.blockPosition(), 16, 10);
            String line = "task=" + citizen.getTask().getSerializedName()
                    + " stuck=" + citizen.isStuck()
                    + " at=" + citizen.blockPosition().toShortString()
                    + " anchor=" + (anchor == null ? "none" : anchor.toShortString())
                    + " " + route
                    + " goals=[" + citizen.describeRunningGoals() + "]";
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return citizens.size();
    }

    /** What each selected citizen is doing about getting somewhere, straight from the driver. */
    private static int why(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CitizenEntity> citizens = CitizenSelectionManager.resolve(source);
        if (citizens.isEmpty()) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.no_selection"));
            return 0;
        }
        long now = source.getLevel().getGameTime();
        for (CitizenEntity citizen : citizens) {
            String line = "CZWHY uuid=" + citizen.getStringUUID().substring(0, 8)
                    + " task=" + citizen.getTask().getSerializedName()
                    + " stuck=" + citizen.isStuck()
                    + " at=" + citizen.blockPosition().toShortString()
                    + " walkBanned=" + citizen.isWalkingBanned()
                    + " " + citizen.getJourney().describe(now)
                    + " goals=[" + citizen.describeRunningGoals() + "]";
            source.sendSuccess(() -> Component.literal(line), false);
            MineKingdom.LOGGER.info(line);
        }
        return citizens.size();
    }

    /** Every step out of the nearest selected citizen's square, with the verdict on each. */
    private static int whyBlocked(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CitizenEntity> citizens = CitizenSelectionManager.resolve(source);
        if (citizens.isEmpty()) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.no_selection"));
            return 0;
        }
        // One citizen only: thirteen lines each would bury the answer for a whole shift.
        CitizenEntity citizen = citizens.get(0);
        String header = "CZBLOCK uuid=" + citizen.getStringUUID().substring(0, 8)
                + " at=" + citizen.blockPosition().toShortString();
        source.sendSuccess(() -> Component.literal(header), false);
        MineKingdom.LOGGER.info(header);
        for (String line : CitizenDiagnostics.explainExits(citizen)) {
            String out = "CZBLOCK   " + line;
            source.sendSuccess(() -> Component.literal(out), false);
            MineKingdom.LOGGER.info(out);
        }
        return 1;
    }

    private static int watchStart(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<CitizenEntity> citizens = CitizenSelectionManager.resolve(source);
        if (citizens.isEmpty()) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.no_selection"));
            return 0;
        }
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        CitizenWatch.clear();
        CitizenWatch.start(citizens, ticks);
        int count = citizens.size();
        source.sendSuccess(() -> Component.literal(
                "CZWATCH started n=" + count + " ticks=" + ticks), false);
        return count;
    }

    private static int watchStop(CommandContext<CommandSourceStack> context, boolean clear) {
        CommandSourceStack source = context.getSource();
        if (!CitizenWatch.watching()) {
            source.sendFailure(Component.literal("CZWATCH nothing being watched"));
            return 0;
        }
        List<String> lines = CitizenWatch.report(source.getLevel().getGameTime());
        for (String line : lines) {
            String out = "CZWATCH " + line;
            source.sendSuccess(() -> Component.literal(out), false);
            MineKingdom.LOGGER.info(out);
        }
        if (clear) {
            CitizenWatch.clear();
        }
        return lines.size();
    }

    /** Puts the selection to work, or calls it off. */
    private static int put(CommandContext<CommandSourceStack> context, CitizenAssignment assignment) {
        CommandSourceStack source = context.getSource();
        List<CitizenEntity> citizens = CitizenSelectionManager.resolve(source);
        if (citizens.isEmpty()) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.no_selection"));
            return 0;
        }

        citizens.forEach(citizen -> citizen.setAssignment(assignment));

        int count = citizens.size();
        String key = assignment == CitizenAssignment.NONE
                ? "commands.minekingdom.ctest.mining.stop"
                : "commands.minekingdom.ctest.mining.start";
        source.sendSuccess(() -> Component.translatable(key, count), true);
        return count;
    }

    private static int assign(CommandContext<CommandSourceStack> context, CitizenTask task) {
        CommandSourceStack source = context.getSource();
        List<CitizenEntity> citizens = CitizenSelectionManager.resolve(source);
        if (citizens.isEmpty()) {
            source.sendFailure(Component.translatable("commands.minekingdom.ctest.no_selection"));
            return 0;
        }

        citizens.forEach(citizen -> citizen.setTask(task));

        int count = citizens.size();
        String key = switch (task) {
            case MINING -> "commands.minekingdom.ctest.mining.start";
            case RETURNING -> "commands.minekingdom.ctest.home.return";
            case IDLE -> "commands.minekingdom.ctest.mining.stop";
        };
        source.sendSuccess(() -> Component.translatable(key, count), true);
        return count;
    }
}

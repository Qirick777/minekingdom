package com.minekingdom.command;

import com.minekingdom.entity.CitizenEntity;
import com.minekingdom.entity.task.CitizenTask;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
                                .executes(context -> assign(context, CitizenTask.MINING)))
                        .then(Commands.literal("stop")
                                .executes(context -> assign(context, CitizenTask.IDLE))))
                .then(Commands.literal("home")
                        .then(Commands.literal("set")
                                .executes(CTestCommand::setHome))
                        .then(Commands.literal("return")
                                .executes(context -> assign(context, CitizenTask.RETURNING)))
                        .then(Commands.literal("report")
                                .executes(CTestCommand::report)))
                .then(Commands.literal("debug")
                        .executes(CTestCommand::debug)));
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
        int away = 0;
        int unset = 0;
        for (CitizenEntity citizen : citizens) {
            double distance = citizen.distanceToReturnPoint();
            if (Double.isNaN(distance)) {
                unset++;
                source.sendSuccess(() -> Component.literal("home=unset task=" + citizen.getTask().getSerializedName()), false);
                continue;
            }
            boolean arrived = distance <= CitizenEntity.RETURN_ARRIVAL_DISTANCE;
            if (arrived) {
                home++;
            } else {
                away++;
            }
            String line = String.format("dist=%.2f %s task=%s home=%s pos=%.1f,%.1f,%.1f",
                    distance, arrived ? "AT_HOME" : "AWAY", citizen.getTask().getSerializedName(),
                    citizen.getReturnPoint(), citizen.getX(), citizen.getY(), citizen.getZ());
            source.sendSuccess(() -> Component.literal(line), false);
        }

        String summary = String.format("SUMMARY total=%d at_home=%d away=%d unset=%d tolerance=%.1f",
                citizens.size(), home, away, unset, CitizenEntity.RETURN_ARRIVAL_DISTANCE);
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
            String line = "task=" + citizen.getTask().getSerializedName()
                    + " goals=[" + citizen.describeRunningGoals() + "]";
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return citizens.size();
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

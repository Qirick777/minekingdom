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
        String key = task == CitizenTask.MINING
                ? "commands.minekingdom.ctest.mining.start"
                : "commands.minekingdom.ctest.mining.stop";
        source.sendSuccess(() -> Component.translatable(key, count), true);
        return count;
    }
}

/*
 * This file is derived from the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.monocle.client.commands.commands;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import it.unimi.dsi.fastutil.objects.ObjectIntImmutablePair;
import it.unimi.dsi.fastutil.objects.ObjectIntPair;
import dev.monocle.client.commands.Command;
import dev.monocle.client.commands.arguments.ModuleArgumentType;
import dev.monocle.client.commands.arguments.PlayerArgumentType;
import dev.monocle.client.pathing.PathManagers;
import dev.monocle.client.systems.modules.Module;
import dev.monocle.client.systems.modules.Modules;
import dev.monocle.client.systems.bots.Bots;
import dev.monocle.coordinator.HighwayJobs;
import dev.monocle.client.systems.modules.misc.swarm.SwarmConnection;
import dev.monocle.client.systems.modules.world.InfinityMiner;
import dev.monocle.client.utils.misc.text.MonocleClickEvent;
import dev.monocle.client.utils.player.ChatUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Random;

public class BotCommand extends Command {

    private final static SimpleCommandExceptionType BOTS_NOT_ACTIVE = new SimpleCommandExceptionType(Component.literal("Enable connections in Right Shift → Workers first."));
    private @Nullable ObjectIntPair<String> pendingConnection;

    public BotCommand() {
        super("worker", "Manage worker connections, crews and jobs. Open Right Shift → Workers for the dashboard.", "bot");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder.executes(_ -> {
            dev.monocle.client.gui.tabs.Tabs.get(dev.monocle.client.gui.tabs.builtin.BotsTab.class).openScreen(dev.monocle.client.gui.GuiThemes.get());
            return SINGLE_SUCCESS;
        });
        builder.then(literal("host").executes(_ -> crewAction(() -> {
            Bots bots = Bots.get(); bots.mode.set(Bots.Mode.Host); bots.startHost(); bots.save();
        })));
        builder.then(literal("export-workflow").then(argument("workflow-id", StringArgumentType.word()).executes(context -> crewAction(() ->
            info("Exported workflow and captured gameplay profiles to %s", Bots.get().tasks().exportWorkflow(StringArgumentType.getString(context, "workflow-id")))))));
        builder.then(literal("highway")
            .then(literal("start").then(argument("road-length", IntegerArgumentType.integer(16, HighwayJobs.MAX_LENGTH)).executes(context -> crewAction(() -> Bots.get().startSelectedHighway(IntegerArgumentType.getInteger(context, "road-length"))))))
            .then(literal("status").executes(_ -> crewAction(() -> Bots.get().controlCrew().status())))
            .then(literal("pause").executes(_ -> crewAction(() -> Bots.get().controlCrew().pause())))
            .then(literal("resume").executes(_ -> crewAction(() -> Bots.get().controlCrew().resume())))
            .then(literal("leave").executes(_ -> crewAction(() -> Bots.get().controlCrew().leave())))
            .then(literal("end").then(literal("confirm").executes(_ -> crewAction(() -> Bots.get().endCrewExecution(Bots.get().selectedCrew()))))));
        builder.then(literal("jobs").executes(_ -> crewAction(() -> {
            for (var job : Bots.get().jobs()) info("%s · %s · %s · %d/%d · %s", job.id(), job.name(), job.status(), job.progress(), job.length(), job.unclaimed() ? "Unclaimed" : Bots.get().crewLabel(job.crewId()));
        })));
        var job = argument("job-id", StringArgumentType.word());
        job.then(literal("configure").then(argument("modules-json", StringArgumentType.greedyString()).executes(context -> crewAction(() ->
            Bots.get().tasks().configure(java.util.UUID.fromString(StringArgumentType.getString(context, "job-id")), null,
                com.google.gson.JsonParser.parseString(StringArgumentType.getString(context, "modules-json")).getAsJsonObject())))));
        job.then(literal("pause").executes(context -> crewAction(() -> Bots.get().pauseJob(java.util.UUID.fromString(StringArgumentType.getString(context, "job-id"))))));
        job.then(literal("resume").executes(context -> crewAction(() -> Bots.get().resumeJob(java.util.UUID.fromString(StringArgumentType.getString(context, "job-id"))))));
        job.then(literal("release").then(literal("confirm").executes(context -> crewAction(() -> Bots.get().releaseJob(java.util.UUID.fromString(StringArgumentType.getString(context, "job-id")))))));
        job.then(literal("cancel").then(literal("confirm").executes(context -> crewAction(() -> Bots.get().cancelJob(java.util.UUID.fromString(StringArgumentType.getString(context, "job-id")))))));
        builder.then(literal("job").then(job));
        builder.then(literal("disconnect").executes(_ -> {
            Bots swarm = Bots.get();
            if (swarm.isActive()) {
                if (swarm.mode.get() == Bots.Mode.Worker) swarm.disable();
                else swarm.close();
            } else {
                throw BOTS_NOT_ACTIVE.create();
            }

            return SINGLE_SUCCESS;
        }));

        builder.then(literal("join")
            .then(argument("ip", StringArgumentType.string())
                .then(argument("port", IntegerArgumentType.integer(1, 65535))
                    .executes(context -> {
                        String ip = StringArgumentType.getString(context, "ip");
                        int port = IntegerArgumentType.getInteger(context, "port");

                        pendingConnection = new ObjectIntImmutablePair<>(ip, port);

                        info("Are you sure you want to connect to '%s:%s'?", ip, port);
                        info(Component.literal("Click here to confirm").setStyle(Style.EMPTY
                            .applyFormats(ChatFormatting.UNDERLINE, ChatFormatting.GREEN)
                            .withClickEvent(new MonocleClickEvent(".worker join confirm"))
                        ));

                        return SINGLE_SUCCESS;
                    })
                )
            )
            .then(literal("confirm").executes(_ -> {
                if (pendingConnection == null) {
                    error("No pending worker connections.");
                    return SINGLE_SUCCESS;
                }

                Bots swarm = Bots.get();
                swarm.connectWorker(pendingConnection.left(), pendingConnection.rightInt());
                pendingConnection = null;
                info("Worker connection requested. Workers will authenticate and retry automatically while enabled.");

                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("connections").executes(_ -> {
            Bots swarm = Bots.get();
            if (swarm.isActive()) {
                if (swarm.isHost()) {
                    if (swarm.host.getConnectionCount() > 0) {
                        ChatUtils.info("--- Worker Connections (highlight)(%s/%s)(default) ---", swarm.host.getConnectionCount(), swarm.host.getConnections().length);

                        for (int i = 0; i < swarm.host.getConnections().length; i++) {
                            SwarmConnection connection = swarm.host.getConnections()[i];
                            if (connection != null)
                                ChatUtils.info("(highlight)Worker %s(default): %s.", i, connection.getConnection());
                        }
                    } else {
                        warning("No active connections");
                    }
                } else if (swarm.isWorker()) {
                    info("Connected to (highlight)%s", swarm.worker.getConnection());
                }
            } else {
                throw BOTS_NOT_ACTIVE.create();
            }

            return SINGLE_SUCCESS;
        }));

        builder.then(literal("follow").executes(context -> {
                Bots swarm = Bots.get();
                if (swarm.isActive()) {
                    if (swarm.isHost()) {
                        swarm.sendMessage(context.getInput() + " " + mc.player.getName().getString());
                    } else if (swarm.isWorker()) {
                        error("The follow host command must be used by the host.");
                    }
                } else {
                    throw BOTS_NOT_ACTIVE.create();
                }

                return SINGLE_SUCCESS;
            }).then(argument("player", PlayerArgumentType.create()).executes(context -> {
                Player playerEntity = PlayerArgumentType.get(context);

                Bots swarm = Bots.get();
                if (swarm.isActive()) {
                    if (swarm.isHost()) {
                        swarm.sendMessage(context.getInput());
                    } else if (swarm.isWorker() && playerEntity != null) {
                        PathManagers.get().follow(entity -> entity.getName().getString().equalsIgnoreCase(playerEntity.getName().getString()));
                    }
                } else {
                    throw BOTS_NOT_ACTIVE.create();
                }
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("goto")
            .then(argument("x", IntegerArgumentType.integer())
                .then(argument("z", IntegerArgumentType.integer()).executes(context -> {
                    Bots swarm = Bots.get();
                    if (swarm.isActive()) {
                        if (swarm.isHost()) {
                            swarm.sendMessage(context.getInput());
                        } else if (swarm.isWorker()) {
                            int x = IntegerArgumentType.getInteger(context, "x");
                            int z = IntegerArgumentType.getInteger(context, "z");

                            PathManagers.get().moveTo(new BlockPos(x, 0, z), true);
                        }
                    } else {
                        throw BOTS_NOT_ACTIVE.create();
                    }
                    return SINGLE_SUCCESS;
                }))
            )
        );

        builder.then(literal("infinity-miner").executes(context -> {
                Bots swarm = Bots.get();
                if (swarm.isActive()) {
                    if (swarm.isHost()) {
                        swarm.sendMessage(context.getInput());
                    } else if (swarm.isWorker()) {
                        runInfinityMiner();
                    }
                } else {
                    throw BOTS_NOT_ACTIVE.create();
                }
                return SINGLE_SUCCESS;
            })
            .then(argument("target", BlockStateArgument.block(REGISTRY_ACCESS)).executes(context -> {
                    Bots swarm = Bots.get();
                    if (swarm.isActive()) {
                        if (swarm.isHost()) {
                            swarm.sendMessage(context.getInput());
                        } else if (swarm.isWorker()) {
                            Modules.get().get(InfinityMiner.class).targetBlocks.set(List.of(context.getArgument("target", BlockInput.class).getState().getBlock()));
                            runInfinityMiner();
                        }
                    } else {
                        throw BOTS_NOT_ACTIVE.create();
                    }
                    return SINGLE_SUCCESS;
                })
                .then(argument("repair", BlockStateArgument.block(REGISTRY_ACCESS)).executes(context -> {
                    Bots swarm = Bots.get();
                    if (swarm.isActive()) {
                        if (swarm.isHost()) {
                            swarm.sendMessage(context.getInput());
                        } else if (swarm.isWorker()) {
                            Modules.get().get(InfinityMiner.class).targetBlocks.set(List.of(context.getArgument("target", BlockInput.class).getState().getBlock()));
                            Modules.get().get(InfinityMiner.class).repairBlocks.set(List.of(context.getArgument("repair", BlockInput.class).getState().getBlock()));
                            runInfinityMiner();
                        }
                    } else {
                        throw BOTS_NOT_ACTIVE.create();
                    }
                    return SINGLE_SUCCESS;
                })))
            .then(literal("logout").then(argument("logout", BoolArgumentType.bool()).executes(context -> {
                Bots swarm = Bots.get();
                if (swarm.isActive()) {
                    if (swarm.isHost()) {
                        swarm.sendMessage(context.getInput());
                    } else if (swarm.isWorker()) {
                        Modules.get().get(InfinityMiner.class).logOut.set(BoolArgumentType.getBool(context, "logout"));
                    }
                } else {
                    throw BOTS_NOT_ACTIVE.create();
                }
                return SINGLE_SUCCESS;
            })))
            .then(literal("walkhome").then(argument("walkhome", BoolArgumentType.bool()).executes(context -> {
                Bots swarm = Bots.get();
                if (swarm.isActive()) {
                    if (swarm.isHost()) {
                        swarm.sendMessage(context.getInput());
                    } else if (swarm.isWorker()) {
                        Modules.get().get(InfinityMiner.class).walkHome.set(BoolArgumentType.getBool(context, "walkhome"));
                    }
                } else {
                    throw BOTS_NOT_ACTIVE.create();
                }
                return SINGLE_SUCCESS;
            }))));

        builder.then(literal("mine")
            .then(argument("block", BlockStateArgument.block(REGISTRY_ACCESS)).executes(context -> {
                Bots swarm = Bots.get();
                if (swarm.isActive()) {
                    if (swarm.isHost()) {
                        swarm.sendMessage(context.getInput());
                    } else if (swarm.isWorker()) {
                        swarm.worker.target = context.getArgument("block", BlockInput.class).getState().getBlock();
                    }
                } else {
                    throw BOTS_NOT_ACTIVE.create();
                }
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("toggle")
            .then(argument("module", ModuleArgumentType.create())
                .executes(context -> {
                    Bots swarm = Bots.get();
                    if (swarm.isActive()) {
                        if (swarm.isHost()) {
                            swarm.sendMessage(context.getInput());
                        } else if (swarm.isWorker()) {
                            Module module = ModuleArgumentType.get(context);
                            module.toggle();
                        }
                    } else {
                        throw BOTS_NOT_ACTIVE.create();
                    }
                    return SINGLE_SUCCESS;
                }).then(literal("on")
                    .executes(context -> {
                        Bots swarm = Bots.get();
                        if (swarm.isActive()) {
                            if (swarm.isHost()) {
                                swarm.sendMessage(context.getInput());
                            } else if (swarm.isWorker()) {
                                Module m = ModuleArgumentType.get(context);
                                m.enable();
                            }
                        } else {
                            throw BOTS_NOT_ACTIVE.create();
                        }
                        return SINGLE_SUCCESS;
                    })).then(literal("off")
                    .executes(context -> {
                        Bots swarm = Bots.get();
                        if (swarm.isActive()) {
                            if (swarm.isHost()) {
                                swarm.sendMessage(context.getInput());
                            } else if (swarm.isWorker()) {
                                Module m = ModuleArgumentType.get(context);
                                m.disable();
                            }
                        } else {
                            throw BOTS_NOT_ACTIVE.create();
                        }
                        return SINGLE_SUCCESS;
                    })
                )
            )
        );

        builder.then(literal("scatter").executes(context -> {
            Bots swarm = Bots.get();
            if (swarm.isActive()) {
                if (swarm.isHost()) {
                    swarm.sendMessage(context.getInput());
                } else if (swarm.isWorker()) {
                    scatter(100);
                }
            } else {
                throw BOTS_NOT_ACTIVE.create();
            }
            return SINGLE_SUCCESS;
        }).then(argument("radius", IntegerArgumentType.integer()).executes(context -> {
            Bots swarm = Bots.get();
            if (swarm.isActive()) {
                if (swarm.isHost()) {
                    swarm.sendMessage(context.getInput());
                } else if (swarm.isWorker()) {
                    scatter(IntegerArgumentType.getInteger(context, "radius"));
                }
            } else {
                throw BOTS_NOT_ACTIVE.create();
            }
            return SINGLE_SUCCESS;
        })));

        builder.then(literal("stop").executes(context -> {
            Bots swarm = Bots.get();
            if (swarm.isActive()) {
                if (swarm.isHost()) {
                    swarm.sendMessage(context.getInput());
                } else if (swarm.isWorker()) {
                    PathManagers.get().stop();
                }
            } else {
                throw BOTS_NOT_ACTIVE.create();
            }
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("exec").then(argument("command", StringArgumentType.greedyString()).executes(context -> {
            Bots swarm = Bots.get();
            if (swarm.isActive()) {
                if (swarm.isHost()) {
                    swarm.sendMessage(context.getInput());
                } else if (swarm.isWorker()) {
                    ChatUtils.sendPlayerMsg(StringArgumentType.getString(context, "command"));
                }
            } else {
                throw BOTS_NOT_ACTIVE.create();
            }
            return SINGLE_SUCCESS;
        })));
    }

    private void runInfinityMiner() {
        InfinityMiner infinityMiner = Modules.get().get(InfinityMiner.class);
        infinityMiner.disable();
//        infinityMiner.smartModuleToggle.set(true);
        infinityMiner.enable();
    }

    private int crewAction(Runnable action) {
        try { action.run(); } catch (RuntimeException e) { error("%s", e.getMessage()); }
        return SINGLE_SUCCESS;
    }

    private void scatter(int radius) {
        Random random = new Random();

        double a = random.nextDouble() * 2 * Math.PI;
        double r = radius * Math.sqrt(random.nextDouble());
        double x = mc.player.getX() + r * Math.cos(a);
        double z = mc.player.getZ() + r * Math.sin(a);

        PathManagers.get().stop();
        PathManagers.get().moveTo(new BlockPos((int) x, 0, (int) z), true);
    }
}

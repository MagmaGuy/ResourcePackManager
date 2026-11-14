package com.magmaguy.resourcepackmanager.bedrock.items;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.magmacore.util.ItemComponents;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.resourcepackmanager.bedrock.generic.ItemComponentSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Reads the components of every custom item the installed plugins register, for the Bedrock
 * conversion ({@link ItemComponentSnapshot}).
 *
 * <p>Building and encoding stacks touches plugin and server state, so it runs on the server
 * thread, a few milliseconds per tick: a server with thousands of custom items spreads them
 * over several ticks instead of stalling one. The conversion thread waits for the result. A
 * plugin that is missing, not ready or changed its API loses only its own items; an item that
 * fails to build loses only itself.</p>
 */
public final class ItemComponentSnapshots {

    /** Server-thread time spent per tick. */
    static final long TICK_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(5);
    /** The conversion stops waiting after this and keeps what was read. */
    static final long WAIT_LIMIT_MILLIS = TimeUnit.SECONDS.toMillis(120);

    static final List<CustomItemSource> SOURCES = List.of(
            new CraftEngineItemSource(),
            new NexoItemSource(),
            new OraxenItemSource(),
            new ItemsAdderItemSource(),
            new MythicItemSource());

    private ItemComponentSnapshots() {
    }

    /**
     * @param plugin    RSPM, which owns the server-thread task
     * @param cancelled stops the wait when the conversion is cancelled or the server stops
     * @return every readable custom item's components; empty when no supported plugin is
     *         installed or this server version cannot encode components
     */
    public static List<ItemComponentSnapshot> collect(Plugin plugin, BooleanSupplier cancelled) {
        if (SOURCES.stream().noneMatch(source -> enabled(source.pluginName()) != null)) return List.of();
        if (!ItemComponents.isAvailable(plugin)) return List.of();
        Run run = new Run(plugin);
        if (Bukkit.isPrimaryThread()) {
            run.listAll();
            while (!run.queue.isEmpty()) run.next();
            return run.finish();
        }

        CompletableFuture<List<ItemComponentSnapshot>> result = new CompletableFuture<>();
        BukkitTask task;
        try {
            task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (result.isDone()) return;
                try {
                    if (!run.listed) run.listAll();
                    long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
                    while (!run.queue.isEmpty() && System.nanoTime() < deadline) run.next();
                    if (run.queue.isEmpty()) result.complete(run.finish());
                } catch (RuntimeException | LinkageError failure) {
                    result.completeExceptionally(failure);
                }
            }, 0L, 1L);
        } catch (RuntimeException schedulerClosed) {
            // The plugin is disabling; no server-thread work can run now.
            return List.of();
        }
        long waitUntil = System.currentTimeMillis() + WAIT_LIMIT_MILLIS;
        try {
            while (true) {
                if (cancelled.getAsBoolean()) return List.of();
                try {
                    return result.get(250, TimeUnit.MILLISECONDS);
                } catch (TimeoutException stillReading) {
                    if (System.currentTimeMillis() > waitUntil) {
                        Logger.warn("Reading custom item components for Bedrock took longer than "
                                + TimeUnit.MILLISECONDS.toSeconds(WAIT_LIMIT_MILLIS) + " seconds; converting with the "
                                + run.snapshots.size() + " read so far.");
                        return List.copyOf(run.snapshots);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException failure) {
            Logger.warn("Could not read custom item components for Bedrock: " + failure.getCause());
            return List.of();
        } finally {
            task.cancel();
        }
    }

    private static Plugin enabled(String name) {
        Plugin found = Bukkit.getPluginManager().getPlugin(name);
        return found != null && found.isEnabled() ? found : null;
    }

    /** One collection pass. Touched only on the server thread, except the final copy. */
    private static final class Run {
        private final Plugin plugin;
        private final Deque<Pending> queue = new ArrayDeque<>();
        private final List<ItemComponentSnapshot> snapshots = java.util.Collections.synchronizedList(new ArrayList<>());
        private final Map<Material, JsonObject> prototypes = new HashMap<>();
        private final Map<String, Integer> failedBuilds = new HashMap<>();
        private boolean listed;

        Run(Plugin plugin) {
            this.plugin = plugin;
        }

        void listAll() {
            listed = true;
            for (CustomItemSource source : SOURCES) {
                Plugin owner = enabled(source.pluginName());
                if (owner == null) continue;
                try {
                    for (CustomItemSource.Item item : source.list(owner.getClass().getClassLoader())) {
                        queue.add(new Pending(source.pluginName(), item));
                    }
                } catch (ReflectiveOperationException | LinkageError | RuntimeException unreadable) {
                    Logger.warn("Could not list " + source.pluginName() + " items for the Bedrock conversion: "
                            + unreadable);
                }
            }
        }

        void next() {
            Pending pending = queue.poll();
            if (pending == null) return;
            try {
                Object built = pending.item().build().call();
                if (!(built instanceof ItemStack stack) || stack.getType().isAir()) return;
                Optional<String> patch = ItemComponents.json(plugin, stack, true);
                if (patch.isEmpty()) return;
                JsonObject prototype = prototypes.computeIfAbsent(stack.getType(), material ->
                        ItemComponents.json(plugin, new ItemStack(material), false)
                                .map(json -> JsonParser.parseString(json).getAsJsonObject())
                                .orElseGet(JsonObject::new));
                snapshots.add(new ItemComponentSnapshot(pending.source(), pending.item().id(),
                        stack.getType().getKey().toString(),
                        JsonParser.parseString(patch.get()).getAsJsonObject(), prototype));
            } catch (Exception | LinkageError failure) {
                failedBuilds.merge(pending.source(), 1, Integer::sum);
            }
        }

        List<ItemComponentSnapshot> finish() {
            failedBuilds.forEach((source, count) -> Logger.warn("Could not build " + count + " " + source
                    + " item(s) to read their components for the Bedrock conversion; they keep their base item's."));
            return List.copyOf(snapshots);
        }
    }

    private record Pending(String source, CustomItemSource.Item item) {
    }
}

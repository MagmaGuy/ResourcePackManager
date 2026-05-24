package com.magmaguy.rspm.proxy;

/**
 * Platform-neutral scheduler facade. Abstracts away Velocity's
 * {@code com.velocitypowered.api.scheduler.Scheduler} and BungeeCord's
 * {@code net.md_5.bungee.api.scheduler.TaskScheduler} so that {@link NetworkSync}
 * can run on either proxy without taking a hard dependency on either API.
 *
 * <p>Both flavors of scheduled async task should run sequentially (one invocation
 * at a time, on a worker thread). {@link NetworkSync#pollOnce()} relies on this
 * non-overlap guarantee so it doesn't need internal synchronization. If a poll
 * happens to outrun the configured interval, behavior is platform-dependent —
 * acceptable for this use case.</p>
 */
public interface ProxySchedulerAdapter {

    /** Run task once, async (off the main thread). */
    void runAsync(Runnable task);

    /**
     * Schedule a repeating async task. Returns a {@link Cancellable} so
     * {@link NetworkSync#stop()} can cancel it cleanly on proxy shutdown.
     */
    Cancellable scheduleRepeating(Runnable task, long initialDelayMillis, long intervalMillis);

    interface Cancellable {
        void cancel();
    }
}

package com.magmaguy.resourcepackmanager.proxy;

/**
 * Platform-neutral scheduler facade. Abstracts away Velocity's
 * {@code com.velocitypowered.api.scheduler.Scheduler} and BungeeCord's
 * {@code net.md_5.bungee.api.scheduler.TaskScheduler} so that {@link NetworkSync}
 * can run on either proxy without taking a hard dependency on either API.
 *
 * <p>No non-overlap guarantee is required from the platform scheduler:
 * {@link NetworkSync} self-guards overlapping polls via its own CAS and warns
 * when a poll overruns the configured interval.</p>
 */
public interface ProxySchedulerAdapter {

    /**
     * Schedule a repeating async task. Returns a {@link Cancellable} so
     * {@link NetworkSync#stop()} can cancel it cleanly on proxy shutdown.
     */
    Cancellable scheduleRepeating(Runnable task, long initialDelayMillis, long intervalMillis);

    interface Cancellable {
        void cancel();
    }
}

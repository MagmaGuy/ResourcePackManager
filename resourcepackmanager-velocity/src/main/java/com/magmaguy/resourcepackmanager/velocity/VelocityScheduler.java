package com.magmaguy.resourcepackmanager.velocity;

import com.magmaguy.resourcepackmanager.proxy.ProxySchedulerAdapter;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.util.concurrent.TimeUnit;

final class VelocityScheduler implements ProxySchedulerAdapter {
    private final Object pluginHandle;
    private final ProxyServer proxy;

    VelocityScheduler(Object pluginHandle, ProxyServer proxy) {
        this.pluginHandle = pluginHandle;
        this.proxy = proxy;
    }

    @Override
    public Cancellable scheduleRepeating(Runnable task, long initialDelayMillis, long intervalMillis) {
        ScheduledTask t = proxy.getScheduler().buildTask(pluginHandle, task)
                .delay(initialDelayMillis, TimeUnit.MILLISECONDS)
                .repeat(intervalMillis, TimeUnit.MILLISECONDS)
                .schedule();
        return t::cancel;
    }
}

package com.magmaguy.rspm.bungee;

import com.magmaguy.rspm.proxy.ProxySchedulerAdapter;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;

import java.util.concurrent.TimeUnit;

final class BungeeScheduler implements ProxySchedulerAdapter {
    private final Plugin plugin;

    BungeeScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runAsync(Runnable task) {
        plugin.getProxy().getScheduler().runAsync(plugin, task);
    }

    @Override
    public Cancellable scheduleRepeating(Runnable task, long initialDelayMillis, long intervalMillis) {
        ScheduledTask t = plugin.getProxy().getScheduler().schedule(
                plugin, task, initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS);
        return t::cancel;
    }
}

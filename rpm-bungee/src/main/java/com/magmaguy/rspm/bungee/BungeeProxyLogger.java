package com.magmaguy.rspm.bungee;

import com.magmaguy.rspm.proxy.ProxyLogger;

import java.util.logging.Level;
import java.util.logging.Logger;

final class BungeeProxyLogger implements ProxyLogger {
    private final Logger jul;

    BungeeProxyLogger(Logger jul) {
        this.jul = jul;
    }

    @Override
    public void info(String message) {
        jul.info(message);
    }

    @Override
    public void warn(String message) {
        jul.warning(message);
    }

    @Override
    public void warn(String message, Throwable t) {
        jul.log(Level.WARNING, message, t);
    }
}

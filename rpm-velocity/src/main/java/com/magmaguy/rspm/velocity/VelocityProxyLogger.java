package com.magmaguy.rspm.velocity;

import com.magmaguy.rspm.proxy.ProxyLogger;
import org.slf4j.Logger;

final class VelocityProxyLogger implements ProxyLogger {
    private final Logger slf4j;

    VelocityProxyLogger(Logger slf4j) {
        this.slf4j = slf4j;
    }

    @Override
    public void info(String message) {
        slf4j.info(message);
    }

    @Override
    public void warn(String message) {
        slf4j.warn(message);
    }

    @Override
    public void warn(String message, Throwable t) {
        slf4j.warn(message, t);
    }
}

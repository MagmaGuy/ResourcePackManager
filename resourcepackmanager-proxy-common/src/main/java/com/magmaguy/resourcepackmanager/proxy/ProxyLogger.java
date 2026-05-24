package com.magmaguy.resourcepackmanager.proxy;

/**
 * Platform-neutral logger facade used by {@link NetworkSync} and friends so the
 * proxy orchestration code doesn't depend on Velocity's SLF4J logger or
 * BungeeCord's {@code java.util.logging} logger directly.
 *
 * <p>Velocity implementations will typically delegate to SLF4J / Velocity's
 * plugin logger; BungeeCord implementations will delegate to
 * {@code Plugin#getLogger()} ({@code java.util.logging}).</p>
 */
public interface ProxyLogger {
    void info(String message);

    void warn(String message);

    void warn(String message, Throwable t);
}

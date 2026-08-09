package com.magmaguy.resourcepackmanager.proxy;

import java.nio.file.Path;

/**
 * Offline/prelaunch fallback for platforms (notably Windows) which lock the
 * proxy plugin JAR while it is loaded. Run this class from a temporary COPY of
 * ResourcePackManager.jar before starting the proxy.
 */
public final class ProxyPluginUpdateApplier {
    private ProxyPluginUpdateApplier() {
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: ProxyPluginUpdateApplier <work-dir> <proxy-plugin-jar> <geyser-plugin-dir-or->");
            System.exit(2);
        }
        Path geyser = "-".equals(args[2]) ? null : Path.of(args[2]);
        ProxyLogger logger = new ProxyLogger() {
            @Override
            public void info(String message) {
                System.out.println("[RSPM] " + message);
            }

            @Override
            public void warn(String message) {
                System.err.println("[RSPM] " + message);
            }

            @Override
            public void warn(String message, Throwable throwable) {
                System.err.println("[RSPM] " + message);
                throwable.printStackTrace(System.err);
            }
        };
        boolean applied = ProxyPluginUpdateCoordinator.applyPending(
                Path.of(args[0]), Path.of(args[1]), geyser, logger);
        System.exit(applied ? 0 : 1);
    }
}

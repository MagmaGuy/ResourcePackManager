package com.magmaguy.resourcepackmanager.proxy;

/**
 * Conditional debug logging for the proxy-side Bedrock pack delivery pipeline.
 *
 * <p>Toggleable diagnostic for "Bedrock player connected, got a pack URL, but
 * something's wrong with the pack contents / Geyser-mapping handshake."
 * Complements FMM's per-bone display logging on the other side of the wire —
 * together they let an operator grep "what did the proxy announce" + "what
 * did FMM try to display" for a single Bedrock session and see both halves.</p>
 *
 * <p>Off by default. Toggled at runtime via {@code /rspm debug bedrock on|off}
 * on either proxy (Velocity or Bungee); {@link GeyserBinder#onSession} consults
 * this flag to decide whether to emit per-session log lines. The state is not
 * persisted anywhere and resets to off on proxy restart.</p>
 *
 * <p>Static-mutable on purpose — wiring this through a constructor chain
 * (config → plugin → NetworkSync → GeyserBinder) would force every existing
 * call site to thread an extra argument for what is a debug-only feature.
 * Threading: the field is volatile, toggled rarely from command threads, read
 * on every Bedrock session load.</p>
 */
public final class BedrockDeliveryDebugLog {

    private static volatile boolean enabled = false;

    private BedrockDeliveryDebugLog() {}

    private static void setEnabled(boolean v) {
        enabled = v;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Shared logic for the {@code /rspm debug bedrock [on|off]} subcommand on
     * both proxies. Parses {@code args} ({@code args[0]} = "debug",
     * {@code args[1]} = expected "bedrock", {@code args[2]} = optional on/off),
     * applies the toggle when a state is supplied, and returns the message the
     * platform command should send to the sender.
     */
    public static String handleToggleCommand(String[] args) {
        if (args.length < 2 || !"bedrock".equalsIgnoreCase(args[1])) {
            return "Usage: /rspm debug bedrock [on|off] — currently only the 'bedrock' subsystem is supported.";
        }
        if (args.length < 3) {
            boolean cur = isEnabled();
            return "[RSPM] Bedrock delivery debug logging is currently "
                    + (cur ? "ON" : "OFF")
                    + ". Use /rspm debug bedrock on|off to change.";
        }
        boolean target;
        switch (args[2].toLowerCase()) {
            case "on", "true", "enable", "enabled" -> target = true;
            case "off", "false", "disable", "disabled" -> target = false;
            default -> {
                return "Unknown state '" + args[2] + "'. Expected 'on' or 'off'.";
            }
        }
        setEnabled(target);
        return "[RSPM] Bedrock delivery debug logging is now "
                + (target ? "ON" : "OFF")
                + ". Log lines prefixed with [RSPM-BedrockDebug]. "
                + (target
                    ? "Reproduce the issue then turn this OFF."
                    : "");
    }
}

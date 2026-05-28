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
 * <p>Off by default. The plugin entrypoint (Velocity / Bungee) reads the
 * platform's config flag at startup and calls {@link #setEnabled(boolean)};
 * after that, {@link GeyserBinder#onSession} consults this flag to decide
 * whether to emit per-session log lines.</p>
 *
 * <p>Static-mutable on purpose — wiring this through a constructor chain
 * (config → plugin → NetworkSync → GeyserBinder) would force every existing
 * call site to thread an extra argument for what is a debug-only feature.
 * Threading: the field is volatile, set once at startup, read on every
 * Bedrock session load. Reload semantics: a {@code /rspm reload} that
 * rebuilds the plugin will re-call {@link #setEnabled(boolean)} with the
 * fresh config value.</p>
 */
public final class BedrockDeliveryDebugLog {

    private static volatile boolean enabled = false;

    private BedrockDeliveryDebugLog() {}

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    public static boolean isEnabled() {
        return enabled;
    }
}

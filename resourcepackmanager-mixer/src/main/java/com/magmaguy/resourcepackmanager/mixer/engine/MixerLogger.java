package com.magmaguy.resourcepackmanager.mixer.engine;

public interface MixerLogger {
    /** Discards every message. */
    MixerLogger SILENT = new MixerLogger() {
        @Override public void info(String message) { }
        @Override public void warn(String message) { }
        @Override public void collision(String message) { }
    };

    void info(String message);
    void warn(String message);
    void collision(String message);
}

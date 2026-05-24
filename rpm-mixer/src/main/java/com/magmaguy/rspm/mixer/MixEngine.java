package com.magmaguy.rspm.mixer;

import java.io.IOException;

public final class MixEngine {
    private final MixerLogger logger;

    public MixEngine(MixerLogger logger) {
        this.logger = logger;
    }

    public MixOutput run(MixInput input) throws IOException {
        throw new UnsupportedOperationException("MixEngine.run not yet implemented — see Task 1.3");
    }
}

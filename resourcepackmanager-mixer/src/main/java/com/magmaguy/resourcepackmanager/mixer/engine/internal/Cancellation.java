package com.magmaguy.resourcepackmanager.mixer.engine.internal;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Shared cooperative-cancellation helpers for the mixing / conversion pipeline.
 *
 * <p>Cancellation is polled ({@link BooleanSupplier}) rather than interrupt-driven,
 * but a thread interrupt is honored too. The interrupt status is deliberately
 * preserved ({@code isInterrupted()}, never {@code interrupted()}) so outer layers
 * still observe it.</p>
 */
public final class Cancellation {
    private Cancellation() {
    }

    public static boolean isCancelled(BooleanSupplier cancellationRequested) {
        return Thread.currentThread().isInterrupted()
                || (cancellationRequested != null && cancellationRequested.getAsBoolean());
    }

    public static void check(BooleanSupplier cancellationRequested, String message) {
        if (isCancelled(cancellationRequested)) {
            throw new CancellationException(message);
        }
    }

    /**
     * Byte-for-byte file comparison with a size short-circuit, polling for
     * cancellation between reads.
     */
    public static boolean contentEquals(Path first, Path second,
                                        BooleanSupplier cancellationRequested,
                                        String cancellationMessage) throws IOException {
        if (Files.size(first) != Files.size(second)) return false;
        byte[] firstBuffer = new byte[64 * 1024];
        byte[] secondBuffer = new byte[64 * 1024];
        try (InputStream firstInput = new BufferedInputStream(Files.newInputStream(first));
             InputStream secondInput = new BufferedInputStream(Files.newInputStream(second))) {
            while (true) {
                check(cancellationRequested, cancellationMessage);
                // readNBytes fills the buffer (short only at EOF) — a plain
                // read() may legally return different chunk sizes for the two
                // streams, which would misreport equal files as different.
                int firstRead = firstInput.readNBytes(firstBuffer, 0, firstBuffer.length);
                int secondRead = secondInput.readNBytes(secondBuffer, 0, secondBuffer.length);
                if (firstRead != secondRead) return false;
                if (firstRead == 0) return true;
                if (!java.util.Arrays.equals(firstBuffer, 0, firstRead, secondBuffer, 0, firstRead)) {
                    return false;
                }
            }
        }
    }
}

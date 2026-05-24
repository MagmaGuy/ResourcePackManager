package com.magmaguy.resourcepackmanager.http;

/**
 * Structured representation of an error response returned by the magmaguy.com
 * resource-pack hosting API. Mirrors the JSON shape used by the existing
 * {@code AutoHost.handleErrorResponse(...)} flow.
 *
 * <p>Known error codes include: {@code MISSING_REQUIRED_FILES},
 * {@code FILE_TOO_LARGE}, {@code INVALID_FILE_FORMAT}, {@code SESSION_NOT_FOUND},
 * {@code SERVER_UNAVAILABLE}. The {@code type} field carries the broader
 * category (e.g. {@code validation_error}).</p>
 */
public record RspError(
        String code,
        String type,
        String message,
        int httpStatus
) {
}

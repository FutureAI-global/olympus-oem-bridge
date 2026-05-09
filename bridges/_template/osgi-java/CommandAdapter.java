// MARK: 1 — replace this package with your OEM-specific path.
package com.example.oem.bridge.layer2;

import java.util.Map;

/**
 * Interface every command adapter implements. One adapter per OEM
 * command you want to expose via HTTP. The HTTP server's Router
 * dispatches by {@code publicName} → adapter → invocation.
 *
 * <p>Pattern:
 *   1. {@link #publicName} — the URL segment after {@code /commands/}.
 *      Use camelCase to match the OEM tool's existing internal naming.
 *   2. {@link #commandClass} — informational. The OEM-internal class
 *      name your adapter delegates to. Helps debugging when log lines
 *      reference the underlying class.
 *   3. {@link #argShape} — JSON arg schema as human-readable text. The
 *      `/commands` introspection endpoint returns this so external
 *      callers can self-document.
 *   4. {@link #invoke} — actually run the command. Receives the parsed
 *      args map + the bundle's command service handle. Returns whatever
 *      the OEM command produces (the HTTP server handles JSON encoding).
 */
public interface CommandAdapter {
    String publicName();
    String commandClass();
    Map<String, String> argShape();

    /**
     * Run the command. Args are pre-parsed from JSON; commandService is
     * the handle Activator captured at startup. Return value is JSON-
     * encoded by the caller. Returning null surfaces as
     * {@code result:null} in the HTTP response — see the receiver-pattern
     * gotcha in {@code docs/ADDING_AN_OEM.md} for when this happens
     * unexpectedly and how to recover.
     *
     * @throws Exception any OEM-internal exception. The Router catches
     *         it and surfaces as {@code kind:"invocation_error"}.
     */
    Object invoke(Object commandService, Map<String, Object> args) throws Exception;
}

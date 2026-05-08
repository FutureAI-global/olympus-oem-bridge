# Security policy

## Reporting a vulnerability

This repo contains code that runs inside vehicle diagnostic tools and dispatches commands to a vehicle's CAN/UDS bus. A vulnerability here can affect real vehicles.

**Do not file public issues for security vulnerabilities.** Instead:

1. Email **security@futureai.com** with the subject `[oem-bridge security] <short summary>`.
2. Include: affected component (bridge / MCP), reproduction steps, suspected impact, and your suggested fix if you have one.
3. We acknowledge within 72 hours and will work with you on a coordinated disclosure timeline.

## Scope

| Component | In scope |
|---|---|
| `bridges/<oem>/` JAR / DLL / module | Yes — anything that affects the bridge's allowlist enforcement, command execution, HTTP server, or interaction with the OEM tool's runtime |
| `mcp/` MCP server | Yes — anything that affects MCP tool dispatch or stdio framing |
| `docs/` | No — but please file an issue if docs are misleading in a security-relevant way |

## Threat model summary

The bridge listens only on `127.0.0.1:18082`. Threats we care about:

1. **Allowlist bypass** — a way to invoke a non-allowlisted command via the bridge.
2. **Argument injection** — args that escape the OEM tool's command validation and trigger unintended bus operations.
3. **Local privilege escalation** — using the bridge to gain access the calling process didn't have.
4. **OEM tool exploitation** — using the bridge as a vector to compromise the OEM tool itself.
5. **Vehicle bus mutation** — a way to issue write/active-test commands when the allowlist is read-only.

Threats out of scope (because they're already addressed by the localhost binding):
- Network-borne attacks (the bridge is unreachable from the network)
- DoS against the bridge from local processes (the OEM tool itself can do this; not interesting)

## Safe-disclosure timeline

| Timeframe | Step |
|---|---|
| Day 0 | Report received via security@futureai.com |
| Within 72h | Acknowledgment + initial triage |
| Within 14 days | Fix landed in private branch + reviewed |
| Within 30 days | Fix merged to public main + advisory published |
| 90 days max | Full disclosure if maintainer is unresponsive (please give us this much) |

We follow [GitHub Security Advisories](https://github.com/FutureAI-global/olympus-oem-bridge/security/advisories) for tracking and credit.

## What this repo is NOT a vulnerability vector for

- Driving a vehicle without authorization. The bridge requires the OEM tool to be running, which requires the tool's licensing + the technician to have plugged in a J2534 / VCM / pass-thru cable. No remote attacker can invoke commands without local-machine access AND a connected vehicle.
- Bypassing OEM DRM. The bridge calls public-or-internal commands the tool already exposes; it does not crack protected functionality.

## Out-of-band contact for severe issues

For severe issues affecting connected vehicles (e.g. an allowlist that lets a remote process write to engine calibration), email **security@futureai.com** AND signal the urgency in the subject: `[URGENT oem-bridge security]`. We pager-page on those.

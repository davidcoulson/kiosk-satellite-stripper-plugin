# WebSocket Stripper status for Kiosk Satellite

Reports what the [HA WebSocket Stripper](https://github.com/davidcoulson/HA-Websocket-Stripper)
is doing in front of this panel: whether it is in the path at all, what it is
trimming, how many entities this panel is served, and how much traffic it was
spared.

Read-only. Nothing here changes how the panel behaves.

## What it shows

A **status tile on the Overview** — the one line worth having:

```
WebSocket Stripper    88 entities · 71% not sent · office-tablet
```

or `Not behind the trimmer` when Home Assistant answered directly, which is a
different thing from the panel being unable to reach anything at all.

And the numbers as **Home Assistant entities**: entities served, the not-sent
percentage and byte totals, first-payload timing, connection count, the
Stripper's version and uptime, which dashboard this panel was attributed to
and how, and a text sensor listing what is being trimmed.

## Detection

The plugin asks the panel's own Home Assistant URL for `/stripper/client.json`.
That path is served by the proxy and never forwarded, so the reply is the
answer:

| Result | Meaning |
| --- | --- |
| `200` | The Stripper is running **and in the path** for this panel |
| `404` | Home Assistant answered directly. No proxy in front of this panel |
| No answer | Neither was reachable — a network fault, reported as its own state |

A fourth state matters and is shown separately: `connections: 0` means the
proxy *is* in the path but holds no websocket from this panel's address,
usually because the panel has not opened one yet.

## Settings

| Setting | Default | Notes |
| --- | --- | --- |
| Stripper base URL | empty | Empty uses the panel's own Home Assistant URL, which is normally right. The port is never assumed |
| Refresh interval | 120s | The endpoint takes a stats snapshot per call, so this is deliberately slow. `0` reads only on demand |
| Request timeout | 2000ms | A diagnostics read must not hang when the proxy is busy |

The API asks callers not to poll it. A plugin publishing Home Assistant
entities cannot honour that literally — an entity nobody refreshes is an
entity that lies — so the compromise is a slow default, `0` to disable
polling entirely, and a **Refresh now** command for when someone is looking.

## What it deliberately does not do

The endpoint is unauthenticated and readable by anything on the network. Its
own documentation says to treat it as advisory and not to build anything
security-sensitive on it. So:

- Nothing here gates panel behaviour on what it finds. It renders, and that is all.
- Nulls are published as unknown, never as `0`. Every `client` field can be
  null, and null means "not known" — a `0` would land in a Home Assistant
  long-term statistic as though someone had measured it.
- `update_bytes_per_min` stays null until the API is willing to report it.
  A rate extrapolated from four seconds is an opening burst multiplied by
  fifteen, and publishing it as a number would launder a disclaimer into a
  data point.
- The not-sent percentage is presented as *what this connection was spared*,
  not as the Stripper's headline savings figure. The API is explicit that they
  are computed differently and should not be shown as the same number.

## Requirements

Kiosk Satellite with plugin SDK 1. No root, no Shizuku. Capabilities used are
`host.read` (to learn the panel's Home Assistant URL) and `entities`.

The Stripper must be on `2026.09.15.21` or newer — that is the build the
status endpoint was added in.

## Licence

Apache-2.0.

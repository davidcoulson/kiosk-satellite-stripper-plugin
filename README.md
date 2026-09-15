# WebSocket Stripper status for Kiosk Satellite

Reports what the [HA WebSocket Stripper](https://github.com/davidcoulson/HA-Websocket-Stripper)
is doing in front of this panel: whether it is in the path at all, what it is
trimming, how many entities this panel is served, and how much traffic it was
spared.

Read-only. Nothing here changes how the panel behaves.

## What it shows

A **status tile on the Overview** — the one line worth having:

```
WebSocket Stripper    80% dropped · 214 entities
```

It shares a narrow row with "Validated" and "Entities and BT proxy" and elides
after roughly thirty characters, so it leads with the figure that justifies the
proxy existing: how much of Home Assistant's traffic never had to cross to this
panel. A panel with no proxy in front of it reads `Disabled` — which is a
different thing from the panel being unable to reach anything at all.

And the numbers as **Home Assistant entities**: entities served, the not-sent
percentage, the three traffic totals (data from Home Assistant, data trimmed,
data forwarded), first-payload timing, connection count, the Stripper's version
and uptime, which dashboard this panel was attributed to and how, and a text
sensor listing what is being trimmed.

Byte totals are published in bytes and time spans in seconds, declared as
`data_size` and `duration`, and the host renders them at a readable scale —
`12.9 MB`, `7m 8s`. The sensors stay in base units deliberately: a statistic
whose unit slides from KB to MB as the number grows is a broken statistic.

## Detection

The plugin asks the panel's own Home Assistant URL for `/stripper/client.json`.
That path is served by the proxy and never forwarded, so the reply is the
answer:

| Result | Meaning |
| --- | --- |
| `200` | The Stripper is running **and in the path** for this panel |
| `401` | In the path, but the access token is missing or was not accepted |
| `403` | In the path, but it does not answer callers from here |
| `404` | Home Assistant answered directly. No proxy in front of this panel |
| No answer | Neither was reachable — a network fault, reported as its own state |

`401` and `403` are refusals from the proxy itself, so both still prove it is in
front of this panel. Reporting either as "no proxy here" would send someone
looking in the wrong place.

A fourth state matters and is shown separately: `connections: 0` means the
proxy *is* in the path but holds no websocket from this panel's address,
usually because the panel has not opened one yet.

## Settings

| Setting | Default | Notes |
| --- | --- | --- |
| Stripper base URL | empty | Empty uses the panel's own Home Assistant URL, which is normally right. The port is never assumed |
| Access token | empty | Required from Stripper `2026.09.15.25`, which answers `401` without one. A long-lived access token from your Home Assistant profile; Kiosk Satellite masks it on the settings row |
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

The Stripper must be on `2026.09.15.21` or newer — that is the build the status
endpoint was added in. From `2026.09.15.25` the endpoint also requires a Home
Assistant access token, set above.

The token is a plugin setting rather than the panel's own: Kiosk Satellite does
not hand a plugin the token it uses for Home Assistant, which is the right call
— a diagnostics reader has no business holding the credential that can drive the
house.

## Licence

Apache-2.0.

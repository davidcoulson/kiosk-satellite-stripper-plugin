# Changelog

## 0.2.0

The status endpoint requires a Home Assistant access token from Stripper build
`2026.09.15.25` onward. Without one it answers `401`, which 0.1.0 reported as
"The Stripper answered with something unexpected" — in the path, trimming
normally, and the plugin unable to say so.

- **Access token setting.** Sent as `Authorization: Bearer`. Paste a long-lived
  access token from your Home Assistant profile. Leave it empty for a Stripper
  older than `2026.09.15.25`, which needs none. Kiosk Satellite masks it on the
  settings row.
- **`401` and `403` are their own states, and both are positive detections.**
  The reply came from the proxy, so the trimmer *is* in front of this panel; it
  just will not say what it is doing. Reporting either as "no proxy here" sends
  someone looking in the wrong place. A `403` keeps the proxy's own reason ("local
  callers only") for the plugin page rather than the tile.
- **Shorter tile text, leading with the figure that matters.** The tile shares a
  narrow row with "Validated" and elides after roughly thirty characters, so it
  now reads `80% dropped · 214 entities` rather than opening with the entity
  count and losing the percentage to the ellipsis. A panel with no proxy in front
  of it reads `Disabled`, not `Not behind the trimmer`.
- The byte totals, dashboard attribution and trim flags are unchanged and still
  on the plugin's own page, where there is room for them.

## 0.1.0

First release.

- Reads `/stripper/client.json` from the panel's own Home Assistant URL and reports what the HA WebSocket Stripper is doing in front of this panel.
- Status tile on the Overview: entities served, the not-sent percentage and the attributed dashboard, or "Not behind the trimmer" on a 404.
- Home Assistant entities for the figures: entities served, not-sent percentage and bytes, traffic totals, first-payload timing, connection count, Stripper version and uptime, attribution, and a text sensor listing what is trimmed.
- Four states kept distinct: trimming, in-path-but-idle (`connections: 0`), talking to Home Assistant directly, and unreachable. The last two are different problems and read differently.
- Nulls are published as unknown rather than zero, and `update_bytes_per_min` stays null until the API is willing to report a real rate.

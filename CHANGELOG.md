# Changelog

## 0.1.0

First release.

- Reads `/stripper/client.json` from the panel's own Home Assistant URL and reports what the HA WebSocket Stripper is doing in front of this panel.
- Status tile on the Overview: entities served, the not-sent percentage and the attributed dashboard, or "Not behind the trimmer" on a 404.
- Home Assistant entities for the figures: entities served, not-sent percentage and bytes, traffic totals, first-payload timing, connection count, Stripper version and uptime, attribution, and a text sensor listing what is trimmed.
- Four states kept distinct: trimming, in-path-but-idle (`connections: 0`), talking to Home Assistant directly, and unreachable. The last two are different problems and read differently.
- Nulls are published as unknown rather than zero, and `update_bytes_per_min` stays null until the API is willing to report a real rate.

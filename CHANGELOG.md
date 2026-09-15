# Changelog

## 0.3.1

- **What is being trimmed reads as a list, in words.** It was a
  comma-separated run of API keys that wrapped across three ragged
  right-aligned lines — `entities, by_dashboard, registries, resources,
  extra_modules, ...` — and had to be read rather than scanned. Now one per
  line, and `extra_modules` reads as "Extra modules": that is the payload's
  spelling, not a word for whoever is standing at the panel. The conversion is
  mechanical rather than a lookup table, so a flag added to the API later reads
  sensibly here without a release.

## 0.3.0

Readable figures. **Renames three entities** — see the note at the end.

- **Byte totals are declared as `data_size` and time spans as `duration`,** so
  the host renders them at a human scale: `12.9 MB` rather than `13483830 B`,
  `7m 8s` rather than `428 s`. The values published are unchanged and still in
  base units, which is the point — a Home Assistant statistic whose unit slides
  from KB to MB as the number grows is a broken statistic, so the rescaling
  belongs in the display, not the sensor. Needs a Kiosk Satellite build that
  understands those device classes; an older host shows the raw figure as before.
- The throughput rate keeps its denominator: `9.1 KB/min`, never `9.1 KB`.
- **The three traffic totals are renamed and reordered** to read in the order
  they happen, so that `from = trimmed + forwarded` is obvious instead of
  something to work out from three names that each described the same bytes
  differently:

  | Was | Now |
  | --- | --- |
  | Bytes from Home Assistant | **Data from Home Assistant** |
  | Bytes not sent | **Data trimmed** |
  | Bytes to this panel | **Data forwarded** |

  Home Assistant derives an entity id from the name, so these arrive as new
  entities and the three old ones are left behind as unavailable. Delete them
  from Settings → Devices & services → Entities; any history on them does not
  carry over.

## 0.2.1

- **The state sensor now agrees with the tile.** 0.2.0 gave `401` and `403`
  their own tile wording but left the `Stripper state` text sensor folding both
  into `error`, so the panel read "Needs an access token" while an automation
  watching the entity saw something break. The states are now `trimming`,
  `in path, idle`, `direct`, `needs a token`, `refused`, `unreachable` and
  `error`, and `error` means only a genuinely unexpected reply.
- The trim list is null rather than empty on a refusal: the proxy did not say
  what it is cutting, which is not the same as cutting nothing.

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

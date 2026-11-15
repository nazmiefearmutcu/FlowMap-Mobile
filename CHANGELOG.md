# Changelog

## 1.0.1 — 2026-11-15

### Fixed
- **Navigation never changes a value.** You reported Liq Map numbers changing while dragging; this release removes that whole class of problem:
  - Liq Map buckets are fixed groups of price rows, and their scales come from a fixed band around the current price.
  - Heatmap cells are combined into fixed blocks that depend only on the zoom level.
  - The inspection crosshair is pinned to a time and price, so its tooltip keeps reporting the same cell while you pan.
  - Price auto-fit no longer runs during a pan. It applies on first view, on double-tap, and to new live data at the live edge.
  - A two-finger drag only pans until the fingers clearly spread or pinch.
- **Live order book recording is lossless.** Every column keeps its own price window, so a large move never drops or shifts recorded liquidity. The colour scale is learnt in the first minute and then held.
- **Liq Map:** cumulative curves split the bucket that holds the current price row by row. The top bucket shows only prices that exist. Bars beyond the scale carry a marker. The open-interest badge shows when open-interest coverage starts.
- **All heatmaps:** colours no longer flicker while panning. Cells are combined into fixed, data-anchored blocks, cached and redrawn only when the data, zoom level, palette or threshold changes. Thin bands are no longer skipped when zoomed out.
- **Live heatmap:** the colour scale and trade-bubble size are learnt once and then held, so recorded history keeps its colours and bubble sizes. The CVD panel now shares the chart's time axis, and its range covers the whole recording.
- **Symbol view of the liquidation heatmap:** no false band when one venue's open interest history starts later. Merged open interest now counts only times where every venue has data.
- **Liquidation heatmap:** a superseded load or build can no longer overwrite a newer one. A new symbol or range opens with a fresh view instead of the previous symbol's price range.
- **Double-tap reset and axis gestures** use the current chart instead of the first one shown. Turning haptics on or off takes effect immediately.
- **Recording gaps** (app in the background) stay empty on the heatmap, the BBO lines and the CVD panel instead of repeating the last order book. Candles missed while paused, or while a stream was down, are back-filled when it returns. A trade-stream outage shows a notice.
- **Sideways swipes and pinches** no longer switch off price auto-fit or LIVE follow because of a few pixels of drift.
- **Binance streams:** a server close frame is answered, and a silence watchdog reconnects any socket that stops delivering data instead of showing it as live.
- **Order book:** connection state can no longer be overwritten by a concurrent update. Snapshot back-off resets after a successful sync. Crossed-book repair removes the stale side instead of valid asks.
- **Liquidations:** the 60-minute chart keeps every minute, however heavy the cascade.
- **Longs/Shorts** falls back to Bybit when Binance refuses the region.
- **News:** nested media titles no longer leak into headlines, HTML entities no longer cut a feed short, and opening a story without a browser shows a message instead of crashing.
- The grid now draws over the heatmap. The ticker cache locks per venue. The threshold slider saves only when you release it. The 2-minute Liq Map refresh pauses in the background. An invalid 8 h Bybit interval and dead code were removed.

### Changed
- **Chart** is now a heatmap chart. It opens on the last hour of history, reconstructed from 1-minute candles and marked as an estimate, followed by the live recording. A white price line replaces candles by default, and candles remain an option.
- Choosing another symbol pauses the current live recording and keeps it, for the two most recent markets, instead of discarding it.

## 1.0.0 — 2026-11-15

First release.

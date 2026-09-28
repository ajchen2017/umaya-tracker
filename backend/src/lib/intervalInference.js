// The app's 定位頻率 choices (android Prefs.kt INTERVAL_PRESETS), in seconds.
const INTERVAL_PRESETS = [10, 20, 30, 60, 120, 300, 600, 900, 1800, 3600];

const WINDOW_POINTS = 7;       // latest routine points looked at
const MIN_GAPS = 3;            // fewer than this and the spacing says nothing reliable
const MAX_GAP_SECONDS = 7200;  // longer gaps are pauses / no signal, not the interval

function median(values) {
  const s = [...values].sort((a, b) => a - b);
  const mid = Math.floor(s.length / 2);
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

function nearestPreset(seconds) {
  return INTERVAL_PRESETS.reduce((best, p) =>
    Math.abs(Math.log(p / seconds)) < Math.abs(Math.log(best / seconds)) ? p : best);
}

/**
 * The interval the phone is actually recording at. The app announces a 定位頻率 change with a
 * single best-effort PATCH that is never retried, so a change made without signal never reaches
 * the server — but every point it records still carries its own timestamp and its upload IS
 * retried, so the spacing of the latest routine points shows the real interval regardless.
 * An announced change newer than those points wins, since the points predate it.
 */
function effectiveIntervalSeconds(hike, points) {
  const recent = points.filter((p) => p.marker_type === 'normal').slice(-WINDOW_POINTS);
  const gaps = [];
  for (let i = 1; i < recent.length; i++) {
    const gap = (new Date(recent[i].recorded_at) - new Date(recent[i - 1].recorded_at)) / 1000;
    if (gap > 0 && gap <= MAX_GAP_SECONDS) gaps.push(gap);
  }
  if (gaps.length < MIN_GAPS) return hike.interval_seconds;

  const announcedAt = new Date(hike.interval_updated_at || hike.started_at);
  if (hike.interval_seconds && announcedAt > new Date(recent[0].recorded_at)) return hike.interval_seconds;

  return nearestPreset(median(gaps));
}

module.exports = { effectiveIntervalSeconds, INTERVAL_PRESETS };

// Guardian-facing alert level, derived from signal patterns — see the design
// proposal: the server can never *confirm* an emergency from GPS silence alone
// (a stationary hiker with no signal looks identical whether camping or hurt),
// so this only ever produces an advisory tier. The hiker's own "camping" marker
// is what lets a long, expected silence stay green instead of escalating.
//
// Tiers: green (normal) -> yellow (notice) -> orange (concern) -> red (alert).
// An explicit SOS marker forces red — unless the hiker later marked "safe",
// which clears it (points are chronological, so this is "last sos after any safe").
//
// All thresholds are configurable per hike (hikes.alert_config); these are the
// defaults and valid ranges shown/enforced on the settings page.
const DEFAULT_CONFIG = {
  greenHours: 2, // range 1-8
  campingSilenceHours: 6, // N for "silent 6h+ but plausibly camping" -> yellow; range 3-8
  dayOrangeStart: 2, // N1 — day silence orange floor; range 2-6
  dayOrangeEnd: 6, // N2 — day silence orange ceiling (red starts above this); range 6-12
  redDayHours: 6, // N — day silence red threshold; range 6-24
};

const RANGES = {
  greenHours: [1, 8],
  campingSilenceHours: [3, 8],
  dayOrangeStart: [2, 6],
  dayOrangeEnd: [6, 12],
  redDayHours: [6, 24],
};

function clamp(value, [min, max]) {
  return Math.min(max, Math.max(min, value));
}

// Merge user config over defaults, clamping/rejecting anything out of range
// rather than trusting client input outright.
function resolveConfig(userConfig) {
  const cfg = { ...DEFAULT_CONFIG };
  if (!userConfig || typeof userConfig !== 'object') return cfg;

  for (const key of ['greenHours', 'campingSilenceHours', 'dayOrangeStart', 'dayOrangeEnd', 'redDayHours']) {
    const v = Number(userConfig[key]);
    if (Number.isFinite(v)) cfg[key] = clamp(v, RANGES[key]);
  }

  return cfg;
}

// --- Astronomical day/night, from the hiker's own last-known position ---
// Was previously a fixed 18:00-06:00 Taipei-clock window — wrong for anyone recording
// outside Taiwan (e.g. a Finland test hike: Taipei's night doesn't line up with Finland's
// at all, off by the ~5-6h zone difference, so day/night came out backwards). Real
// sunrise/sunset at the actual lat/lng works anywhere without ever needing to know the
// hiker's timezone — the whole calculation runs in UTC, using longitude itself as the
// "which way to lean" input. Standard sunrise-equation approximation (the same one behind
// most "sunrise time for my location" tools); accurate to a few minutes, plenty for a
// coarse day/night check.
const RAD = Math.PI / 180;
const DAY_MS = 86400000;
const J1970 = 2440588;
const J2000 = 2451545;

function toJulian(date) {
  return date.getTime() / DAY_MS - 0.5 + J1970;
}
function fromJulian(J) {
  return new Date((J + 0.5 - J1970) * DAY_MS);
}
function toDays(date) {
  return toJulian(date) - J2000;
}
function solarMeanAnomaly(d) {
  return RAD * (357.5291 + 0.98560028 * d);
}
function eclipticLongitude(M) {
  const C = RAD * (1.9148 * Math.sin(M) + 0.02 * Math.sin(2 * M) + 0.0003 * Math.sin(3 * M));
  const P = RAD * 102.9372;
  return M + C + P + Math.PI;
}
function declination(l) {
  const e = RAD * 23.4397; // Earth's axial tilt; sun's own ecliptic latitude is ~0
  return Math.asin(Math.sin(l) * Math.sin(e));
}
function julianCycle(d, lw) {
  return Math.round(d - 0.0009 - lw / (2 * Math.PI));
}
function approxTransit(Ht, lw, n) {
  return 0.0009 + (Ht + lw) / (2 * Math.PI) + n;
}
function solarTransitJ(ds, M, L) {
  return J2000 + ds + 0.0053 * Math.sin(M) - 0.0069 * Math.sin(2 * L);
}
function hourAngle(h, phi, d) {
  return Math.acos((Math.sin(h) - Math.sin(phi) * Math.sin(d)) / (Math.cos(phi) * Math.cos(d)));
}

// Returns { sunrise, sunset } (both Date, UTC) for the UTC calendar day containing `date`,
// at the given latitude/longitude — or null for either if the sun doesn't rise/set that
// day at all (polar day/night, only reachable at extreme latitudes).
function sunTimes(date, lat, lng) {
  const lw = RAD * -lng;
  const phi = RAD * lat;
  const d = toDays(date);
  const n = julianCycle(d, lw);
  const ds = approxTransit(0, lw, n);
  const M = solarMeanAnomaly(ds);
  const L = eclipticLongitude(M);
  const dec = declination(L);
  const Jnoon = solarTransitJ(ds, M, L);

  const h0 = -0.833 * RAD; // standard sunrise/sunset altitude — atmospheric refraction + sun's apparent radius
  const w0 = hourAngle(h0, phi, dec);
  if (Number.isNaN(w0)) return { sunrise: null, sunset: null }; // polar day/night this date
  const Jset = solarTransitJ(approxTransit(w0, lw, n), M, L);
  const Jrise = Jnoon - (Jset - Jnoon);
  return { sunrise: fromJulian(Jrise), sunset: fromJulian(Jset) };
}

// Checks yesterday/today/tomorrow's (UTC-calendar-date) sun windows for whichever one
// actually contains `now` — avoids getting the wrong day's sunrise/sunset near a UTC
// midnight boundary, which a single day's calculation could otherwise straddle.
function isNight(now, lat, lng) {
  if (typeof lat !== 'number' || typeof lng !== 'number') return false; // no position yet — default to day (the less lenient tier)
  for (const offsetDays of [-1, 0, 1]) {
    const probe = new Date(now.getTime() + offsetDays * DAY_MS);
    const { sunrise, sunset } = sunTimes(probe, lat, lng);
    if (!sunrise || !sunset) continue; // polar day/night — try an adjacent date
    if (now >= sunrise && now < sunset) return false; // daytime
  }
  return true; // not inside any day window found — night (or genuine polar night)
}

function computeAlertLevel(points, now = new Date(), userConfig = null, hikeEnded = false) {
  const cfg = resolveConfig(userConfig);

  if (points.length === 0) {
    return { level: 'green', reason: 'no_data', message: '（等待追蹤的行程開始）', config: cfg };
  }

  // points[] is chronological (ORDER BY recorded_at ASC) — an SOS stays active
  // until a later "safe" point clears it, so search from the newest point back.
  for (let i = points.length - 1; i >= 0; i--) {
    if (points[i].marker_type === 'safe') break; // cleared before any earlier sos is found
    if (points[i].marker_type === 'sos') {
      return { level: 'red', reason: 'sos', message: '已發出 SOS 求救訊號', config: cfg };
    }
  }

  // A hike the hiker already ended has nothing further to report — silence from
  // here on is expected, not a warning sign. (An unresolved SOS above still wins.)
  if (hikeEnded) {
    return { level: 'green', reason: 'ended', message: null, config: cfg };
  }

  const last = points[points.length - 1];
  const lastAt = new Date(last.recorded_at);
  const hoursSince = (now.getTime() - lastAt.getTime()) / 3_600_000;
  const hrs = Math.floor(hoursSince);
  const isCamping = last.marker_type === 'camping';
  // Whether *now* (evaluation time) is day or night at the hiker's last known position —
  // not when the last point was recorded — since that's what makes an ongoing silence
  // plausible ("it's dark there right now, they're probably asleep") rather than the past.
  const night = isNight(now, last.lat, last.lng);

  if (isCamping) {
    if (hoursSince < cfg.campingSilenceHours) {
      return { level: 'green', reason: 'camping', message: '登山者已標記停駐中', config: cfg };
    }
    return { level: 'yellow', reason: 'extended_camping_silence', message: `已 ${hrs} 小時沒有回報（判斷為停駐中）`, config: cfg };
  }

  if (night) {
    if (hoursSince < cfg.greenHours) return { level: 'green', reason: 'recent', message: null, config: cfg };
    return { level: 'yellow', reason: 'night_silence', message: `已 ${hrs} 小時沒有回報，夜間訊號中斷通常是正常的`, config: cfg };
  }

  // Daytime, not camping. dayOrangeStart/dayOrangeEnd and greenHours/redDayHours
  // are independently configurable, so use whichever bound is more permissive at
  // each edge — that way a gap between two mismatched thresholds still resolves
  // to a sane tier instead of falling through undefined.
  if (hoursSince < cfg.greenHours) return { level: 'green', reason: 'recent', message: null, config: cfg };
  if (hoursSince < Math.max(cfg.dayOrangeStart, cfg.greenHours)) {
    return { level: 'green', reason: 'recent', message: null, config: cfg };
  }
  if (hoursSince < Math.max(cfg.dayOrangeEnd, cfg.redDayHours)) {
    return { level: 'orange', reason: 'day_silence', message: `已 ${hrs} 小時沒有回報`, config: cfg };
  }
  return { level: 'red', reason: 'extended_silence', message: `已 ${hrs} 小時沒有回報，請留意`, config: cfg };
}

module.exports = { computeAlertLevel, isNight, sunTimes, resolveConfig, DEFAULT_CONFIG, RANGES };

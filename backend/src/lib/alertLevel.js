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
  nightStart: '18:00', // T1 — wraps past midnight to nightEnd, always treated as valid
  nightEnd: '06:00', // T2
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

function isValidHHMM(s) {
  return typeof s === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(s);
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
  if (isValidHHMM(userConfig.nightStart)) cfg.nightStart = userConfig.nightStart;
  if (isValidHHMM(userConfig.nightEnd)) cfg.nightEnd = userConfig.nightEnd;

  return cfg;
}

const TAIPEI_UTC_OFFSET_HOURS = 8;

function taipeiMinutesOfDay(date) {
  const h = (date.getUTCHours() + TAIPEI_UTC_OFFSET_HOURS) % 24;
  return h * 60 + date.getUTCMinutes();
}

function hhmmToMinutes(hhmm) {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}

// Night window wraps past midnight whenever end <= start (e.g. 18:00 -> 06:00),
// which is the expected shape, not an error — see the settings page discussion.
function isNightInTaipei(date, cfg) {
  const now = taipeiMinutesOfDay(date);
  const start = hhmmToMinutes(cfg.nightStart);
  const end = hhmmToMinutes(cfg.nightEnd);
  if (end <= start) return now >= start || now < end;
  return now >= start && now < end;
}

function computeAlertLevel(points, now = new Date(), userConfig = null) {
  const cfg = resolveConfig(userConfig);

  if (points.length === 0) {
    return { level: 'green', reason: 'no_data', message: '尚未開始回報', config: cfg };
  }

  // points[] is chronological (ORDER BY recorded_at ASC) — an SOS stays active
  // until a later "safe" point clears it, so search from the newest point back.
  for (let i = points.length - 1; i >= 0; i--) {
    if (points[i].marker_type === 'safe') break; // cleared before any earlier sos is found
    if (points[i].marker_type === 'sos') {
      return { level: 'red', reason: 'sos', message: '已發出 SOS 求救訊號', config: cfg };
    }
  }

  const last = points[points.length - 1];
  const lastAt = new Date(last.recorded_at);
  const hoursSince = (now.getTime() - lastAt.getTime()) / 3_600_000;
  const hrs = Math.floor(hoursSince);
  const isCamping = last.marker_type === 'camping';
  // Whether *now* (evaluation time) falls in typical camp/sleep hours — not when
  // the last point was recorded — since that's what makes an ongoing silence
  // plausible ("it's night now, they're probably asleep") rather than the past.
  const night = isNightInTaipei(now, cfg);

  if (isCamping) {
    if (hoursSince < cfg.campingSilenceHours) {
      return { level: 'green', reason: 'camping', message: '登山者已標記紮營中', config: cfg };
    }
    return { level: 'yellow', reason: 'extended_camping_silence', message: `已 ${hrs} 小時沒有回報（判斷為紮營中）`, config: cfg };
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

module.exports = { computeAlertLevel, isNightInTaipei, resolveConfig, DEFAULT_CONFIG, RANGES };

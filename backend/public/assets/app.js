const shareToken = location.pathname.split('/').filter(Boolean).pop();
const btnSettings = document.getElementById('btnSettings');
if (btnSettings) btnSettings.href = `/t/${shareToken}/settings`;

// RudyMap only covers Taiwan; zooming/panning past this shows its coverage edge
// (blank tiles, or a very wide viewport that trips a browser compositor bug on
// high-DPI screens), so keep the view boxed to "Taiwan + outlying islands" while active.
const RUDY_MIN_ZOOM = 8;
const OSM_MIN_ZOOM = 2;
const RUDY_BOUNDS = L.latLngBounds([21.4, 118.0], [26.5, 122.3]);

// RudyMap's own theme only defines text sizing up to z19 — past that, contour
// lines/roads keep scaling (true vector geometry) but labels stay pinned at the
// z19 size, so text looks proportionally smaller next to everything else at
// z20-21. Allowed anyway per explicit request — geometry still scales correctly,
// only label size stops growing.
const RUDY_MAX_ZOOM = 21;
const OSM_MAX_ZOOM = 21;

const map = L.map('map', {
  zoomControl: true, maxZoom: RUDY_MAX_ZOOM, minZoom: RUDY_MIN_ZOOM,
  maxBounds: RUDY_BOUNDS, maxBoundsViscosity: 1.0,
}).setView([23.6, 121], 8);

// Before the hiker's first point arrives there's nothing to center on — use the
// guardian's own location as a more useful default than "all of Taiwan". Only
// applies if a real point hasn't already centered the map first (hasCenteredOnStart).
if (navigator.geolocation) {
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      if (!hasCenteredOnStart) map.setView([pos.coords.latitude, pos.coords.longitude], 16);
    },
    () => {}, // denied/unavailable — keep the Taiwan-wide fallback view
    { timeout: 10000 }
  );
}

// bounds: Leaflet never requests tiles outside this box, so the coverage edge is
// clean map background (ocean-blue), never a stray OpenStreetMap tile bleeding in.
const rudyLayer = L.tileLayer(RUDY_TILE_URL, {
  maxZoom: RUDY_MAX_ZOOM, bounds: RUDY_BOUNDS, attribution: '地圖資料 &copy; RudyMap',
});
const osmLayer = L.tileLayer(OSM_TILE_URL, { maxZoom: 19, maxNativeZoom: 19, attribution: '&copy; OpenStreetMap contributors' });
let currentMapLayer = 'rudy';
const MAP_LAYER_LABEL = { rudy: '魯地圖', osm: '線上地圖' };
switchLayer('rudy'); // also sets btnMapLayer's initial title — real default (Taiwan vs. not) applies once the hiker's actual position is known, see render()

const TRACK_COLORS = [
  '#e63946', '#f4a261', '#e9c46a', '#2a9d8f', '#06d6a0', '#118ab2',
  '#073b4c', '#7209b7', '#ff006e', '#fb8500', '#43aa8b', '#4d4d4d',
];
const PLANNED_ROUTE_COLOR = '#2d7dd2'; // uploaded GPX/KML route is always blue

let trackColor = localStorage.getItem('trackColor') || TRACK_COLORS[0];

// Meters/second a mode is plausibly capable of — hiking tops out around a brisk walk/jog
// (200m/min), cycling around 40km/h (700m/min). Set on the settings page, read here.
const TRAVEL_MODE_SPEED_MPS = { hiking: 200 / 60, cycling: 700 / 60 };
let travelMode = localStorage.getItem('travelMode') || 'hiking';
window.addEventListener('storage', (e) => {
  if (e.key !== 'travelMode') return;
  travelMode = e.newValue || 'hiking';
  if (lastRenderedPoints) drawTrack(lastRenderedPoints);
});

function haversineMeters(lat1, lng1, lat2, lng2) {
  const R = 6371000;
  const toRad = (d) => (d * Math.PI) / 180;
  const dLat = toRad(lat2 - lat1);
  const dLng = toRad(lng2 - lng1);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2;
  return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

// Keeps a point only if its straight-line distance from the last KEPT point implies a
// plausible speed for the current travel mode (checked against the last kept point, not
// the raw previous one — so skipping one bad point doesn't also reject the next good one).
// Same idea as the phone's own GPS teleport filter, applied here to how the line is drawn.
//
// A pure speed×time threshold breaks down at short recording intervals: GPS noise itself
// (worse near buildings — multipath) is commonly 20-50m even standing still, and at a 10s
// interval a walking speed limit only allows ~33m — so ordinary GPS jitter alone would get
// misread as "impossibly fast" and rejected, even with zero real movement. The allowed
// distance is floored by both points' own reported accuracy radius: two fixes whose error
// circles could plausibly overlap are never rejected on speed grounds, no matter how short
// the time between them.
const MIN_ACCURACY_MARGIN_M = 20; // baseline slack even when accuracy is missing/zero

function filterPlausiblePoints(points) {
  const speedLimit = TRAVEL_MODE_SPEED_MPS[travelMode] || TRAVEL_MODE_SPEED_MPS.hiking;
  const kept = [];
  let last = null;
  points.forEach((p) => {
    if (!last) { kept.push(p); last = p; return; }
    const dtSec = (new Date(p.recorded_at) - new Date(last.recorded_at)) / 1000;
    if (dtSec <= 0) return; // duplicate/out-of-order timestamp — no speed can be computed
    const distM = haversineMeters(last.lat, last.lng, p.lat, p.lng);
    const accuracyFloor = (p.accuracy || 0) + (last.accuracy || 0) + MIN_ACCURACY_MARGIN_M;
    const allowedM = Math.max(speedLimit * dtSec, accuracyFloor);
    if (distM > allowedM) return; // implausible for this mode even accounting for GPS noise — drop
    kept.push(p);
    last = p;
  });
  return kept;
}

const polyline = L.polyline([], { color: trackColor, weight: 4 }).addTo(map);
let lastMarker = null;
let sosLayer = L.layerGroup().addTo(map);
let markerEventLayer = L.layerGroup().addTo(map); // "我很好" / "停駐中" icons — otherwise indistinguishable from normal points
let pointsLayer = L.layerGroup().addTo(map);
let plannedLayer = L.layerGroup().addTo(map);
let lastPointCount = 0;
let hasCenteredOnStart = false;
let startLatLng = null;
let lastLatLng = null; // newest point — what 📍 recenters to, and what decides the RudyMap-bounds check
let lastPlannedRoute = undefined; // undefined = never checked yet, distinct from null (cleared)
let lastRenderedPoints = null;
let currentNickname = '';
let lastPointRecordedAt = null;

function fmtRelativeTime(iso) {
  const diffSec = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (diffSec < 10) return '剛剛';
  if (diffSec < 60) return `${Math.floor(diffSec)} 秒前`;
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)} 分鐘前`;
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)} 小時前`;
  return `${Math.floor(diffSec / 86400)} 天前`;
}

// Live label anchored to the hiker's current-position marker: nickname (falls
// back to the account's display_name for hikes created before this field
// existed) plus a relative-time readout that keeps ticking between polls.
// 暫停/結束行程 ride along here too, since neither has its own track point to
// anchor a separate map label to.
let currentHikeState = { paused: false, status: 'active' };
function buildMarkerLabel() {
  const parts = [currentNickname];
  if (lastPointRecordedAt) parts.push(fmtRelativeTime(lastPointRecordedAt));
  if (currentHikeState.status === 'ended') parts.push('🏁 結束行程');
  else if (currentHikeState.paused) parts.push('⏸ 暫停');
  return parts.join(' · ');
}
setInterval(() => {
  if (lastMarker && lastPointRecordedAt) lastMarker.setTooltipContent(buildMarkerLabel());
}, 5000);

function updateLabelSize() {
  const zoom = map.getZoom();
  const size = Math.max(8, Math.min(16, 8 + (zoom - 10) * 1.3));
  document.documentElement.style.setProperty('--point-label-size', `${size}px`);
}
map.on('zoom', updateLabelSize);
updateLabelSize();

function updateZoomLevel() {
  // map.getZoom() returns fractional values mid-gesture (pinch-zoom) — round it, or the
  // number box shows a long decimal string that only resolves once the finger lifts.
  document.getElementById('zoomLevel').textContent = Math.round(map.getZoom());
}
map.on('zoom', updateZoomLevel);
updateZoomLevel();

// A long hike name wraps to two lines on narrow phones, growing the topbar past the
// height the right-side button column (btnStart/btnColor/...) and zoomLevel box assume —
// re-measure whenever it might have changed instead of hardcoding a single-line height.
function updateTopbarHeight() {
  document.documentElement.style.setProperty('--topbar-h', `${document.getElementById('topbar').offsetHeight}px`);
}
window.addEventListener('resize', updateTopbarHeight);
new ResizeObserver(updateTopbarHeight).observe(document.getElementById('topbar'));
updateTopbarHeight();

// 'guardian' (default): plain browser-local getters — whatever timezone the guardian's own
// device is set to. 'hiker': shift by the recorded point's own longitude (~15°/hour, the
// same simple sundial-style estimate used for the astronomical day/night check server-side
// — not real timezone-boundary data, but consistent with the rest of the app never needing
// an external timezone lookup) so a guardian in Taiwan can see what the hiker's own local
// clock read, not just their own.
let timeDisplayMode = localStorage.getItem('timeDisplayMode') || 'guardian';
window.addEventListener('storage', (e) => {
  if (e.key !== 'timeDisplayMode') return;
  timeDisplayMode = e.newValue || 'guardian';
  if (lastRenderedPoints) drawTrack(lastRenderedPoints); // storage events don't cover the tab that made the change (see pageshow below) — this is for a second tab open at the same time
});

// A hike that already ended never gets new points, so drawTrack() never runs again on its
// own after the first render — nothing would ever pick up a setting changed afterward on
// the settings page. That's compounded by bfcache: navigating back to this page via history
// (as "← 返回地圖" does) can restore it from cache without re-running this script at all, so
// the in-memory settings variables above go stale relative to whatever's now in localStorage.
// Re-sync from localStorage and force a redraw whenever the page becomes visible again.
window.addEventListener('pageshow', (e) => {
  if (!e.persisted) return; // only the bfcache-restore case needs this — a fresh load already reads localStorage above
  timeDisplayMode = localStorage.getItem('timeDisplayMode') || 'guardian';
  travelMode = localStorage.getItem('travelMode') || 'hiking';
  if (lastRenderedPoints) drawTrack(lastRenderedPoints);
});

function fmtDateTime(iso, lng) {
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  if (timeDisplayMode === 'hiker' && typeof lng === 'number') {
    const offsetH = Math.round(lng / 15);
    const shifted = new Date(d.getTime() + offsetH * 3_600_000);
    return `${shifted.getUTCFullYear()}${pad(shifted.getUTCMonth() + 1)}${pad(shifted.getUTCDate())}:${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}:${pad(shifted.getUTCSeconds())}`;
  }
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}:${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function addPlannedLine(latlngs) {
  if (latlngs.length > 0) {
    L.polyline(latlngs, { color: PLANNED_ROUTE_COLOR, weight: 3, dashArray: '8,6' }).addTo(plannedLayer);
  }
}

function addPlannedWaypoint(lat, lon, name) {
  L.circleMarker([lat, lon], {
    radius: 5, color: PLANNED_ROUTE_COLOR, fillColor: '#fff', fillOpacity: 1, weight: 2,
  })
    .bindTooltip(name, { permanent: true, direction: 'top', offset: [0, -6], className: 'waypoint-label' })
    .addTo(plannedLayer);
}

function parseGpxRoute(xml) {
  addPlannedLine(Array.from(xml.getElementsByTagName('trkpt')).map((el) => [
    parseFloat(el.getAttribute('lat')), parseFloat(el.getAttribute('lon')),
  ]));

  Array.from(xml.getElementsByTagName('wpt')).forEach((el) => {
    const lat = parseFloat(el.getAttribute('lat'));
    const lon = parseFloat(el.getAttribute('lon'));
    const name = el.getElementsByTagName('name')[0]?.textContent || '';
    addPlannedWaypoint(lat, lon, name);
  });
}

// KML coordinates are "lon,lat[,ele]" tuples, whitespace-separated for a LineString.
function parseKmlCoordinates(text) {
  return text.trim().split(/\s+/).map((tuple) => {
    const [lon, lat] = tuple.split(',').map(Number);
    return [lat, lon];
  });
}

function parseKmlRoute(xml) {
  Array.from(xml.getElementsByTagName('Placemark')).forEach((placemark) => {
    const name = placemark.getElementsByTagName('name')[0]?.textContent || '';
    const lineString = placemark.getElementsByTagName('LineString')[0];
    const point = placemark.getElementsByTagName('Point')[0];

    if (lineString) {
      const coordsText = lineString.getElementsByTagName('coordinates')[0]?.textContent;
      if (coordsText) addPlannedLine(parseKmlCoordinates(coordsText));
    } else if (point) {
      const coordsText = point.getElementsByTagName('coordinates')[0]?.textContent;
      if (coordsText) {
        const [[lat, lon]] = parseKmlCoordinates(coordsText);
        addPlannedWaypoint(lat, lon, name);
      }
    }
  });
}

function renderPlannedRoute(routeText) {
  const xml = new DOMParser().parseFromString(routeText, 'application/xml');
  if (xml.querySelector('parsererror')) return;

  plannedLayer.clearLayers();

  const root = xml.documentElement.tagName.toLowerCase();
  if (root === 'kml') parseKmlRoute(xml);
  else parseGpxRoute(xml);
}

// Non-routine marker types get their own icon on the map — otherwise "我很好"
// and "停駐中" points look exactly like ordinary background pings.
const MARKER_EVENT_ICONS = { safe: '✅', camping: '⛺' };
const MARKER_EVENT_LABELS = { safe: '我很好', camping: '停駐中' };

function drawTrack(points) {
  // Status readout / blue endpoint marker always reflect the true latest reading — only
  // the line and its dots are filtered, so "last updated" never looks stale because the
  // newest point happened to look implausible.
  const last = points[points.length - 1];
  const validPoints = filterPlausiblePoints(points);
  const latlngs = validPoints.map((p) => [p.lat, p.lng]);
  const sosPoints = points.filter((p) => p.marker_type === 'sos');
  const eventPoints = points.filter((p) => p.marker_type === 'safe' || p.marker_type === 'camping');

  polyline.setStyle({ color: trackColor });
  polyline.setLatLngs(latlngs);

  pointsLayer.clearLayers();
  validPoints.forEach((p) => {
    const label = MARKER_EVENT_LABELS[p.marker_type];
    L.circleMarker([p.lat, p.lng], {
      radius: 4, color: trackColor, fillColor: '#fff', fillOpacity: 1, weight: 2,
    })
      .bindPopup(
        `${p.lat.toFixed(6)}, ${p.lng.toFixed(6)}<br>${fmtDateTime(p.recorded_at, p.lng)}` +
          (label ? `<br>${MARKER_EVENT_ICONS[p.marker_type]} ${label}` : '') +
          (p.battery_pct != null ? `<br>🔋 ${p.battery_pct}%` : '')
      )
      .addTo(pointsLayer);
  });

  markerEventLayer.clearLayers();
  eventPoints.forEach((p) => {
    L.marker([p.lat, p.lng], {
      icon: L.divIcon({ html: MARKER_EVENT_ICONS[p.marker_type], className: '', iconSize: [22, 22] }),
    })
      .bindTooltip(MARKER_EVENT_LABELS[p.marker_type], { permanent: true, direction: 'right', offset: [10, 0], className: 'waypoint-label' })
      .bindPopup(`${MARKER_EVENT_LABELS[p.marker_type]}<br>${fmtDateTime(p.recorded_at, p.lng)}`)
      .addTo(markerEventLayer);
  });

  // Endpoint marker (current position) always stays blue, regardless of track color.
  if (lastMarker) map.removeLayer(lastMarker);
  lastPointRecordedAt = last.recorded_at;
  lastMarker = L.circleMarker([last.lat, last.lng], {
    radius: 8, color: '#2d7dd2', fillColor: '#4da3ff', fillOpacity: 1, weight: 3,
  })
    .bindTooltip(buildMarkerLabel(), { permanent: true, direction: 'right', offset: [10, 0], className: 'waypoint-label' })
    .addTo(map);

  sosLayer.clearLayers();
  sosPoints.forEach((p) => {
    L.marker([p.lat, p.lng], {
      icon: L.divIcon({ html: '🆘', className: '', iconSize: [24, 24] }),
    })
      .bindTooltip('SOS', { permanent: true, direction: 'right', offset: [12, 0], className: 'waypoint-label' })
      .bindPopup(`SOS<br>${fmtDateTime(p.recorded_at, p.lng)}`)
      .addTo(sosLayer);
  });

  document.getElementById('lastUpdate').textContent = fmtRelativeTime(last.recorded_at);
  document.getElementById('lastPos').textContent =
    `${last.lat.toFixed(5)}, ${last.lng.toFixed(5)}` +
    (last.altitude ? ` · 海拔${Math.round(last.altitude)}m` : '') +
    (last.accuracy ? ` · 誤差±${Math.round(last.accuracy)}m` : '');

  const batteryEl = document.getElementById('lastBattery');
  if (last.battery_pct != null) {
    const color = last.battery_pct <= 20 ? '#d92b2b' : last.battery_pct <= 50 ? '#e8a33d' : '#1a9c4a';
    batteryEl.textContent = `${last.battery_pct}%`;
    batteryEl.style.color = color;
    batteryEl.style.fontWeight = '600';
  } else {
    batteryEl.textContent = '—';
    batteryEl.style.color = '';
    batteryEl.style.fontWeight = '';
  }
}

function setTrackColor(color) {
  trackColor = color;
  localStorage.setItem('trackColor', color);
  if (lastRenderedPoints) drawTrack(lastRenderedPoints);
}

let lastRenderedAlertConfig = null;

const ALERT_LEVEL_ICONS = { green: '🟢', yellow: '🟡', orange: '🟠', red: '🔴' };

function updateAlertBanner(alert) {
  const el = document.getElementById('alertBanner');
  document.getElementById('alertIcon').textContent = ALERT_LEVEL_ICONS[alert && alert.level] || '🟢';
  if (!alert || alert.level === 'green' || !alert.message) {
    el.classList.remove('show', 'yellow', 'orange', 'red');
  } else {
    const icon = ALERT_LEVEL_ICONS[alert.level] || '';
    el.textContent = `${icon} ${alert.message}`;
    el.className = `show ${alert.level}`;
  }

  // The tooltip reflects this hike's actual configured thresholds, not fixed
  // defaults — only rebuild it when the config actually changes.
  const cfg = alert && alert.config;
  if (!cfg || JSON.stringify(cfg) === JSON.stringify(lastRenderedAlertConfig)) return;
  lastRenderedAlertConfig = cfg;

  document.getElementById('alertInfoTooltip').innerHTML = `
    🟢 綠：${cfg.greenHours} 小時內有回報，或已標記「停駐中」<br>
    🟡 黃：登山者當地夜間（依定位經緯度計算日出日落）斷訊，或斷訊超過 ${cfg.campingSilenceHours} 小時但判斷為停駐<br>
    🟠 橘：白天斷訊 ${cfg.dayOrangeStart}–${cfg.dayOrangeEnd} 小時<br>
    🔴 紅：SOS，或白天斷訊超過 ${cfg.redDayHours} 小時
  `;
}

// Sound is best-effort: browsers block AudioContext until the page has had a user
// gesture, so this can silently no-op on first load — the visual frame/banner is
// the primary alert either way. Unlocked below on the first click anywhere.
let audioCtx = null;
let sosBeepTimer = null;

function ensureAudioCtx() {
  if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  if (audioCtx.state === 'suspended') audioCtx.resume().catch(() => {});
  return audioCtx;
}
document.addEventListener('click', () => { try { ensureAudioCtx(); } catch {} }, { once: true });

function sosBeep() {
  try {
    const ctx = ensureAudioCtx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'square';
    osc.frequency.value = 880;
    gain.gain.setValueAtTime(0.15, ctx.currentTime);
    osc.connect(gain).connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.4);
  } catch {
    // AudioContext unavailable/blocked — nothing more to do, see comment above.
  }
}

// Driven by the server-computed alert (respects a later "safe" clearing an
// earlier SOS), not raw historical SOS points — those never un-happen, but the
// active emergency state should stop once the hiker checks in.
let sosActive = false;
let sosMuted = false; // resets on each fresh SOS onset — muting a past SOS shouldn't silence the next one

function updateSosAlert(alert) {
  const active = !!alert && alert.level === 'red' && alert.reason === 'sos';
  if (active && !sosActive) sosMuted = false; // new onset (was clear, now SOS) — always sound once
  sosActive = active;

  const banner = document.getElementById('sosBanner');
  banner.style.display = active ? 'block' : 'none';
  banner.textContent = sosMuted
    ? '🆘 已發出 SOS 標記，請確認登山者狀況（聲音已靜音，點擊恢復）'
    : '🆘 已發出 SOS 標記，請確認登山者狀況（點擊靜音）';
  // Flashing is the primary channel, not gated by the sound mute — sound can be blocked
  // outright by the browser (autoplay policy, no gesture on the page yet), flashing can't.
  banner.classList.toggle('flashing', active);
  document.getElementById('sosFrame').classList.toggle('show', active);
  document.getElementById('sosFrame').classList.toggle('flashing', active);

  if (active && !sosMuted && !sosBeepTimer) {
    sosBeep();
    sosBeepTimer = setInterval(sosBeep, 1500);
  } else if ((!active || sosMuted) && sosBeepTimer) {
    clearInterval(sosBeepTimer);
    sosBeepTimer = null;
  }
}

document.getElementById('sosBanner').addEventListener('click', () => {
  sosMuted = !sosMuted;
  updateSosAlert(sosActive ? { level: 'red', reason: 'sos' } : null);
});

function updateHikeStatus(hike) {
  const el = document.getElementById('hikeStatus');
  if (hike.status === 'ended') {
    el.textContent = hike.ended_at ? `已結束（${fmtDateTime(hike.ended_at, lastLatLng && lastLatLng[1])}）` : '已結束';
  } else if (hike.paused) {
    el.textContent = '進行中（定位已暫停）';
  } else {
    el.textContent = '進行中';
  }
}

// --- Live elevation-vs-time strip chart ---
// Horizontally scrollable (native scrollbar) so the guardian can drag back
// through the whole hike's history, not just a fixed recent window; zoom
// buttons step through what one grid division represents. X is real elapsed
// time, not point index — a delayed/dropped point doesn't drift off a
// nominal grid, it just leaves a gap, same as a paused/silent stretch would.
// Auto-follows the live edge only while already scrolled near it, so
// browsing history doesn't get yanked back on the next poll.
const ELEVATION_MAX_POINTS = 2000; // sanity cap for very long hikes, not a "recent window"
const ELEVATION_PX_PER_GRID = 60; // constant on-screen spacing between gridlines at any zoom level
const ELEVATION_GRID_LEVELS = [
  { sec: 60, label: '1 分鐘' },
  { sec: 5 * 60, label: '5 分鐘' },
  { sec: 15 * 60, label: '15 分鐘' },
  { sec: 30 * 60, label: '30 分鐘' },
  { sec: 60 * 60, label: '1 小時' },
  { sec: 2 * 60 * 60, label: '2 小時' },
];
const ELEVATION_EVENT_LABELS = { sos: 'SOS', safe: '我很好', camping: '停駐中' };
let elevationHikeId = null;
let elevationFrozen = false;
let elevationGridLevelIdx = 0; // index into ELEVATION_GRID_LEVELS; 0 = finest (1 分鐘/格)
let lastElevationHike = null;
let lastElevationPoints = null;
// Default off: most check-ins never need it, and it used to eat space in the
// status bar even when nobody looked at it. Remembered per-browser once toggled on.
let elevationChartVisible = localStorage.getItem('elevationChartVisible') === '1';

function formatDuration(sec) {
  if (sec < 60) return `${Math.round(sec)} 秒`;
  if (sec < 3600) return `${Math.round(sec / 60)} 分`;
  return `${(sec / 3600).toFixed(1)} 小時`;
}

function drawElevationChart() {
  const hike = lastElevationHike, points = lastElevationPoints;
  if (!hike || !points) return;
  const el = document.getElementById('elevationChart');
  const yAxisEl = document.getElementById('elevationAxisY');
  const scrollEl = document.getElementById('elevationScroll');
  // The text span, not the outer row — that row also holds the zoom control buttons now,
  // and .textContent on it would silently wipe those out along with the caption text.
  const xAxisEl = document.getElementById('elevationAxisXText');

  const withAltitude = points.filter((p) => p.altitude != null).slice(-ELEVATION_MAX_POINTS);
  if (withAltitude.length < 2) {
    el.classList.remove('show');
    yAxisEl.innerHTML = '';
    scrollEl.innerHTML = '';
    xAxisEl.textContent = '';
    return;
  }
  // Toggle visibility before measuring scrollEl.clientWidth below — a hidden (display:none)
  // element reports 0 width, which would undersize the pre-filled grid on the very first draw.
  el.classList.toggle('show', elevationChartVisible);

  const wasNearRightEdge = scrollEl.scrollWidth - scrollEl.scrollLeft - scrollEl.clientWidth < 40;

  const alts = withAltitude.map((p) => p.altitude);
  const minAlt = Math.min(...alts);
  const maxAlt = Math.max(...alts);
  const altRange = Math.max(1, maxAlt - minAlt); // avoid divide-by-zero on flat ground

  // Fixed pixel dimensions (not a responsive viewBox) — width grows with elapsed real
  // time so the scroll container actually has something to scroll. No room reserved
  // here for axis text anymore — that lives in the separate fixed Y-axis/X-axis
  // panels, which is what keeps it from scrolling away.
  const H = 96;
  const padX = 6, padTop = 18, padBottom = 6;
  const plotH = H - padTop - padBottom;
  const gridSec = ELEVATION_GRID_LEVELS[elevationGridLevelIdx].sec;
  const pxPerSec = ELEVATION_PX_PER_GRID / gridSec;
  const times = withAltitude.map((p) => new Date(p.recorded_at).getTime());
  const startMs = times[0];
  // X is real elapsed time since the first point — not point index. A delayed or
  // dropped point doesn't drift off a nominal grid, it just leaves a visible gap.
  const xAt = (i) => padX + ((times[i] - startMs) / 1000) * pxPerSec;
  const yAt = (alt) => padTop + plotH - ((alt - minAlt) / altRange) * plotH;
  const dataW = xAt(withAltitude.length - 1) + padX;
  // Grid/frame always fill at least the visible panel — pre-drawn regardless of how much
  // data exists yet, so only the line/dots/labels grow as new points arrive, not the canvas.
  const totalW = Math.max(dataW, scrollEl.clientWidth || 0);

  let gridSvg = '';
  for (let g = 0; g <= 2; g++) {
    const y = (padTop + (plotH / 2) * g).toFixed(1);
    gridSvg += `<line class="axis-line" x1="${padX}" y1="${y}" x2="${totalW - padX}" y2="${y}" />`;
  }
  // Vertical ticks at a constant on-screen spacing, each spanning gridSec of real
  // time — a true time axis at whatever granularity the zoom level currently is.
  for (let x = padX; x <= totalW - padX; x += ELEVATION_PX_PER_GRID) {
    gridSvg += `<line class="axis-line" x1="${x.toFixed(1)}" y1="${padTop}" x2="${x.toFixed(1)}" y2="${H - padBottom}" />`;
  }

  const pathD = withAltitude.map((p, i) => `${i === 0 ? 'M' : 'L'}${xAt(i).toFixed(1)},${yAt(p.altitude).toFixed(1)}`).join(' ');

  // Clickable dots — recentering the map on a past point is how the guardian
  // cross-references "where was he when the elevation did this".
  let dotsSvg = '';
  withAltitude.forEach((p, i) => {
    dotsSvg += `<circle class="elev-point" cx="${xAt(i).toFixed(1)}" cy="${yAt(p.altitude).toFixed(1)}" r="2.5" data-lat="${p.lat}" data-lng="${p.lng}" />`;
  });

  let labelsSvg = '';
  withAltitude.forEach((p, i) => {
    const label = ELEVATION_EVENT_LABELS[p.marker_type];
    if (!label) return;
    const cls = p.marker_type === 'sos' ? 'event-label sos' : 'event-label';
    labelsSvg += `<text class="${cls}" x="${xAt(i).toFixed(1)}" y="${(yAt(p.altitude) - 6).toFixed(1)}">${label}</text>`;
  });
  const lastX = xAt(withAltitude.length - 1).toFixed(1);
  if (hike.status === 'ended') {
    labelsSvg += `<text class="event-label edge" x="${lastX}" y="${padTop - 5}">結束行程</text>`;
  } else if (hike.paused) {
    labelsSvg += `<text class="event-label edge" x="${lastX}" y="${padTop - 5}">暫停</text>`;
  }

  // Frame spans the full canvas (pre-filled, not data-dependent) — the axis panels sit
  // outside it (Y to its left, X below it), fixed in place while this scrolls underneath them.
  const frameSvg = `<rect class="chart-frame" x="${padX}" y="${padTop}" width="${totalW - padX * 2}" height="${plotH}" />`;

  scrollEl.innerHTML = `<svg width="${totalW}" height="${H}" viewBox="0 0 ${totalW} ${H}" preserveAspectRatio="none">${frameSvg}${gridSvg}<path class="elevation-line" d="${pathD}" />${dotsSvg}${labelsSvg}</svg>`;
  el.dataset.frozen = elevationFrozen ? '1' : '0';
  // Status bar's height varies (alert banner, signal legend) — pin flush above it, not a
  // hardcoded offset that would drift out of place whenever that content changes.
  el.style.bottom = `${document.getElementById('statusbar').offsetHeight}px`;

  if (wasNearRightEdge) requestAnimationFrame(() => { scrollEl.scrollLeft = scrollEl.scrollWidth; });

  // Fixed Y-axis: rotated "高度(M)" title + the actual altitude at each gridline
  // (top/middle/bottom), recomputed every redraw since the visible range changes
  // as new points arrive or the zoom level changes.
  const yTitleX = 10, yTitleY = padTop + plotH / 2;
  const yTicks = [maxAlt, (maxAlt + minAlt) / 2, minAlt]
    .map((alt, g) => `<text class="axis-tick" x="30" y="${(padTop + (plotH / 2) * g + 3).toFixed(1)}">${Math.round(alt)}</text>`)
    .join('');
  yAxisEl.innerHTML = `<svg viewBox="0 0 32 ${H}" preserveAspectRatio="none">
    <text class="axis-label" x="${yTitleX}" y="${yTitleY}" text-anchor="middle" transform="rotate(-90, ${yTitleX}, ${yTitleY})">高度(M)</text>
    ${yTicks}
  </svg>`;

  // X is a true time axis now, so "每格" is always exactly what the current zoom level
  // says, by construction — no longer an estimate. Still surface the phone's actual
  // configured recording interval when the hike has one, since that's separate useful
  // context (how dense the real data is), not what the grid spacing means.
  const intervalNote = hike.interval_seconds ? ` · 定位頻率 ${formatDuration(hike.interval_seconds)}` : '';
  xAxisEl.textContent = `時間（每格 ${ELEVATION_GRID_LEVELS[elevationGridLevelIdx].label}）${intervalNote}`;
}

const btnElevToggle = document.getElementById('btnElevToggle');
btnElevToggle.classList.toggle('active', elevationChartVisible);
btnElevToggle.addEventListener('click', () => {
  elevationChartVisible = !elevationChartVisible;
  localStorage.setItem('elevationChartVisible', elevationChartVisible ? '1' : '0');
  btnElevToggle.classList.toggle('active', elevationChartVisible);
  drawElevationChart();
});

document.getElementById('btnElevZoomIn').addEventListener('click', () => {
  elevationGridLevelIdx = Math.max(0, elevationGridLevelIdx - 1);
  drawElevationChart();
});
document.getElementById('btnElevZoomOut').addEventListener('click', () => {
  elevationGridLevelIdx = Math.min(ELEVATION_GRID_LEVELS.length - 1, elevationGridLevelIdx + 1);
  drawElevationChart();
});
document.getElementById('btnElevZoomReset').addEventListener('click', () => {
  elevationGridLevelIdx = 0;
  drawElevationChart();
});

// Delegated on the scroll container (not per-dot) since scrollEl.innerHTML is
// fully replaced on every redraw — per-element listeners would leak/vanish.
document.getElementById('elevationScroll').addEventListener('click', (e) => {
  const dot = e.target.closest('.elev-point');
  if (!dot) return;
  const lat = parseFloat(dot.dataset.lat);
  const lng = parseFloat(dot.dataset.lng);
  if (!isNaN(lat) && !isNaN(lng)) map.setView([lat, lng], Math.max(map.getZoom(), 16));
});

function updateElevationChart(hike, points) {
  if (hike.id !== elevationHikeId) {
    elevationHikeId = hike.id; // new hike started — resume scrolling from scratch
    elevationFrozen = false;
  }
  // 結束行程 freezes the chart where it was; a later hike (caught above) is what resumes it.
  if (hike.status === 'ended') elevationFrozen = true;
  const el = document.getElementById('elevationChart');
  if (elevationFrozen && el.dataset.frozen === '1') return;

  lastElevationHike = hike;
  lastElevationPoints = points;
  drawElevationChart();
}

function render(data) {
  const { hike, points, alert } = data;
  currentNickname = hike.nickname || hike.hiker_name;
  document.getElementById('hikeName').textContent = `${currentNickname} · ${hike.name}`;
  const hikeStateChanged = currentHikeState.paused !== !!hike.paused || currentHikeState.status !== hike.status;
  currentHikeState = { paused: !!hike.paused, status: hike.status };
  if (hikeStateChanged && lastMarker) lastMarker.setTooltipContent(buildMarkerLabel());
  updateAlertBanner(alert); // time-based, so must update even when no new points arrived
  updateSosAlert(alert);
  updateHikeStatus(hike);
  updateElevationChart(hike, points);

  // Re-check every poll, not just once: the planned route can be replaced or
  // cleared from the phone/settings page mid-hike, and the map needs to follow.
  if (hike.planned_route !== lastPlannedRoute) {
    lastPlannedRoute = hike.planned_route;
    if (hike.planned_route) {
      renderPlannedRoute(hike.planned_route);
    } else {
      plannedLayer.clearLayers();
    }
  }

  if (points.length === lastPointCount) return; // nothing new
  lastPointCount = points.length;
  if (points.length === 0) return;

  lastRenderedPoints = points;
  drawTrack(points);

  if (!startLatLng) {
    startLatLng = [points[0].lat, points[0].lng];
    document.getElementById('btnStart').disabled = false;
  }
  lastLatLng = [points[points.length - 1].lat, points[points.length - 1].lng];

  if (!hasCenteredOnStart) {
    hasCenteredOnStart = true;
    // RudyMap only covers Taiwan — a hiker actually outside it would otherwise render as
    // a dark/blank map (view clamped to Taiwan bounds while trying to center elsewhere).
    // Re-checked on every "first centering" — including after a hard refresh, since that
    // re-runs this whole script from scratch and would otherwise default back to RudyMap.
    if (currentMapLayer === 'rudy' && !RUDY_BOUNDS.contains(lastLatLng)) switchLayer('osm');
    map.setView(lastLatLng, 18);
  } else {
    map.fitBounds(polyline.getBounds(), { padding: [30, 30] });
  }
}

async function refresh() {
  try {
    const res = await fetch(`/api/t/${shareToken}`);
    if (!res.ok) throw new Error('not found');
    render(await res.json());
  } catch (err) {
    document.getElementById('hikeName').textContent = '找不到這個行程';
  }
}

// A full hard reload (like Ctrl+Shift+R), not just an AJAX re-fetch — this page
// has bitten guardians before with a stale-cached HTML/JS build showing nothing
// changing no matter how long they waited on a plain data refresh.
document.getElementById('btnRefresh').addEventListener('click', () => {
  location.href = location.pathname + '?_=' + Date.now();
});

function switchLayer(layer) {
  currentMapLayer = layer;
  const btn = document.getElementById('btnMapLayer');
  btn.title = `目前：${MAP_LAYER_LABEL[layer]}（點擊切換至${MAP_LAYER_LABEL[layer === 'rudy' ? 'osm' : 'rudy']}）`;
  if (layer === 'osm') {
    map.removeLayer(rudyLayer);
    osmLayer.addTo(map);
    map.setMinZoom(OSM_MIN_ZOOM);
    map.setMaxBounds(null);
  } else {
    map.removeLayer(osmLayer);
    rudyLayer.addTo(map);
    map.setMinZoom(RUDY_MIN_ZOOM);
    if (map.getZoom() < RUDY_MIN_ZOOM) map.setZoom(RUDY_MIN_ZOOM);
    map.setMaxBounds(RUDY_BOUNDS);
  }
}

document.getElementById('btnMapLayer').addEventListener('click', () => {
  const next = currentMapLayer === 'rudy' ? 'osm' : 'rudy';
  // 魯地圖 only has tiles for Taiwan — switching to it while the hiker's last known
  // position is elsewhere would just show a dark/blank map, so refuse with an explanation
  // instead of silently loading nothing.
  if (next === 'rudy' && lastLatLng && !RUDY_BOUNDS.contains(lastLatLng)) {
    alert('該位置不在魯地圖範圍，地圖不載入，請切回線上地圖');
    return;
  }
  switchLayer(next);
});
document.getElementById('btnStart').addEventListener('click', () => {
  if (lastLatLng) map.setView(lastLatLng, 18);
});

// render()'s "points.length === lastPointCount → nothing new" guard never runs
// drawTrack() again once points goes back to 0, so clear the drawn layers here
// directly instead of waiting on the next poll to do it.
function clearMapVisuals() {
  polyline.setLatLngs([]);
  pointsLayer.clearLayers();
  sosLayer.clearLayers();
  markerEventLayer.clearLayers();
  if (lastMarker) { map.removeLayer(lastMarker); lastMarker = null; }
  lastPointRecordedAt = null;
  lastPointCount = 0;
  hasCenteredOnStart = false;
  startLatLng = null;
  lastRenderedPoints = null;
  document.getElementById('btnStart').disabled = true;
  document.getElementById('lastUpdate').textContent = '—';
  document.getElementById('lastPos').textContent = '—';
  const batteryEl = document.getElementById('lastBattery');
  batteryEl.textContent = '—';
  batteryEl.style.color = '';
  batteryEl.style.fontWeight = '';

  elevationFrozen = false;
  lastElevationHike = null;
  lastElevationPoints = null;
  const chartEl = document.getElementById('elevationChart');
  chartEl.classList.remove('show');
  delete chartEl.dataset.frozen;
  document.getElementById('elevationAxisY').innerHTML = '';
  document.getElementById('elevationScroll').innerHTML = '';
  document.getElementById('elevationAxisXText').textContent = '';
}

document.getElementById('btnClearTrack').addEventListener('click', async () => {
  if (!confirm('確定要刪除這個行程目前所有的軌跡點嗎？此動作無法復原。')) return;
  try {
    const res = await fetch(`/api/t/${shareToken}/track`, { method: 'DELETE' });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || '刪除失敗');
    clearMapVisuals();
  } catch (err) {
    alert(err.message);
  }
});

const colorPicker = document.getElementById('colorPicker');
const btnColorSwatch = document.getElementById('btnColorSwatch');

function refreshColorButtons() {
  btnColorSwatch.style.background = trackColor;
  colorPicker.querySelectorAll('button').forEach((btn) => {
    btn.classList.toggle('selected', btn.dataset.color === trackColor);
  });
}

TRACK_COLORS.forEach((color) => {
  const btn = document.createElement('button');
  btn.style.background = color;
  btn.dataset.color = color;
  btn.title = color;
  btn.addEventListener('click', () => {
    setTrackColor(color);
    refreshColorButtons();
    colorPicker.classList.remove('open');
  });
  colorPicker.appendChild(btn);
});
refreshColorButtons();

document.getElementById('btnColor').addEventListener('click', () => {
  colorPicker.classList.toggle('open');
});

// While a finger/cursor is down on the map (dragging or not), pausing at a spot for
// 2s shows the coordinates there — the timer restarts on every movement.
const PAUSE_MS = 2000;
let pauseTimer = null;
let touching = false;

function cancelPauseTimer() {
  if (pauseTimer) clearTimeout(pauseTimer);
  pauseTimer = null;
}

function armPauseTimer(latlng) {
  cancelPauseTimer();
  pauseTimer = setTimeout(() => {
    L.popup({ closeButton: true })
      .setLatLng(latlng)
      .setContent(`${latlng.lat.toFixed(6)}, ${latlng.lng.toFixed(6)}`)
      .openOn(map);
    pauseTimer = null;
  }, PAUSE_MS);
}

map.on('mousedown', (e) => {
  touching = true;
  armPauseTimer(e.latlng);
});
map.on('mousemove', (e) => {
  if (touching) armPauseTimer(e.latlng);
});
map.on('mouseup dragend zoomstart', () => {
  touching = false;
  cancelPauseTimer();
});

refresh();
setInterval(refresh, REFRESH_INTERVAL_MS);

// --- Signal points (手機可通訊點), set via the settings page ---
const SIGNAL_CARRIERS = [
  { key: 'cht', label: '中華電信', abbr: '中', color: '#ff5a5f' },
  { key: 'twm', label: '台灣大哥大', abbr: '台', color: '#ffb347' },
  { key: 'fet', label: '遠傳電信', abbr: '遠', color: '#c56cf0' },
  { key: 'other', label: '其他/不明電信', abbr: '他', color: '#a4b0be' },
];
const SIGNAL_DOT_STROKE = '#222'; // thin black-ish border on every carrier dot, regardless of fill color
// Canvas renderer batches up to ~2000 points x up to 4 carriers into one draw
// call instead of thousands of SVG nodes.
const signalCanvasRenderer = L.canvas({ padding: 0.5 });
// Text labels are real DOM tooltips, which don't scale to thousands at once —
// only shown zoomed in past this, and only for points inside the current view.
const SIGNAL_LABEL_MIN_ZOOM = 13;

function offsetLatLng(lat, lng, angleDeg, meters) {
  const rad = (angleDeg * Math.PI) / 180;
  const dLat = (meters * Math.cos(rad)) / 111320;
  const dLng = (meters * Math.sin(rad)) / (111320 * Math.cos((lat * Math.PI) / 180));
  return [lat + dLat, lng + dLng];
}

let signalMarkers = []; // [{ latlng, carrier }] — cached so pan/zoom can re-filter without refetching
let signalDotMarkers = []; // the actual L.circleMarker instances, resized as zoom changes
const signalLabelLayer = L.layerGroup();

function signalDotSizeForZoom(zoom) {
  return {
    radius: Math.max(3, Math.min(9, 3 + (zoom - 10) * 0.8)),
    weight: Math.max(0.75, Math.min(2.5, 0.75 + (zoom - 10) * 0.2)),
  };
}

function refreshSignalDotSize() {
  if (signalDotMarkers.length === 0) return;
  const { radius, weight } = signalDotSizeForZoom(map.getZoom());
  signalDotMarkers.forEach((m) => {
    m.setRadius(radius);
    m.setStyle({ weight });
  });
}

function refreshSignalLabels() {
  signalLabelLayer.clearLayers();
  if (map.getZoom() < SIGNAL_LABEL_MIN_ZOOM) return;

  const bounds = map.getBounds();
  signalMarkers.forEach(({ latlng, carrier }) => {
    if (!bounds.contains(latlng)) return;
    L.marker(latlng, {
      icon: L.divIcon({
        html: `<span class="signal-label-text" style="color:${carrier.color}">${carrier.abbr}</span>`,
        className: 'signal-label-icon', iconSize: [16, 16], iconAnchor: [-6, 8],
      }),
      interactive: false,
    }).addTo(signalLabelLayer);
  });
}

async function loadSignalPoints() {
  if (localStorage.getItem('signalPointsEnabled') !== '1') return;

  let carriers;
  try {
    carriers = JSON.parse(localStorage.getItem('signalPointsCarriers'));
    if (!Array.isArray(carriers)) throw new Error();
  } catch {
    carriers = SIGNAL_CARRIERS.map((c) => c.key);
  }
  if (carriers.length === 0) return;

  const legend = document.getElementById('signalLegend');
  legend.innerHTML = SIGNAL_CARRIERS.filter((c) => carriers.includes(c.key))
    .map((c) => {
      return `<span class="item"><span class="dot" style="background:${c.color};border:1px solid ${SIGNAL_DOT_STROKE};"></span>${c.label}</span>`;
    })
    .join('');
  legend.classList.add('visible');

  const res = await fetch('/api/signal-points');
  const points = await res.json();
  const signalLayer = L.layerGroup();
  signalMarkers = [];
  signalDotMarkers = [];

  const { radius, weight } = signalDotSizeForZoom(map.getZoom());
  points.forEach((p) => {
    const active = SIGNAL_CARRIERS.filter((c) => p[c.key] && carriers.includes(c.key));
    active.forEach((c, i) => {
      const angle = (360 / active.length) * i;
      const [lat, lng] = active.length > 1 ? offsetLatLng(p.lat, p.lng, angle, 12) : [p.lat, p.lng];
      const dot = L.circleMarker([lat, lng], {
        renderer: signalCanvasRenderer, radius, color: SIGNAL_DOT_STROKE, fillColor: c.color, fillOpacity: 0.9, weight,
      })
        .bindPopup(`${p.trail_name || ''} ${p.location_desc || ''}`.trim())
        .addTo(signalLayer);
      signalMarkers.push({ latlng: L.latLng(lat, lng), carrier: c });
      signalDotMarkers.push(dot);
    });
  });

  signalLayer.addTo(map);
  signalLabelLayer.addTo(map);
  map.on('zoomend moveend', refreshSignalLabels);
  map.on('zoomend', refreshSignalDotSize);
  refreshSignalLabels();
}
loadSignalPoints();

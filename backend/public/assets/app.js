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
rudyLayer.addTo(map);

const TRACK_COLORS = [
  '#e63946', '#f4a261', '#e9c46a', '#2a9d8f', '#06d6a0', '#118ab2',
  '#073b4c', '#7209b7', '#ff006e', '#fb8500', '#43aa8b', '#4d4d4d',
];
const PLANNED_ROUTE_COLOR = '#2d7dd2'; // uploaded GPX/KML route is always blue

let trackColor = localStorage.getItem('trackColor') || TRACK_COLORS[0];

const polyline = L.polyline([], { color: trackColor, weight: 4 }).addTo(map);
let lastMarker = null;
let sosLayer = L.layerGroup().addTo(map);
let markerEventLayer = L.layerGroup().addTo(map); // "我很好" / "停駐中" icons — otherwise indistinguishable from normal points
let pointsLayer = L.layerGroup().addTo(map);
let plannedLayer = L.layerGroup().addTo(map);
let lastPointCount = 0;
let hasCenteredOnStart = false;
let startLatLng = null;
let plannedRouteRendered = false;
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
function buildMarkerLabel() {
  if (!lastPointRecordedAt) return currentNickname;
  return `${currentNickname} · ${fmtRelativeTime(lastPointRecordedAt)}`;
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
  document.getElementById('zoomLevel').textContent = map.getZoom();
}
map.on('zoom', updateZoomLevel);
updateZoomLevel();

function fmtDateTime(iso) {
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
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
  const latlngs = points.map((p) => [p.lat, p.lng]);
  const last = points[points.length - 1];
  const sosPoints = points.filter((p) => p.marker_type === 'sos');
  const eventPoints = points.filter((p) => p.marker_type === 'safe' || p.marker_type === 'camping');

  polyline.setStyle({ color: trackColor });
  polyline.setLatLngs(latlngs);

  pointsLayer.clearLayers();
  points.forEach((p) => {
    const label = MARKER_EVENT_LABELS[p.marker_type];
    L.circleMarker([p.lat, p.lng], {
      radius: 4, color: trackColor, fillColor: '#fff', fillOpacity: 1, weight: 2,
    })
      .bindPopup(
        `${p.lat.toFixed(6)}, ${p.lng.toFixed(6)}<br>${fmtDateTime(p.recorded_at)}` +
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
      .bindPopup(`${MARKER_EVENT_LABELS[p.marker_type]}<br>${fmtDateTime(p.recorded_at)}`)
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
    }).addTo(sosLayer);
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

function updateAlertBanner(alert) {
  const el = document.getElementById('alertBanner');
  if (!alert || alert.level === 'green' || !alert.message) {
    el.classList.remove('show', 'yellow', 'orange', 'red');
  } else {
    const icon = { yellow: '🟡', orange: '🟠', red: '🔴' }[alert.level] || '';
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
    🟡 黃：夜間（${cfg.nightStart}–${cfg.nightEnd}）斷訊，或斷訊超過 ${cfg.campingSilenceHours} 小時但判斷為停駐<br>
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
function updateSosAlert(alert) {
  const active = !!alert && alert.level === 'red' && alert.reason === 'sos';
  document.getElementById('sosBanner').style.display = active ? 'block' : 'none';
  document.getElementById('sosFrame').classList.toggle('show', active);

  if (active && !sosBeepTimer) {
    sosBeep();
    sosBeepTimer = setInterval(sosBeep, 1500);
  } else if (!active && sosBeepTimer) {
    clearInterval(sosBeepTimer);
    sosBeepTimer = null;
  }
}

function updateHikeStatus(hike) {
  const el = document.getElementById('hikeStatus');
  if (hike.status === 'ended') {
    el.textContent = hike.ended_at ? `已結束（${fmtDateTime(hike.ended_at)}）` : '已結束';
  } else if (hike.paused) {
    el.textContent = '進行中（定位已暫停）';
  } else {
    el.textContent = '進行中';
  }
}

function render(data) {
  const { hike, points, alert } = data;
  currentNickname = hike.nickname || hike.hiker_name;
  document.getElementById('hikeName').textContent = `${currentNickname} · ${hike.name}`;
  updateAlertBanner(alert); // time-based, so must update even when no new points arrived
  updateSosAlert(alert);
  updateHikeStatus(hike);

  if (hike.planned_route && !plannedRouteRendered) {
    plannedRouteRendered = true;
    renderPlannedRoute(hike.planned_route);
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

  if (!hasCenteredOnStart) {
    hasCenteredOnStart = true;
    map.setView(startLatLng, 18);
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

function switchLayer(layer) {
  document.getElementById('btnRudy').classList.toggle('active', layer === 'rudy');
  document.getElementById('btnOsm').classList.toggle('active', layer === 'osm');
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

document.getElementById('btnRudy').addEventListener('click', () => switchLayer('rudy'));
document.getElementById('btnOsm').addEventListener('click', () => switchLayer('osm'));
document.getElementById('btnStart').addEventListener('click', () => {
  if (startLatLng) map.setView(startLatLng, 18);
});

// render()'s "points.length === lastPointCount → nothing new" guard never runs
// drawTrack() again once points goes back to 0, so clear the drawn layers here
// directly instead of waiting on the next poll to do it.
function clearMapVisuals() {
  polyline.setLatLngs([]);
  pointsLayer.clearLayers();
  sosLayer.clearLayers();
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

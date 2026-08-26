const shareToken = location.pathname.split('/').filter(Boolean)[1];
document.getElementById('backLink').href = `/t/${shareToken}`;
document.getElementById('exportGpx').href = `/api/t/${shareToken}/export.gpx`;
document.getElementById('exportKml').href = `/api/t/${shareToken}/export.kml`;

const TRACK_COLORS = [
  '#e63946', '#f4a261', '#e9c46a', '#2a9d8f', '#06d6a0', '#118ab2',
  '#073b4c', '#7209b7', '#ff006e', '#fb8500', '#43aa8b', '#4d4d4d',
];

function setStatus(el, text, kind) {
  el.textContent = text;
  el.className = `status ${kind || ''}`;
}

// --- Alert level thresholds ---
const ALERT_FIELD_IDS = {
  greenHours: 'cfgGreenHours',
  nightStart: 'cfgNightStart',
  nightEnd: 'cfgNightEnd',
  campingSilenceHours: 'cfgCampingSilenceHours',
  dayOrangeStart: 'cfgDayOrangeStart',
  dayOrangeEnd: 'cfgDayOrangeEnd',
  redDayHours: 'cfgRedDayHours',
};

async function loadAlertConfig() {
  try {
    const res = await fetch(`/api/t/${shareToken}/alert-config`);
    const data = await res.json();
    for (const [key, elId] of Object.entries(ALERT_FIELD_IDS)) {
      document.getElementById(elId).value = data.config[key];
    }
  } catch {
    // leave fields blank; save will fail loudly if the user tries anyway
  }
}
loadAlertConfig();

document.getElementById('btnSaveAlertConfig').addEventListener('click', async () => {
  const statusEl = document.getElementById('alertConfigStatus');
  const body = {};
  for (const [key, elId] of Object.entries(ALERT_FIELD_IDS)) {
    const el = document.getElementById(elId);
    body[key] = el.type === 'number' ? Number(el.value) : el.value;
  }
  try {
    const res = await fetch(`/api/t/${shareToken}/alert-config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || '儲存失敗');
    // Server clamps out-of-range values — reflect what actually got saved.
    for (const [key, elId] of Object.entries(ALERT_FIELD_IDS)) {
      document.getElementById(elId).value = data.config[key];
    }
    setStatus(statusEl, '已儲存', 'ok');
  } catch (err) {
    setStatus(statusEl, err.message, 'err');
  }
});

// --- Upload planned route (GPX/KML) ---
document.getElementById('btnUploadRoute').addEventListener('click', async () => {
  const input = document.getElementById('routeFile');
  const statusEl = document.getElementById('routeStatus');
  const file = input.files[0];
  if (!file) return setStatus(statusEl, '請先選擇檔案', 'err');

  try {
    const text = await file.text();
    const res = await fetch(`/api/t/${shareToken}/route`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain' },
      body: text,
    });
    if (!res.ok) throw new Error((await res.json()).error || '上傳失敗');
    setStatus(statusEl, '上傳成功，回地圖頁面即可看到路線', 'ok');
  } catch (err) {
    setStatus(statusEl, err.message, 'err');
  }
});

// --- Update RudyMap data (admin) ---
document.getElementById('btnUpdateMap').addEventListener('click', async () => {
  const passwordEl = document.getElementById('adminPassword');
  const statusEl = document.getElementById('mapStatus');
  const btn = document.getElementById('btnUpdateMap');

  btn.disabled = true;
  setStatus(statusEl, '更新中，可能需要幾分鐘…', '');
  try {
    const res = await fetch('/api/admin/update-rudymap', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: passwordEl.value }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || '更新失敗');
    setStatus(statusEl, `更新完成：${data.updated.join('、')}`, 'ok');
    passwordEl.value = '';
  } catch (err) {
    setStatus(statusEl, err.message, 'err');
  } finally {
    btn.disabled = false;
  }
});

// --- Track color ---
const colorGrid = document.getElementById('colorGrid');
let trackColor = localStorage.getItem('trackColor') || TRACK_COLORS[0];

function refreshColorGrid() {
  colorGrid.querySelectorAll('button').forEach((b) => {
    b.classList.toggle('selected', b.dataset.color === trackColor);
  });
}

TRACK_COLORS.forEach((color) => {
  const b = document.createElement('button');
  b.style.background = color;
  b.dataset.color = color;
  b.addEventListener('click', () => {
    trackColor = color;
    localStorage.setItem('trackColor', color);
    refreshColorGrid();
  });
  colorGrid.appendChild(b);
});
refreshColorGrid();

// --- Signal points (手機可通訊點) ---
const SIGNAL_CARRIERS = [
  { key: 'cht', label: '中華電信', abbr: '中', color: '#ff5a5f' },
  { key: 'twm', label: '台灣大哥大', abbr: '台', color: '#ffb347' },
  { key: 'fet', label: '遠傳電信', abbr: '遠', color: '#c56cf0' },
  { key: 'other', label: '其他/不明電信', abbr: '他', color: '#a4b0be' },
];

const signalToggle = document.getElementById('signalToggle');
const carrierList = document.getElementById('carrierList');

signalToggle.checked = localStorage.getItem('signalPointsEnabled') === '1';
signalToggle.addEventListener('change', () => {
  localStorage.setItem('signalPointsEnabled', signalToggle.checked ? '1' : '0');
});

let enabledCarriers;
try {
  enabledCarriers = JSON.parse(localStorage.getItem('signalPointsCarriers'));
  if (!Array.isArray(enabledCarriers)) throw new Error();
} catch {
  enabledCarriers = SIGNAL_CARRIERS.map((c) => c.key); // default: all on
}

SIGNAL_CARRIERS.forEach(({ key, label, color }) => {
  const row = document.createElement('div');
  row.className = 'carrierRow';
  row.innerHTML = `
    <span class="dot" style="background:${color};border:1px solid #222;"></span>
    <span>${label}</span>
    <input type="checkbox" ${enabledCarriers.includes(key) ? 'checked' : ''} />
  `;
  row.querySelector('input').addEventListener('change', (e) => {
    enabledCarriers = e.target.checked
      ? [...new Set([...enabledCarriers, key])]
      : enabledCarriers.filter((k) => k !== key);
    localStorage.setItem('signalPointsCarriers', JSON.stringify(enabledCarriers));
  });
  carrierList.appendChild(row);
});

document.getElementById('btnUpdateSignalPoints').addEventListener('click', async () => {
  const passwordEl = document.getElementById('signalAdminPassword');
  const statusEl = document.getElementById('signalStatus');
  const btn = document.getElementById('btnUpdateSignalPoints');

  btn.disabled = true;
  setStatus(statusEl, '檢查中…', '');
  try {
    const res = await fetch('/api/admin/update-signal-points', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: passwordEl.value }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || '更新失敗');
    const lines = data.results.map((r) => {
      if (r.error) return `${r.label}：失敗（${r.error}）`;
      if (r.changed) return `${r.label}：已更新，共 ${r.count} 筆`;
      return `${r.label}：已是最新，沒有變更`;
    });
    const anyError = data.results.some((r) => r.error);
    setStatus(statusEl, lines.join('\n'), anyError ? 'err' : 'ok');
    passwordEl.value = '';
  } catch (err) {
    setStatus(statusEl, err.message, 'err');
  } finally {
    btn.disabled = false;
  }
});

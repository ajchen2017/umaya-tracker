const https = require('https');
const AdmZip = require('adm-zip');
const { XMLParser } = require('fast-xml-parser');
const pool = require('../db/pool');

function fetchBuffer(url) {
  return new Promise((resolve, reject) => {
    // These gov sites 404 requests with no User-Agent header (Node sends none by default).
    const opts = { headers: { 'User-Agent': 'Mozilla/5.0 (compatible; tracker-bot/1.0)' } };
    https
      .get(url, opts, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          return fetchBuffer(res.headers.location).then(resolve, reject);
        }
        if (res.statusCode !== 200) return reject(new Error(`HTTP ${res.statusCode} for ${url}`));
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({ buffer: Buffer.concat(chunks), headers: res.headers }));
        res.on('error', reject);
      })
      .on('error', reject);
  });
}

function titleFromHeaders(headers) {
  const cd = headers['content-disposition'] || '';
  const match = cd.match(/filename\*?=(?:UTF-8'')?"?([^";]+)"?/i);
  if (match) return decodeURIComponent(match[1]).replace(/\.ods$/i, '');
  // ysnp.gov.tw / taroko.gov.tw don't send a filename in Content-Disposition —
  // fall back to ETag/Last-Modified so a re-upload at the same URL still counts
  // as "changed" (the URL alone never changes, so it can't be the version key).
  if (headers.etag) return `etag:${headers.etag}`;
  if (headers['last-modified']) return `modified:${headers['last-modified']}`;
  return null;
}

// fast-xml-parser auto-types numeric-looking text (e.g. <text:p>1</text:p> -> 1, not "1"),
// so every branch here has to tolerate a bare number, not just strings/objects.
function textPValue(x) {
  if (x == null) return '';
  if (typeof x === 'object') return (x['#text'] ?? '').toString();
  return x.toString();
}
function cellText(cell) {
  if (cell == null) return '';
  const p = cell['text:p'];
  if (p == null) return '';
  if (Array.isArray(p)) return p.map(textPValue).join(' ').trim();
  return textPValue(p).trim();
}
function repeatCount(cell) {
  const n = cell?.['@_table:number-columns-repeated'];
  return n ? parseInt(n, 10) : 1;
}
function rowCells(row, max = 20) {
  const cells = [];
  for (const cell of row['table:table-cell'] || []) {
    const repeat = repeatCount(cell);
    const text = cellText(cell);
    for (let i = 0; i < Math.min(repeat, 3); i++) cells.push(text);
    if (cells.length > max) break;
  }
  return cells;
}

function parseXmlDoc(xmlText, extraArrayTags = []) {
  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: '@_',
    isArray: (name) =>
      ['table:table-row', 'table:table-cell', 'table:covered-table-cell', 'text:p', 'table:table', ...extraArrayTags].includes(name),
  });
  return parser.parse(xmlText);
}

function styleColorMap(doc) {
  const autoStyles = doc['office:document-content']['office:automatic-styles']['style:style'] || [];
  const map = {};
  for (const s of Array.isArray(autoStyles) ? autoStyles : [autoStyles]) {
    const name = s['@_style:name'];
    const tp = s['style:text-properties'];
    const props = Array.isArray(tp) ? tp[0] : tp;
    const color = props?.['@_fo:color'];
    if (color) map[name] = color;
  }
  return map;
}

// ---------- 林業保育署 (source: forestry) ----------
const FORESTRY_URL = 'https://www.forest.gov.tw/0004548/file/203426';

function parseForestry(buffer) {
  const zip = new AdmZip(buffer);
  const xml = zip.getEntry('content.xml').getData().toString('utf8');
  const doc = parseXmlDoc(xml);
  const rows = doc['office:document-content']['office:body']['office:spreadsheet']['table:table'][0]['table:table-row'];

  const points = [];
  for (const row of rows) {
    const cells = rowCells(row, 15);
    const seq = cells[0]?.trim();
    if (!seq || !/^\d+$/.test(seq)) continue;
    const lng = parseFloat(cells[8]);
    const lat = parseFloat(cells[9]);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
    points.push({
      seq: parseInt(seq, 10), trailName: cells[2], branch: cells[3], locationDesc: cells[4], county: cells[5],
      lat, lng, cht: !!cells[10], fet: !!cells[11], twm: !!cells[12], other: false, remark: cells[13] || '',
    });
  }
  return points;
}

// ---------- 玉山國家公園 (source: ysnp) ----------
const YSNP_URL =
  'https://www.ysnp.gov.tw/ckfinder/userfiles/files/%e7%8e%89%e5%b1%b1%e5%9c%8b%e5%ae%b6%e5%85%ac%e5%9c%92%e6%ad%a5%e9%81%93%e9%80%9a%e8%a8%8a%e9%bb%9e%e5%bd%99%e6%95%b4%e8%a1%a8206%e8%99%95%201150408%20%e6%96%b0%e5%a2%9e%e9%81%a0%e5%82%b358%e8%99%95%20%e7%84%a1%e9%9a%b1%e8%97%8f.ods';

function parseYsnp(buffer) {
  const zip = new AdmZip(buffer);
  const xml = zip.getEntry('content.xml').getData().toString('utf8');
  const doc = parseXmlDoc(xml, ['style:style', 'style:text-properties']);
  const colorMap = styleColorMap(doc);
  const isRed = (styleName) => {
    const c = colorMap[styleName];
    return c === '#ff0000' || c === '#c9211e';
  };

  const table = doc['office:document-content']['office:body']['office:spreadsheet']['table:table'][0];
  const rows = table['table:table-row'];

  const points = [];
  let lastTrailName = '';
  for (let i = 3; i < rows.length; i++) {
    const rawCells = rows[i]['table:table-cell'] || [];
    let cells = rawCells.map(cellText);
    while (cells.length && !cells[cells.length - 1]) cells.pop(); // drop trailing empty-pad cells
    if (cells.length === 0) continue;

    // Column order (from the header rows): 步道系統, 步道名稱, 設置手機標示牌地點, X, Y, 北緯, 東經
    // — but 步道系統/步道名稱 are merge-carried: the whole leading cell is OMITTED
    // (not just blank) on continuation rows, so remaining columns shift left.
    // 步道系統 itself isn't used below (not safety-relevant), only 步道名稱+位置.
    if (cells.length < 4) continue; // not a coordinate row (e.g. a lone section label)
    const lat = parseFloat(cells[cells.length - 2]);
    const lng = parseFloat(cells[cells.length - 1]);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;

    let locationDesc = '';
    if (cells.length >= 7) { lastTrailName = cells[1]; locationDesc = cells[2]; }
    else if (cells.length === 6) { lastTrailName = cells[0]; locationDesc = cells[1]; }
    else if (cells.length === 5) { locationDesc = cells[0]; }

    // Approximate: carrier is only distinguishable by font color (red = 2025 遠傳
    // survey), and color isn't reliably on one specific column, so use whichever
    // populated cell we hit first. Good enough to separate "confirmed FarEasTone"
    // from everything else, not exact to the source's row-by-row color coding.
    const firstPopulated = rawCells.find((c) => cellText(c));
    const fet = firstPopulated ? isRed(firstPopulated['@_table:style-name']) : false;

    points.push({
      seq: null, trailName: lastTrailName, branch: '', locationDesc, county: '',
      lat, lng, cht: false, fet, twm: false, other: !fet, remark: '',
    });
  }
  return points;
}

// ---------- 太魯閣國家公園 (source: taroko) ----------
const TAROKO_URL = 'https://www.taroko.gov.tw/uploads/files/183c3d50868ad89aa7b1766977747080.ods';

function looksLikeLng(n) { return n > 118 && n < 123; }
function looksLikeLat(n) { return n > 21 && n < 26; }

function parseTaroko(buffer) {
  const zip = new AdmZip(buffer);
  const xml = zip.getEntry('content.xml').getData().toString('utf8');
  const doc = parseXmlDoc(xml);
  const tables = doc['office:document-content']['office:body']['office:spreadsheet']['table:table'];

  const points = [];
  for (const table of tables) {
    const trailSystem = table['@_table:name'] || '';
    const rows = table['table:table-row'] || [];
    for (const row of rows) {
      const cells = rowCells(row, 10).filter((_, idx, arr) => true);
      const nums = cells.map((c) => parseFloat(c));
      // Find the last two adjacent cells that look like (lng, lat) in that order.
      let lng = null, lat = null, coordIdx = -1;
      for (let i = 0; i < nums.length - 1; i++) {
        if (looksLikeLng(nums[i]) && looksLikeLat(nums[i + 1])) {
          lng = nums[i]; lat = nums[i + 1]; coordIdx = i;
        }
      }
      if (lng == null) continue;
      const locationDesc = cells.slice(0, coordIdx - 2).filter(Boolean).join(' ');
      points.push({
        seq: null, trailName: trailSystem, branch: '', locationDesc, county: '',
        lat, lng, cht: false, fet: false, twm: false, other: true, remark: '',
      });
    }
  }
  return points;
}

// ---------- shared: title check + DB write ----------
async function getStoredTitle(sourceKey) {
  const { rows } = await pool.query('SELECT value FROM app_metadata WHERE key = $1', [`signal_points_title_${sourceKey}`]);
  return rows[0]?.value;
}
async function setStoredTitle(sourceKey, title) {
  await pool.query(
    `INSERT INTO app_metadata (key, value) VALUES ($1, $2) ON CONFLICT (key) DO UPDATE SET value = $2`,
    [`signal_points_title_${sourceKey}`, title]
  );
}

async function replaceSourcePoints(sourceKey, points) {
  await pool.query('DELETE FROM signal_points WHERE source = $1', [sourceKey]);
  for (const p of points) {
    await pool.query(
      `INSERT INTO signal_points (source, seq, trail_name, branch, location_desc, county, lat, lng, cht, fet, twm, other, remark)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)`,
      [sourceKey, p.seq, p.trailName, p.branch, p.locationDesc, p.county, p.lat, p.lng, p.cht, p.fet, p.twm, p.other, p.remark]
    );
  }
}

const CHECKABLE_SOURCES = [
  { key: 'forestry', url: FORESTRY_URL, label: '林業保育署', parse: parseForestry },
  { key: 'ysnp', url: YSNP_URL, label: '玉山國家公園', parse: parseYsnp },
  { key: 'taroko', url: TAROKO_URL, label: '太魯閣國家公園', parse: parseTaroko },
];

// Checks each checkable source's remote title against what we last imported;
// re-downloads and replaces only that source's rows when it changed. 雪霸 (PDF,
// user-supplied, no stable URL to diff against) is seeded once separately —
// see src/db/importSheiPaSignalPoints.js — and isn't part of this loop.
async function updateSignalPoints() {
  const results = [];
  for (const source of CHECKABLE_SOURCES) {
    try {
      const { headers, buffer } = await fetchBuffer(source.url);
      const title = titleFromHeaders(headers) || source.url;
      const stored = await getStoredTitle(source.key);

      if (stored === title) {
        results.push({ source: source.key, label: source.label, changed: false, title });
        continue;
      }

      const points = source.parse(buffer);
      if (points.length === 0) throw new Error('解析結果是 0 筆，先不覆蓋現有資料');

      await replaceSourcePoints(source.key, points);
      await setStoredTitle(source.key, title);
      results.push({ source: source.key, label: source.label, changed: true, title, count: points.length });
    } catch (err) {
      results.push({ source: source.key, label: source.label, error: err.message });
    }
  }
  return results;
}

module.exports = { updateSignalPoints, parseForestry, parseYsnp, parseTaroko };

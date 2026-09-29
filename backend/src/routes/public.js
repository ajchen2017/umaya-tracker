const express = require('express');
const pool = require('../db/pool');
const { computeAlertLevel, resolveConfig, RANGES } = require('../lib/alertLevel');
const { checkAdminPassword } = require('../middleware/auth');
const { effectiveIntervalSeconds } = require('../lib/intervalInference');

const router = express.Router();

// The share token is per-user (issued at registration), not per-hike, so a
// family's saved link keeps working across every trip the hiker records —
// resolves to the most recently started hike. Deliberately NOT "prefer any
// active hike": a hike whose end-hike call failed (e.g. network timeout) can
// be stuck "active" forever with no retry, and preferring it would keep
// showing that stale orphan as 進行中 even after a later hike properly ended.
// Recency alone is correct as long as at most one hike is genuinely active
// at a time, which the app already enforces client-side.
async function findHikeForShareToken(shareToken) {
  const { rows } = await pool.query(
    `SELECT h.*, u.display_name AS hiker_name
     FROM hikes h JOIN users u ON u.id = h.user_id
     WHERE u.share_token = $1
     ORDER BY h.started_at DESC
     LIMIT 1`,
    [shareToken]
  );
  return rows[0] || null;
}

// Public, no auth: family view via share link.
router.get('/:shareToken', async (req, res) => {
  const hike = await findHikeForShareToken(req.params.shareToken);
  if (!hike) return res.status(404).json({ error: 'Not found' });

  const pointsResult = await pool.query(
    `SELECT lat, lng, altitude, accuracy, marker_type, battery_pct, recorded_at
     FROM track_points WHERE hike_id = $1 ORDER BY recorded_at ASC`,
    [hike.id]
  );

  const alert = computeAlertLevel(pointsResult.rows, new Date(), hike.alert_config, hike.status === 'ended');
  hike.interval_seconds = effectiveIntervalSeconds(hike, pointsResult.rows);
  // Route names/ids only — the page polls every 30s, so each file's content is fetched once
  // separately (GET /routes/:routeId) instead of re-sending every MB of it on every poll.
  const routesResult = await pool.query('SELECT id, name FROM hike_routes WHERE hike_id = $1 ORDER BY id', [hike.id]);
  const waypointsResult = await pool.query(
    'SELECT name, lat, lng, altitude, recorded_at FROM hike_waypoints WHERE hike_id = $1 ORDER BY recorded_at',
    [hike.id]
  );
  res.json({ hike, points: pointsResult.rows, alert, routes: routesResult.rows, waypoints: waypointsResult.rows });
});

router.get('/:shareToken/routes/:routeId', async (req, res) => {
  const hike = await findHikeForShareToken(req.params.shareToken);
  if (!hike) return res.status(404).json({ error: 'Not found' });
  const { rows } = await pool.query(
    'SELECT content FROM hike_routes WHERE id = $1 AND hike_id = $2',
    [req.params.routeId, hike.id]
  );
  if (!rows[0]) return res.status(404).json({ error: 'Not found' });
  res.set('Content-Type', 'application/xml; charset=utf-8');
  res.send(rows[0].content);
});

// Public read of the resolved (defaults-applied) alert thresholds + valid ranges,
// for the settings page to pre-fill its form.
router.get('/:shareToken/alert-config', async (req, res) => {
  const hike = await findHikeForShareToken(req.params.shareToken);
  if (!hike) return res.status(404).json({ error: 'Not found' });
  res.json({ config: resolveConfig(hike.alert_config), ranges: RANGES });
});

// Tuning alert thresholds is admin-gated, not share-link-gated: with multiple
// hikers each reachable via their own share link, letting anyone with a link
// self-tune thresholds produced inconsistent, confusing behavior across hikes.
router.put('/:shareToken/alert-config', async (req, res) => {
  if (!checkAdminPassword(req, res)) return;
  const hike = await findHikeForShareToken(req.params.shareToken);
  if (!hike) return res.status(404).json({ error: 'Not found' });
  const resolved = resolveConfig(req.body);
  await pool.query('UPDATE hikes SET alert_config = $1 WHERE id = $2', [JSON.stringify(resolved), hike.id]);
  res.json({ ok: true, config: resolved });
});

// Share-link-gated, same trust model as route upload: wipe every recorded track
// point for the current/most recent hike (e.g. clearing test data), so it starts
// recording fresh. Irreversible — no undo. New points the phone is still sending
// land normally afterwards, since this only deletes what's already in the table.
router.delete('/:shareToken/track', async (req, res) => {
  const hike = await findHikeForShareToken(req.params.shareToken);
  if (!hike) return res.status(404).json({ error: 'Not found' });
  const { rowCount } = await pool.query('DELETE FROM track_points WHERE hike_id = $1', [hike.id]);
  res.json({ ok: true, deleted: rowCount });
});

// Public route upload: whoever has the share link can attach a reference route
// (in case the hiker forgot to load it on the phone before setting off).
router.post('/:shareToken/routes', express.text({ type: '*/*', limit: '5mb' }), async (req, res) => {
  const content = req.body;
  if (!content || typeof content !== 'string' || !(content.includes('<gpx') || content.includes('<kml'))) {
    return res.status(400).json({ error: 'Request body must be a GPX or KML (XML) document' });
  }

  const hike = await findHikeForShareToken(req.params.shareToken);
  if (!hike) return res.status(404).json({ error: 'Not found' });
  const { rows } = await pool.query(
    'INSERT INTO hike_routes (hike_id, name, content) VALUES ($1, $2, $3) RETURNING id, name',
    [hike.id, String(req.query.name || '留守人上傳').slice(0, 200), content]
  );
  res.status(201).json(rows[0]);
});

function escapeXml(str) {
  return String(str ?? '').replace(/[<>&'"]/g, (c) => (
    { '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;' }[c]
  ));
}

async function loadHikeWithPoints(shareToken) {
  const hike = await findHikeForShareToken(shareToken);
  if (!hike) return null;

  const pointsResult = await pool.query(
    `SELECT lat, lng, altitude, marker_type, recorded_at
     FROM track_points WHERE hike_id = $1 ORDER BY recorded_at ASC`,
    [hike.id]
  );
  return { hike, points: pointsResult.rows };
}

// Export the hiker's actual recorded track (not the planned route) as GPX/KML,
// so rescuers can load the real path walked into their own devices/systems.
router.get('/:shareToken/export.gpx', async (req, res) => {
  const data = await loadHikeWithPoints(req.params.shareToken);
  if (!data) return res.status(404).json({ error: 'Not found' });
  const { hike, points } = data;

  const trkpts = points
    .map((p) => {
      const ele = p.altitude != null ? `<ele>${p.altitude}</ele>` : '';
      return `<trkpt lat="${p.lat}" lon="${p.lng}">${ele}<time>${new Date(p.recorded_at).toISOString()}</time></trkpt>`;
    })
    .join('');
  const sosWpts = points
    .filter((p) => p.marker_type === 'sos')
    .map((p) => `<wpt lat="${p.lat}" lon="${p.lng}"><name>SOS</name><time>${new Date(p.recorded_at).toISOString()}</time></wpt>`)
    .join('');

  const gpx = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="tracker" xmlns="http://www.topografix.com/GPX/1/1">
${sosWpts}<trk><name>${escapeXml(hike.hiker_name)} - ${escapeXml(hike.name)}</name><trkseg>${trkpts}</trkseg></trk>
</gpx>`;

  res.set('Content-Type', 'application/gpx+xml; charset=utf-8');
  res.set('Content-Disposition', `attachment; filename="track.gpx"; filename*=UTF-8''${encodeURIComponent(hike.name)}.gpx`);
  res.send(gpx);
});

router.get('/:shareToken/export.kml', async (req, res) => {
  const data = await loadHikeWithPoints(req.params.shareToken);
  if (!data) return res.status(404).json({ error: 'Not found' });
  const { hike, points } = data;

  const coords = points.map((p) => `${p.lng},${p.lat},${p.altitude ?? 0}`).join(' ');
  const sosPlacemarks = points
    .filter((p) => p.marker_type === 'sos')
    .map((p) => `<Placemark><name>SOS</name><Point><coordinates>${p.lng},${p.lat},${p.altitude ?? 0}</coordinates></Point></Placemark>`)
    .join('');

  const kml = `<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
<Document>
<name>${escapeXml(hike.hiker_name)} - ${escapeXml(hike.name)}</name>
<Placemark><name>軌跡</name><LineString><tessellate>1</tessellate><coordinates>${coords}</coordinates></LineString></Placemark>
${sosPlacemarks}
</Document>
</kml>`;

  res.set('Content-Type', 'application/vnd.google-earth.kml+xml; charset=utf-8');
  res.set('Content-Disposition', `attachment; filename="track.kml"; filename*=UTF-8''${encodeURIComponent(hike.name)}.kml`);
  res.send(kml);
});

module.exports = router;

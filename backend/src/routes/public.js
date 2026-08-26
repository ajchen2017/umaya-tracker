const express = require('express');
const pool = require('../db/pool');
const { computeAlertLevel } = require('../lib/alertLevel');

const router = express.Router();

// Public, no auth: family view via share link.
router.get('/:shareToken', async (req, res) => {
  const hikeResult = await pool.query(
    `SELECT h.id, h.name, h.status, h.started_at, h.ended_at, h.planned_route, h.alert_config, u.display_name AS hiker_name
     FROM hikes h JOIN users u ON u.id = h.user_id
     WHERE h.share_token = $1`,
    [req.params.shareToken]
  );
  const hike = hikeResult.rows[0];
  if (!hike) return res.status(404).json({ error: 'Not found' });

  const pointsResult = await pool.query(
    `SELECT lat, lng, altitude, accuracy, marker_type, battery_pct, recorded_at
     FROM track_points WHERE hike_id = $1 ORDER BY recorded_at ASC`,
    [hike.id]
  );

  const alert = computeAlertLevel(pointsResult.rows, new Date(), hike.alert_config);
  res.json({ hike, points: pointsResult.rows, alert });
});

// Public route upload: whoever has the share link can attach/replace the planned
// route (in case the hiker forgot to upload it from the phone before setting off).
router.put('/:shareToken/route', express.text({ type: '*/*', limit: '5mb' }), async (req, res) => {
  const route = req.body;
  if (!route || typeof route !== 'string' || !(route.includes('<gpx') || route.includes('<kml'))) {
    return res.status(400).json({ error: 'Request body must be a GPX or KML (XML) document' });
  }

  const { rows } = await pool.query(
    'UPDATE hikes SET planned_route = $1 WHERE share_token = $2 RETURNING id',
    [route, req.params.shareToken]
  );
  if (!rows[0]) return res.status(404).json({ error: 'Not found' });
  res.json({ ok: true });
});

function escapeXml(str) {
  return String(str ?? '').replace(/[<>&'"]/g, (c) => (
    { '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;' }[c]
  ));
}

async function loadHikeWithPoints(shareToken) {
  const hikeResult = await pool.query(
    `SELECT h.id, h.name, u.display_name AS hiker_name
     FROM hikes h JOIN users u ON u.id = h.user_id
     WHERE h.share_token = $1`,
    [shareToken]
  );
  const hike = hikeResult.rows[0];
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

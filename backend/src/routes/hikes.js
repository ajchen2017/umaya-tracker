const express = require('express');
const pool = require('../db/pool');
const { requireAuth } = require('../middleware/auth');

const router = express.Router();

router.post('/', requireAuth, async (req, res) => {
  const { name, nickname, intervalSeconds } = req.body;
  if (!name) return res.status(400).json({ error: 'name is required' });

  const { rows } = await pool.query(
    'INSERT INTO hikes (user_id, name, nickname, interval_seconds) VALUES ($1, $2, $3, $4) RETURNING *',
    [req.userId, name, nickname || null, intervalSeconds || null]
  );
  res.status(201).json(rows[0]);
});

router.patch('/:id/end', requireAuth, async (req, res) => {
  const { rows } = await pool.query(
    `UPDATE hikes SET status = 'ended', ended_at = now()
     WHERE id = $1 AND user_id = $2 RETURNING *`,
    [req.params.id, req.userId]
  );
  if (!rows[0]) return res.status(404).json({ error: 'Hike not found' });
  // Reference routes are only shared for the duration of the hike.
  await pool.query('DELETE FROM hike_routes WHERE hike_id = $1', [rows[0].id]);
  res.json(rows[0]);
});

// Resumes a hike that had already been ended (接續舊行程 can target any past hike,
// not just one still active server-side — e.g. the app was reinstalled, or the
// hiker set back out after "ending" by mistake). No-op if it's already active.
router.patch('/:id/reactivate', requireAuth, async (req, res) => {
  const { rows } = await pool.query(
    `UPDATE hikes SET status = 'active', ended_at = NULL, paused = false
     WHERE id = $1 AND user_id = $2 RETURNING *`,
    [req.params.id, req.userId]
  );
  if (!rows[0]) return res.status(404).json({ error: 'Hike not found' });
  res.json(rows[0]);
});

// Best-effort: reflects GPS-pause state on the family web page. If the phone is
// offline when this fires, the call just fails silently — the web page keeps
// showing whatever the last successfully-delivered status was, same as every
// other signal from the phone (no retry, no polling for "did it apply").
router.patch('/:id/pause-state', requireAuth, async (req, res) => {
  const { paused } = req.body;
  if (typeof paused !== 'boolean') return res.status(400).json({ error: 'paused (boolean) is required' });

  const { rows } = await pool.query(
    'UPDATE hikes SET paused = $1 WHERE id = $2 AND user_id = $3 RETURNING id',
    [paused, req.params.id, req.userId]
  );
  if (!rows[0]) return res.status(404).json({ error: 'Hike not found' });
  res.json({ ok: true });
});

// Same best-effort contract as pause-state — reflects a mid-hike interval change
// (定位頻率 dialog) on the family page's elevation-chart grid.
router.patch('/:id/interval', requireAuth, async (req, res) => {
  const { intervalSeconds } = req.body;
  if (!Number.isInteger(intervalSeconds) || intervalSeconds <= 0) {
    return res.status(400).json({ error: 'intervalSeconds (positive integer) is required' });
  }

  const { rows } = await pool.query(
    'UPDATE hikes SET interval_seconds = $1, interval_updated_at = now() WHERE id = $2 AND user_id = $3 RETURNING id',
    [intervalSeconds, req.params.id, req.userId]
  );
  if (!rows[0]) return res.status(404).json({ error: 'Hike not found' });
  res.json({ ok: true });
});

// Reference GPX/KML routes (several per hike), each uploaded as a raw XML body with its display
// name in ?name=. Shown on the guardian page alongside the live track; deleted when the hike ends.
router.post('/:id/routes', requireAuth, express.text({ type: '*/*', limit: '5mb' }), async (req, res) => {
  const content = req.body;
  if (!isRouteDocument(content)) {
    return res.status(400).json({ error: 'Request body must be a GPX or KML (XML) document' });
  }
  const owns = await pool.query('SELECT id FROM hikes WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
  if (!owns.rows[0]) return res.status(404).json({ error: 'Hike not found' });

  const { rows } = await pool.query(
    'INSERT INTO hike_routes (hike_id, name, content) VALUES ($1, $2, $3) RETURNING id, name',
    [req.params.id, String(req.query.name || '路線').slice(0, 200), content]
  );
  res.status(201).json(rows[0]);
});

router.get('/:id/routes', requireAuth, async (req, res) => {
  const { rows } = await pool.query(
    `SELECT r.id, r.name FROM hike_routes r JOIN hikes h ON h.id = r.hike_id
     WHERE r.hike_id = $1 AND h.user_id = $2 ORDER BY r.id`,
    [req.params.id, req.userId]
  );
  res.json(rows);
});

router.delete('/:id/routes/:routeId', requireAuth, async (req, res) => {
  const { rowCount } = await pool.query(
    `DELETE FROM hike_routes r USING hikes h
     WHERE r.id = $1 AND r.hike_id = $2 AND h.id = r.hike_id AND h.user_id = $3`,
    [req.params.routeId, req.params.id, req.userId]
  );
  if (!rowCount) return res.status(404).json({ error: 'Route not found' });
  res.json({ ok: true });
});

// A named waypoint (航點). Idempotent on clientId, since the app retries until delivered.
router.post('/:id/waypoints', requireAuth, async (req, res) => {
  const { clientId, name, lat, lng, altitude, recordedAt } = req.body || {};
  if (!clientId || !name || typeof lat !== 'number' || typeof lng !== 'number' || !recordedAt) {
    return res.status(400).json({ error: 'clientId, name, lat, lng, recordedAt are required' });
  }
  const owns = await pool.query('SELECT id FROM hikes WHERE id = $1 AND user_id = $2', [req.params.id, req.userId]);
  if (!owns.rows[0]) return res.status(404).json({ error: 'Hike not found' });
  await pool.query(
    `INSERT INTO hike_waypoints (hike_id, client_id, name, lat, lng, altitude, recorded_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7) ON CONFLICT (hike_id, client_id) DO NOTHING`,
    [req.params.id, String(clientId), String(name).slice(0, 200), lat, lng, altitude ?? null, recordedAt]
  );
  res.status(201).json({ ok: true });
});

function isRouteDocument(content) {
  return typeof content === 'string' && (content.includes('<gpx') || content.includes('<kml'));
}

router.get('/', requireAuth, async (req, res) => {
  const { rows } = await pool.query(
    'SELECT * FROM hikes WHERE user_id = $1 ORDER BY started_at DESC',
    [req.userId]
  );
  res.json(rows);
});

module.exports = router;

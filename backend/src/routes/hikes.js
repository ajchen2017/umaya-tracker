const express = require('express');
const pool = require('../db/pool');
const { requireAuth } = require('../middleware/auth');

const router = express.Router();

router.post('/', requireAuth, async (req, res) => {
  const { name, nickname } = req.body;
  if (!name) return res.status(400).json({ error: 'name is required' });

  const { rows } = await pool.query(
    'INSERT INTO hikes (user_id, name, nickname) VALUES ($1, $2, $3) RETURNING *',
    [req.userId, name, nickname || null]
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

// Planned route the hiker intends to follow, uploaded as a raw GPX or KML file (XML body).
// Shown on the family web page alongside the actual live track for comparison.
router.put('/:id/route', requireAuth, express.text({ type: '*/*', limit: '5mb' }), async (req, res) => {
  const route = req.body;
  if (!route || typeof route !== 'string' || !(route.includes('<gpx') || route.includes('<kml'))) {
    return res.status(400).json({ error: 'Request body must be a GPX or KML (XML) document' });
  }

  const { rows } = await pool.query(
    'UPDATE hikes SET planned_route = $1 WHERE id = $2 AND user_id = $3 RETURNING id',
    [route, req.params.id, req.userId]
  );
  if (!rows[0]) return res.status(404).json({ error: 'Hike not found' });
  res.json({ ok: true });
});

// Removes the planned route without replacing it (e.g. the hiker changed plans).
router.delete('/:id/route', requireAuth, async (req, res) => {
  const { rows } = await pool.query(
    'UPDATE hikes SET planned_route = NULL WHERE id = $1 AND user_id = $2 RETURNING id',
    [req.params.id, req.userId]
  );
  if (!rows[0]) return res.status(404).json({ error: 'Hike not found' });
  res.json({ ok: true });
});

router.get('/', requireAuth, async (req, res) => {
  const { rows } = await pool.query(
    'SELECT * FROM hikes WHERE user_id = $1 ORDER BY started_at DESC',
    [req.userId]
  );
  res.json(rows);
});

module.exports = router;

const express = require('express');
const pool = require('../db/pool');
const { requireAuth } = require('../middleware/auth');

const router = express.Router();

router.post('/', requireAuth, async (req, res) => {
  const { name } = req.body;
  if (!name) return res.status(400).json({ error: 'name is required' });

  const { rows } = await pool.query(
    'INSERT INTO hikes (user_id, name) VALUES ($1, $2) RETURNING *',
    [req.userId, name]
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

router.get('/', requireAuth, async (req, res) => {
  const { rows } = await pool.query(
    'SELECT * FROM hikes WHERE user_id = $1 ORDER BY started_at DESC',
    [req.userId]
  );
  res.json(rows);
});

module.exports = router;

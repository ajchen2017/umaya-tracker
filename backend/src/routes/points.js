const express = require('express');
const pool = require('../db/pool');
const { requireAuth } = require('../middleware/auth');

const router = express.Router();

// Batch upload. Body: { points: [{ clientId, lat, lng, altitude, accuracy, markerType, batteryPct, recordedAt }, ...] }
router.post('/:id/points', requireAuth, async (req, res) => {
  const { points } = req.body;
  if (!Array.isArray(points) || points.length === 0) {
    return res.status(400).json({ error: 'points must be a non-empty array' });
  }

  const hike = await pool.query('SELECT id FROM hikes WHERE id = $1 AND user_id = $2', [
    req.params.id,
    req.userId,
  ]);
  if (!hike.rows[0]) return res.status(404).json({ error: 'Hike not found' });

  const inserted = [];
  for (const p of points) {
    const { rows } = await pool.query(
      `INSERT INTO track_points (hike_id, client_id, lat, lng, altitude, accuracy, marker_type, battery_pct, recorded_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
       ON CONFLICT (hike_id, client_id) DO NOTHING
       RETURNING *`,
      [
        req.params.id,
        p.clientId,
        p.lat,
        p.lng,
        p.altitude ?? null,
        p.accuracy ?? null,
        p.markerType || 'normal',
        p.batteryPct ?? null,
        p.recordedAt,
      ]
    );
    if (rows[0]) inserted.push(rows[0]);
  }

  res.status(201).json({ inserted: inserted.length, skipped: points.length - inserted.length });
});

module.exports = router;

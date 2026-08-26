const express = require('express');
const pool = require('../db/pool');

const router = express.Router();

// Public reference data (not tied to any hike) — pooled from multiple sources,
// see src/admin/updateSignalPoints.js for provenance.
router.get('/', async (req, res) => {
  const { rows } = await pool.query(
    'SELECT lat, lng, trail_name, location_desc, county, cht, fet, twm, other, source FROM signal_points'
  );
  res.json(rows);
});

module.exports = router;

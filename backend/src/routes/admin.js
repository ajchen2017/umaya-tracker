const express = require('express');
const { updateRudyMap } = require('../admin/updateRudyMap');
const { updateSignalPoints } = require('../admin/updateSignalPoints');
const { checkAdminPassword } = require('../middleware/auth');

const router = express.Router();

router.post('/update-rudymap', async (req, res) => {
  if (!checkAdminPassword(req, res)) return;
  try {
    const updated = await updateRudyMap();
    res.json({ ok: true, updated });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.post('/update-signal-points', async (req, res) => {
  if (!checkAdminPassword(req, res)) return;
  try {
    const results = await updateSignalPoints();
    res.json({ ok: true, results });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;

const express = require('express');
const { updateRudyMap } = require('../admin/updateRudyMap');
const { updateSignalPoints } = require('../admin/updateSignalPoints');

const router = express.Router();

function checkPassword(req, res) {
  const { password } = req.body;
  if (!process.env.ADMIN_PASSWORD || password !== process.env.ADMIN_PASSWORD) {
    res.status(401).json({ error: '密碼錯誤' });
    return false;
  }
  return true;
}

router.post('/update-rudymap', async (req, res) => {
  if (!checkPassword(req, res)) return;
  try {
    const updated = await updateRudyMap();
    res.json({ ok: true, updated });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

router.post('/update-signal-points', async (req, res) => {
  if (!checkPassword(req, res)) return;
  try {
    const results = await updateSignalPoints();
    res.json({ ok: true, results });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;

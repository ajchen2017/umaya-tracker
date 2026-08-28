require('dotenv').config();
const path = require('path');
const express = require('express');
const cors = require('cors');

const authRoutes = require('./routes/auth');
const hikeRoutes = require('./routes/hikes');
const pointRoutes = require('./routes/points');
const publicRoutes = require('./routes/public');
const adminRoutes = require('./routes/admin');
const signalPointsRoutes = require('./routes/signalPoints');
const mapdataRoutes = require('./routes/mapdata');

const app = express();
app.use(cors());
app.use(express.json());

app.use('/api/auth', authRoutes);
app.use('/api/hikes', hikeRoutes);
app.use('/api/hikes', pointRoutes);
app.use('/api/t', publicRoutes);
app.use('/api/admin', adminRoutes);
app.use('/api/signal-points', signalPointsRoutes);
app.use('/api/mapdata', mapdataRoutes);

// Family view (SPA): static assets + one HTML shell for any share token.
app.use('/t/assets', express.static(path.join(__dirname, '..', 'public', 'assets')));
// Debug-build APK for manual distribution to testers — not linked from anywhere, just a
// stable URL to hand out. The file itself isn't tracked in git; deployed by scp.
app.use('/downloads', express.static(path.join(__dirname, '..', 'public', 'downloads')));

// Raw Mapsforge offline map data (main .map + .poi, render theme, SRTM hillshade tiles) — the
// hiker app downloads these directly for native offline vector rendering. Same directory
// mapsforgesrv itself reads from (see admin/updateRudyMap.js's TILESERVER_DIR), so this is
// always in sync with whatever "更新魯地圖" last fetched — no separate copy to keep updated.
// Only maps/theme/dem are exposed, not the whole tileserver dir (config/ has server internals).
const TILESERVER_DIR = path.join(__dirname, '..', '..', 'tileserver');
// Node's mime lookup treats ".map" as a JS sourcemap (application/json) — wrong for Mapsforge's
// own binary .map format, though harmless for the Android client (it saves raw bytes either
// way); corrected here anyway so the header isn't actively misleading.
const staticOpts = {
  setHeaders: (res, filePath) => {
    if (filePath.endsWith('.map')) res.setHeader('Content-Type', 'application/octet-stream');
  },
};
app.use('/mapdata/maps', express.static(path.join(TILESERVER_DIR, 'maps'), staticOpts));
app.use('/mapdata/theme', express.static(path.join(TILESERVER_DIR, 'theme'), staticOpts));
app.use('/mapdata/dem', express.static(path.join(TILESERVER_DIR, 'dem'), staticOpts));
app.get('/t/:shareToken/settings', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'public', 'settings.html'));
});
app.get('/t/:shareToken', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'public', 'index.html'));
});

// Forgot-password link lands here (?token=... in the URL, read client-side).
app.get('/reset-password', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'public', 'reset-password.html'));
});

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`tracker-backend listening on :${port}`));

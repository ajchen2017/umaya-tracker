const express = require('express');
const fs = require('fs');
const path = require('path');

const router = express.Router();

// Same directory mapsforgesrv itself reads from (see admin/updateRudyMap.js) — this manifest
// always reflects whatever "更新魯地圖" last fetched, no separate list to keep in sync by hand.
const TILESERVER_DIR = path.join(__dirname, '..', '..', '..', 'tileserver');

// theme/ has a resource subfolder (moiosmhs_res/) the renderer needs alongside the XML —
// walked recursively so a future theme update doesn't need this route touched.
function listFilesRecursive(dir, baseDir) {
  const entries = fs.readdirSync(dir, { withFileTypes: true });
  return entries.flatMap((entry) => {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) return listFilesRecursive(fullPath, baseDir);
    const relPath = path.relative(baseDir, fullPath).split(path.sep).join('/');
    return [{ path: relPath, size: fs.statSync(fullPath).size }];
  });
}

// { maps: [...], theme: [...], dem: [...] } — each entry's "path" is relative to that
// section's own /mapdata/<section>/ static route, so the client can build the download URL
// directly and (for theme/) preserve the same relative layout the render theme XML expects.
router.get('/manifest', (req, res) => {
  try {
    const sections = ['maps', 'theme', 'dem'];
    const manifest = {};
    for (const section of sections) {
      const dir = path.join(TILESERVER_DIR, section);
      manifest[section] = fs.existsSync(dir) ? listFilesRecursive(dir, dir) : [];
    }
    res.json(manifest);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

module.exports = router;

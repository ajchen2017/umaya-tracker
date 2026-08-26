const https = require('https');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');
const AdmZip = require('adm-zip');

const TILESERVER_DIR = path.join(__dirname, '..', '..', '..', 'tileserver');

// Mirror per rudymap.tw's own recommendation (weekly-updated, non-commercial license).
const DOWNLOADS = [
  { url: 'https://moi.kcwu.csie.org/MOI_OSM_Taiwan_TOPO_Rudy.map.zip', destDir: 'maps' },
  { url: 'https://moi.kcwu.csie.org/hgtmix.zip', destDir: 'dem' },
  { url: 'https://moi.kcwu.csie.org/MOI_OSM_Taiwan_TOPO_Rudy_hs_style.zip', destDir: 'theme' },
];

function download(url, destPath) {
  return new Promise((resolve, reject) => {
    const file = fs.createWriteStream(destPath);
    https
      .get(url, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          file.close();
          fs.unlinkSync(destPath);
          return download(res.headers.location, destPath).then(resolve, reject);
        }
        if (res.statusCode !== 200) {
          file.close();
          return reject(new Error(`HTTP ${res.statusCode} for ${url}`));
        }
        res.pipe(file);
        file.on('finish', () => file.close(resolve));
      })
      .on('error', reject);
  });
}

async function updateRudyMap() {
  const updated = [];

  for (const { url, destDir } of DOWNLOADS) {
    const dir = path.join(TILESERVER_DIR, destDir);
    fs.mkdirSync(dir, { recursive: true });
    const zipPath = path.join(dir, path.basename(url));

    await download(url, zipPath);
    new AdmZip(zipPath).extractAllTo(dir, true);
    fs.unlinkSync(zipPath);
    updated.push(destDir);
  }

  if (process.env.MAPSFORGESRV_RESTART_CMD) {
    await new Promise((resolve, reject) => {
      exec(process.env.MAPSFORGESRV_RESTART_CMD, (err) => (err ? reject(err) : resolve()));
    });
    updated.push('mapsforgesrv restarted');
  }

  return updated;
}

module.exports = { updateRudyMap };

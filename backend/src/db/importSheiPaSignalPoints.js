// 雪霸國家公園山區手機可通訊點 — 遠傳電信專屬（使用者提供的 PDF，無穩定網址可比對版本，
// 故與其他來源不同：這是一次性匯入腳本，資料已固定於 signal_points_sheipa_seed.json，
// 不掛進 updateSignalPoints.js 的自動比對迴圈）。
require('dotenv').config();
const fs = require('fs');
const path = require('path');
const pool = require('./pool');

async function importSheiPa() {
  const points = JSON.parse(
    fs.readFileSync(path.join(__dirname, 'signal_points_sheipa_seed.json'), 'utf8')
  );

  await pool.query("DELETE FROM signal_points WHERE source = 'sheipa'");
  for (const p of points) {
    await pool.query(
      `INSERT INTO signal_points (source, trail_name, location_desc, lat, lng, cht, fet, twm, other, remark)
       VALUES ('sheipa', $1, $2, $3, $4, false, true, false, false, $5)`,
      [p.trailName, p.locationDesc, p.lat, p.lng, p.remark]
    );
  }

  console.log(`Imported ${points.length} 雪霸 signal points.`);
  await pool.end();
}

importSheiPa().catch((err) => {
  console.error(err);
  process.exit(1);
});

require('dotenv').config();
const fs = require('fs');
const path = require('path');
const pool = require('./pool');

async function importSignalPoints() {
  const points = JSON.parse(
    fs.readFileSync(path.join(__dirname, 'signal_points_seed.json'), 'utf8')
  );

  await pool.query('TRUNCATE signal_points RESTART IDENTITY');

  for (const p of points) {
    await pool.query(
      `INSERT INTO signal_points (seq, trail_name, branch, location_desc, county, lat, lng, cht, fet, twm, remark)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)`,
      [p.seq, p.trailName, p.branch, p.locationDesc, p.county, p.lat, p.lng, p.cht, p.fet, p.twm, p.remark]
    );
  }

  console.log(`Imported ${points.length} signal points.`);
  await pool.end();
}

importSignalPoints().catch((err) => {
  console.error(err);
  process.exit(1);
});

const jwt = require('jsonwebtoken');

function requireAuth(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'Missing token' });

  try {
    req.userId = jwt.verify(token, process.env.JWT_SECRET).userId;
    next();
  } catch {
    res.status(401).json({ error: 'Invalid or expired token' });
  }
}

// Shared with admin.js: gates admin-only actions (map/data updates, alert-config
// tuning) behind a single server-wide password, not a per-user login.
function checkAdminPassword(req, res) {
  const { password } = req.body;
  if (!process.env.ADMIN_PASSWORD || password !== process.env.ADMIN_PASSWORD) {
    res.status(401).json({ error: '密碼錯誤' });
    return false;
  }
  return true;
}

module.exports = { requireAuth, checkAdminPassword };

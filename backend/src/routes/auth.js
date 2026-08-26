const express = require('express');
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const pool = require('../db/pool');

const router = express.Router();

router.post('/register', async (req, res) => {
  const { email, password, displayName } = req.body;
  if (!email || !password || !displayName) {
    return res.status(400).json({ error: 'email, password, displayName are required' });
  }

  const passwordHash = await bcrypt.hash(password, 10);
  const shareToken = crypto.randomBytes(8).toString('hex');
  try {
    const { rows } = await pool.query(
      'INSERT INTO users (email, password_hash, display_name, share_token) VALUES ($1, $2, $3, $4) RETURNING id, email, display_name',
      [email, passwordHash, displayName, shareToken]
    );
    res.status(201).json(rows[0]);
  } catch (err) {
    if (err.code === '23505') return res.status(409).json({ error: 'Email already registered' });
    throw err;
  }
});

router.post('/login', async (req, res) => {
  const { email, password } = req.body;
  const { rows } = await pool.query('SELECT * FROM users WHERE email = $1', [email]);
  const user = rows[0];
  if (!user || !(await bcrypt.compare(password, user.password_hash))) {
    return res.status(401).json({ error: 'Invalid email or password' });
  }

  const token = jwt.sign({ userId: user.id }, process.env.JWT_SECRET, { expiresIn: '30d' });
  res.json({
    token,
    user: { id: user.id, email: user.email, displayName: user.display_name, shareToken: user.share_token },
  });
});

module.exports = router;

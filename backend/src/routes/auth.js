const express = require('express');
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const pool = require('../db/pool');
const { sendEmail } = require('../lib/email');

const router = express.Router();
const RESET_TOKEN_TTL_MS = 60 * 60 * 1000; // 1 hour

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

// Always responds the same way regardless of whether the email exists, so this can't
// be used to enumerate registered accounts. Only actually sends mail on a real match.
router.post('/forgot-password', async (req, res) => {
  const { email } = req.body;
  if (!email) return res.status(400).json({ error: 'email is required' });

  const { rows } = await pool.query('SELECT id FROM users WHERE email = $1', [email]);
  const user = rows[0];
  if (user) {
    const token = crypto.randomBytes(24).toString('hex');
    const expires = new Date(Date.now() + RESET_TOKEN_TTL_MS);
    await pool.query('UPDATE users SET reset_token = $1, reset_token_expires = $2 WHERE id = $3', [
      token, expires, user.id,
    ]);

    const baseUrl = process.env.PUBLIC_BASE_URL || 'https://tracker.umaya.tw';
    const resetLink = `${baseUrl}/reset-password?token=${token}`;
    await sendEmail(
      email,
      '登山健行定位追蹤 - 重設密碼',
      `
        <p>你好，</p>
        <p>收到一筆重設密碼的請求。請點擊以下連結設定新密碼（1 小時內有效）：</p>
        <p><a href="${resetLink}">${resetLink}</a></p>
        <p>如果這不是你本人的操作，請忽略這封信，你的密碼不會被變更。</p>
        <p style="color:#888;font-size:12px;margin-top:24px;">
          本信件為系統自動發送，請勿回覆。
        </p>
      `,
    );
  }
  res.json({ ok: true });
});

router.post('/reset-password', async (req, res) => {
  const { token, newPassword } = req.body;
  if (!token || !newPassword) return res.status(400).json({ error: 'token and newPassword are required' });

  const { rows } = await pool.query(
    'SELECT id FROM users WHERE reset_token = $1 AND reset_token_expires > now()',
    [token]
  );
  if (!rows[0]) return res.status(400).json({ error: '連結無效或已過期，請重新申請' });

  const passwordHash = await bcrypt.hash(newPassword, 10);
  await pool.query(
    'UPDATE users SET password_hash = $1, reset_token = NULL, reset_token_expires = NULL WHERE id = $2',
    [passwordHash, rows[0].id]
  );
  res.json({ ok: true });
});

module.exports = router;

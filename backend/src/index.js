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

const app = express();
app.use(cors());
app.use(express.json());

app.use('/api/auth', authRoutes);
app.use('/api/hikes', hikeRoutes);
app.use('/api/hikes', pointRoutes);
app.use('/api/t', publicRoutes);
app.use('/api/admin', adminRoutes);
app.use('/api/signal-points', signalPointsRoutes);

// Family view (SPA): static assets + one HTML shell for any share token.
app.use('/t/assets', express.static(path.join(__dirname, '..', 'public', 'assets')));
app.get('/t/:shareToken/settings', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'public', 'settings.html'));
});
app.get('/t/:shareToken', (req, res) => {
  res.sendFile(path.join(__dirname, '..', 'public', 'index.html'));
});

const port = process.env.PORT || 3000;
app.listen(port, () => console.log(`tracker-backend listening on :${port}`));

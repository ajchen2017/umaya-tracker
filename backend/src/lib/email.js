// Gmail SMTP, same approach as the twstock project's email_service.py — STARTTLS on
// port 587, authenticated as a dedicated sender account (not the recipient's own).
const nodemailer = require('nodemailer');

let transporter = null;

function getTransporter() {
  if (transporter) return transporter;
  if (!process.env.SMTP_USER || !process.env.SMTP_PASSWORD) return null;
  transporter = nodemailer.createTransport({
    host: process.env.SMTP_HOST || 'smtp.gmail.com',
    port: Number(process.env.SMTP_PORT) || 587,
    secure: false, // STARTTLS, not implicit TLS
    auth: { user: process.env.SMTP_USER, pass: process.env.SMTP_PASSWORD },
  });
  return transporter;
}

async function sendEmail(to, subject, html) {
  const t = getTransporter();
  if (!t) {
    console.error('[Email] SMTP_USER/SMTP_PASSWORD not configured, cannot send');
    return false;
  }
  try {
    await t.sendMail({
      from: `"${process.env.SMTP_FROM_NAME || 'tracker admin'}" <${process.env.SMTP_USER}>`,
      to,
      subject,
      html,
    });
    return true;
  } catch (err) {
    console.error('[Email] send failed:', err.message);
    return false;
  }
}

module.exports = { sendEmail };

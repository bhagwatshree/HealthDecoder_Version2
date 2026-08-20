import express from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import db from './db.js';
import { hashPassword, verifyPassword, signToken, requireAuth, encrypt, decrypt, publicUser, verifyPhoneIdToken, isPhoneAuthConfigured, verifyGoogleSignInIdToken, isGoogleAuthConfigured, signDeviceToken, requireDeviceOrUser } from './auth.js';
import crypto from 'crypto';

import { resolveKeysForUser, peekAssignmentForUser, resolveKeysForDevice, getOrCreateDevice } from './keyPool.js';
import { trackFirebaseVerify, trackGemini, runWithUsageContext } from './usageTracker.js';
import { GoogleGenAI } from '@google/genai';
import jwt from 'jsonwebtoken';
import { searchCommercial, startUhiSearch, processUhiWebhook } from './discovery.js';
import { generateSpeech, translateText } from './ocr.js';

import path from 'path';
import { fileURLToPath } from 'url';

// Named distinctly from __filename/__dirname: esbuild bundles this whole file (and its
// dependencies, including @google/genai) into ONE scope for Lambda, and @google/genai's own
// CJS-interop shim declares a top-level `const __filename` — colliding with the standard
// name here throws "Identifier '__filename' has already been declared" at cold start.
const __serverFilename = fileURLToPath(import.meta.url);
const __serverDirname = path.dirname(__serverFilename);

dotenv.config();

// Must read the same real secret as auth.js's own JWT_SECRET (used here only to verify the
// app's own JWT embedded in the Google-account-link OAuth `state` param) — no insecure fallback.
if (!process.env.JWT_SECRET) {
  throw new Error('JWT_SECRET environment variable is required and must not be empty.');
}
const JWT_SECRET = process.env.JWT_SECRET;

const app = express();
const PORT = process.env.PORT || 3000;
const isLambda = !!process.env.AWS_LAMBDA_FUNCTION_NAME;

// Enable CORS for mobile app connectivity
app.use(cors());
// Raised from Express's 100kb default: /api/ai/generate carries base64 scan-page images.
// A default 6-page chunk (1600px JPEG @ q85) is ~1-3MB even after base64 inflation, so 8mb
// leaves comfortable headroom while staying well clear of the platform's ~10MB request cap.
app.use(express.json({ limit: '8mb' }));
// A body over the limit above throws here as a generic Express error — turn it into the same
// JSON error shape every other route returns, with a message that tells the app what happened.
app.use((err, req, res, next) => {
  if (err?.type === 'entity.too.large') {
    return res.status(413).json({ error: 'Request too large — try scanning fewer pages at once.' });
  }
  next(err);
});

// Serve Interactive Clickable Mockup & Figma Canvas Simulator
app.use(express.static(path.join(__serverDirname, '../mockup')));
app.use('/mockup', express.static(path.join(__serverDirname, '../mockup')));

// Test Endpoint
app.get('/api/health', (req, res) => {
  res.json({ status: 'healthy', timestamp: new Date() });
});

// ─── UI-chrome translations ───────────────────────────────────────────────────
// Static button/label/title text, as opposed to AI-generated content (chat answers,
// report summaries) which is translated live via /api/chat's Sarvam call. The app fetches
// this once on first launch and again whenever the user picks a language from the picker,
// then caches it on-device — so editing a row here reaches every install without a build.
// No ?language query: returns every language, grouped. With it: just that language's map.
app.get('/api/translations', async (req, res) => {
  try {
    const { language } = req.query;
    const result = language
      ? await db.query(
          'SELECT text_key, translated_text FROM ui_translations WHERE language = $1',
          [language]
        )
      : await db.query('SELECT language, text_key, translated_text FROM ui_translations');

    if (language) {
      const map = {};
      for (const row of result.rows) map[row.text_key] = row.translated_text;
      return res.json(map);
    }

    const byLanguage = {};
    for (const row of result.rows) {
      if (!byLanguage[row.language]) byLanguage[row.language] = {};
      byLanguage[row.language][row.text_key] = row.translated_text;
    }
    res.json(byLanguage);
  } catch (error) {
    console.error('Error fetching translations:', error);
    res.status(500).json({ error: 'Failed to fetch translations.' });
  }
});

// ─── Personalized health tips ──────────────────────────────────────────────────
// Natural lifestyle tips (diet, hydration, sleep, movement — never medication-specific) keyed
// by canonical test name + High/Low status. Same pattern as /api/translations above: the DB is
// the source of truth, so adding a tip for a newly-covered test reaches every install without
// an app release. The app fetches this once and caches on-device, falling back to its bundled
// seed for any test not yet covered here.
app.get('/api/health-tips', async (req, res) => {
  try {
    const result = await db.query(
      'SELECT canonical_param, status, headline, detail FROM health_tips'
    );
    res.json(result.rows);
  } catch (error) {
    console.error('Error fetching health tips:', error);
    res.status(500).json({ error: 'Failed to fetch health tips.' });
  }
});

// ─── Auth & per-user free tier ────────────────────────────────────────────────
// Provider keys NEVER leave this server. The app calls Gemini/Sarvam only through the
// /api/ai/* proxy routes below, which resolve a key server-side per request (see keyPool.js).
// These auth routes therefore report usage/quota ONLY — they deliberately strip geminiKey and
// sarvamKey from every response, because an app that ships or receives a raw provider key is
// both a Play Store review risk and a key we could never rotate out of installed builds.

const VALID_GENDERS = ['male', 'female', 'other', 'prefer_not_to_say'];

app.post('/api/auth/signup', async (req, res) => {
  try {
    const { firstName, lastName, dateOfBirth, gender, email, password, phoneIdToken } = req.body || {};

    if (!firstName || !String(firstName).trim() || !lastName || !String(lastName).trim()) {
      return res.status(400).json({ error: 'First name and last name are required.' });
    }
    if (!dateOfBirth || Number.isNaN(Date.parse(dateOfBirth))) {
      return res.status(400).json({ error: 'A valid date of birth is required.' });
    }
    if (!VALID_GENDERS.includes(gender)) {
      return res.status(400).json({ error: 'A valid gender is required.' });
    }
    if (!email || !String(email).trim() || !password || String(password).length < 6) {
      return res.status(400).json({ error: 'A valid email and a password of at least 6 characters are required.' });
    }

    const normalizedEmail = String(email).trim().toLowerCase();

    // Phone verification is optional: signup must work with just email+password (every OTP is
    // a billed SMS, so we don't want to force one on every new user). If a phoneIdToken IS
    // supplied — e.g. a future "verify your phone too" upsell — we still verify it and use the
    // real E.164 number. Otherwise we synthesise a placeholder MSISDN, the same trick
    // /api/auth/google-signin already uses for its own passwordless-of-phone signups, so the
    // `msisdn` column (UNIQUE NOT NULL, see db_init.sql) is always satisfiable. The `email_`
    // prefix keeps this scheme from ever colliding with the `google_` one.
    let msisdn;
    if (phoneIdToken) {
      if (!isPhoneAuthConfigured()) {
        return res.status(501).json({ error: 'Phone verification is not configured on this server yet.' });
      }
      try {
        msisdn = await verifyPhoneIdToken(String(phoneIdToken));
        trackFirebaseVerify(null, true);
      } catch (error) {
        trackFirebaseVerify(null, false);
        console.error('Phone verification failed:', error);
        return res.status(401).json({ error: 'Phone verification failed. Please request a new OTP and try again.' });
      }
    } else {
      const emailHash = crypto.createHash('md5').update(normalizedEmail).digest('hex').substring(0, 10);
      msisdn = `email_${emailHash}`;
    }

    // Email and MSISDN each get their own uniqueness check so we can return which one collided
    // — both are UNIQUE at the DB level too (see db_init.sql) as the source of truth.
    const existingEmail = await db.query('SELECT id FROM users WHERE email = $1', [normalizedEmail]);
    if (existingEmail.rows.length > 0) {
      return res.status(409).json({ error: 'An account with this email already exists.' });
    }
    const existingPhone = await db.query('SELECT id FROM users WHERE msisdn = $1', [msisdn]);
    if (existingPhone.rows.length > 0) {
      return res.status(409).json({ error: 'An account with this phone number already exists.' });
    }

    const passwordHash = await hashPassword(String(password));
    const result = await db.query(
      `INSERT INTO users (first_name, last_name, date_of_birth, gender, email, msisdn, password_hash)
       VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING *`,
      [String(firstName).trim(), String(lastName).trim(), dateOfBirth, gender, normalizedEmail, msisdn, passwordHash]
    );
    const user = result.rows[0];
    res.status(201).json({ token: signToken(user), user: publicUser(user) });
  } catch (error) {
    // Race condition: two signups for the same email/phone landing concurrently past the
    // pre-checks above — the DB's UNIQUE constraints are the real guard here.
    if (error.code === '23505') {
      return res.status(409).json({ error: 'An account with this email or phone number already exists.' });
    }
    console.error('Signup failed:', error);
    res.status(500).json({ error: 'Failed to create account' });
  }
});

app.post('/api/auth/login', async (req, res) => {
  try {
    const { email, password } = req.body || {};
    if (!email || !password) {
      return res.status(400).json({ error: 'Email and password are required.' });
    }
    const result = await db.query('SELECT * FROM users WHERE email = $1', [String(email).trim().toLowerCase()]);
    const user = result.rows[0];
    if (!user || !(await verifyPassword(String(password), user.password_hash))) {
      return res.status(401).json({ error: 'Invalid email or password.' });
    }
    res.json({ token: signToken(user), user: publicUser(user) });
  } catch (error) {
    console.error('Login failed:', error);
    res.status(500).json({ error: 'Failed to log in' });
  }
});

// Phone+OTP login: the phone number was already OTP-verified client-side by the Firebase SDK;
// this just confirms that verification (via the ID token) and looks up the linked account.
app.post('/api/auth/login-phone', async (req, res) => {
  try {
    const { phoneIdToken } = req.body || {};
    if (!phoneIdToken) {
      return res.status(400).json({ error: 'Phone verification token is required.' });
    }
    if (!isPhoneAuthConfigured()) {
      return res.status(501).json({ error: 'Phone verification is not configured on this server yet.' });
    }

    let msisdn;
    try {
      msisdn = await verifyPhoneIdToken(String(phoneIdToken));
      trackFirebaseVerify(null, true);
    } catch (error) {
      trackFirebaseVerify(null, false);
      console.error('Phone verification failed:', error);
      return res.status(401).json({ error: 'Phone verification failed. Please request a new OTP and try again.' });
    }

    const result = await db.query('SELECT * FROM users WHERE msisdn = $1', [msisdn]);
    const user = result.rows[0];
    if (!user) {
      return res.status(404).json({ error: 'No account found for this phone number. Sign up first.' });
    }
    res.json({ token: signToken(user), user: publicUser(user) });
  } catch (error) {
    console.error('Phone login failed:', error);
    res.status(500).json({ error: 'Failed to log in' });
  }
});

// Native "Sign in with Google": the device already picked an account and produced a Firebase
// ID token via Credential Manager + FirebaseAuth.signInWithCredential — no browser, no OAuth
// client secret. This only proves identity (email); it never requests Gmail scope/refresh
// tokens — that's a separate, deliberately browser-based flow (see /api/auth/google below),
// needed only when a user opts into email scanning.
app.post('/api/auth/google-signin', async (req, res) => {
  try {
    const { idToken } = req.body || {};
    if (!idToken) {
      return res.status(400).json({ error: 'Google ID token is required.' });
    }
    if (!isGoogleAuthConfigured()) {
      return res.status(501).json({ error: 'Google sign-in is not configured on this server yet.' });
    }

    let email, firstName, lastName;
    try {
      ({ email, firstName, lastName } = await verifyGoogleSignInIdToken(String(idToken)));
      trackFirebaseVerify(null, true);
    } catch (error) {
      trackFirebaseVerify(null, false);
      // The client only ever sees a generic 401 (never leak verifyIdToken's raw error to
      // untrusted callers), so this is the only place the actual cause — wrong Firebase
      // project, malformed service account key, expired/tampered token — is visible at all.
      console.error('Google sign-in verification failed:', error);
      return res.status(401).json({ error: 'Google sign-in verification failed. Please try again.' });
    }

    const existing = await db.query('SELECT * FROM users WHERE email = $1 OR google_email = $1', [email]);
    let user = existing.rows[0];

    if (!user) {
      const msisdnHash = crypto.createHash('md5').update(email).digest('hex').substring(0, 10);
      const dummyMsisdn = `google_${msisdnHash}`;
      const dummyPasswordHash = await hashPassword(crypto.randomBytes(16).toString('hex'));

      const inserted = await db.query(
        `INSERT INTO users (first_name, last_name, date_of_birth, gender, email, msisdn, password_hash, google_email)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING *`,
        [firstName, lastName, '2000-01-01', 'prefer_not_to_say', email, dummyMsisdn, dummyPasswordHash, email]
      );
      user = inserted.rows[0];
    } else if (!user.google_email) {
      await db.query('UPDATE users SET google_email = $1 WHERE id = $2', [email, user.id]);
      user.google_email = email;
    }

    res.json({ token: signToken(user), user: publicUser(user) });
  } catch (error) {
    console.error('Google sign-in failed:', error);
    res.status(500).json({ error: 'Failed to sign in with Google.' });
  }
});

app.get('/api/auth/me', requireAuth, (req, res) => {
  res.json(publicUser(req.user));
});

app.put('/api/auth/me', requireAuth, async (req, res) => {
  try {
    const { firstName, lastName, dateOfBirth, gender } = req.body || {};
    if (!firstName || !String(firstName).trim() || !lastName || !String(lastName).trim()) {
      return res.status(400).json({ error: 'First and last name are required.' });
    }
    if (dateOfBirth && !/^\d{4}-\d{2}-\d{2}$/.test(String(dateOfBirth))) {
      return res.status(400).json({ error: 'Date of birth must be in YYYY-MM-DD format.' });
    }
    if (gender && !VALID_GENDERS.includes(String(gender))) {
      return res.status(400).json({ error: 'Invalid gender value.' });
    }

    const result = await db.query(
      `UPDATE users SET first_name = $1, last_name = $2, date_of_birth = COALESCE($3, date_of_birth),
       gender = COALESCE($4, gender) WHERE id = $5 RETURNING *`,
      [String(firstName).trim(), String(lastName).trim(), dateOfBirth || null, gender || null, req.user.id]
    );
    res.json(publicUser(result.rows[0]));
  } catch (error) {
    console.error('Update profile failed:', error);
    res.status(500).json({ error: 'Failed to update profile.' });
  }
});

app.post('/api/auth/reset-password-otp', async (req, res) => {
  try {
    const { phoneIdToken, newPassword } = req.body || {};
    if (!phoneIdToken || !newPassword || String(newPassword).length < 6) {
      return res.status(400).json({ error: 'Phone verification token and a password of at least 6 characters are required.' });
    }
    if (!isPhoneAuthConfigured()) {
      return res.status(501).json({ error: 'Phone verification is not configured on this server.' });
    }

    let msisdn;
    try {
      msisdn = await verifyPhoneIdToken(String(phoneIdToken));
      trackFirebaseVerify(null, true);
    } catch (error) {
      trackFirebaseVerify(null, false);
      console.error('Phone verification failed:', error);
      return res.status(401).json({ error: 'Phone verification failed. Please try again.' });
    }

    const passwordHash = await hashPassword(String(newPassword));
    // Also bumps token_version: a password reset via "forgot password" is exactly the
    // scenario where any session on another device should stop being trusted.
    const result = await db.query(
      'UPDATE users SET password_hash = $1, token_version = token_version + 1 WHERE msisdn = $2 RETURNING id',
      [passwordHash, msisdn]
    );

    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'No account found linked to this phone number.' });
    }

    res.json({ success: true, message: 'Password reset successfully.' });
  } catch (error) {
    console.error('Reset password failed:', error);
    res.status(500).json({ error: 'Failed to reset password.' });
  }
});

app.post('/api/auth/change-password', requireAuth, async (req, res) => {
  try {
    const { currentPassword, newPassword } = req.body || {};
    if (!currentPassword || !newPassword || String(newPassword).length < 6) {
      return res.status(400).json({ error: 'Current password and a new password of at least 6 characters are required.' });
    }

    const result = await db.query('SELECT password_hash FROM users WHERE id = $1', [req.user.id]);
    const user = result.rows[0];
    if (!user || !(await verifyPassword(String(currentPassword), user.password_hash))) {
      return res.status(401).json({ error: 'Incorrect current password.' });
    }

    const passwordHash = await hashPassword(String(newPassword));
    // Bumping token_version invalidates every previously-issued token for this account
    // (any other device/session) — sign and return a fresh one so the device making this
    // change stays logged in seamlessly instead of being logged out by its own request.
    const updateResult = await db.query(
      'UPDATE users SET password_hash = $1, token_version = token_version + 1 WHERE id = $2 RETURNING *',
      [passwordHash, req.user.id]
    );
    const newToken = signToken(updateResult.rows[0]);

    res.json({ success: true, message: 'Password updated successfully.', token: newToken });
  } catch (error) {
    console.error('Change password failed:', error);
    res.status(500).json({ error: 'Failed to change password.' });
  }
});

// Helper to get Google redirect URI dynamically
const getGoogleRedirectUri = (req) => {
  const host = req.get('host');
  const protocol = req.protocol === 'https' || host.includes('execute-api') || host.includes('ngrok') ? 'https' : 'http';
  return `${protocol}://${host}/api/auth/google/callback`;
};

// 1. Redirect user to Google for authentication
app.get('/api/auth/google', (req, res) => {
  const clientId = process.env.GOOGLE_CLIENT_ID;
  const clientSecret = process.env.GOOGLE_CLIENT_SECRET;
  if (!clientId || !clientSecret) {
    return res.status(400).send(`
      <html>
        <body style="font-family: sans-serif; padding: 40px; text-align: center; background-color: #f8f9fa;">
          <div style="max-width: 500px; margin: auto; padding: 30px; border-radius: 12px; background: white; box-shadow: 0 4px 6px rgba(0,0,0,0.1);">
            <h2 style="color: #d32f2f; margin-top: 0;">Google OAuth Not Configured</h2>
            <p style="color: #495057; font-size: 15px; line-height: 1.6;">
              Please configure the Google OAuth client credentials on the server. Add the following variables to your <code>.env</code> file:
            </p>
            <pre style="background: #e9ecef; padding: 12px; border-radius: 6px; text-align: left; font-size: 14px;">GOOGLE_CLIENT_ID=your_client_id<br>GOOGLE_CLIENT_SECRET=your_client_secret</pre>
            <p style="color: #6c757d; font-size: 13px;">Once added, restart your server and try again.</p>
            <button onclick="window.close()" style="margin-top: 15px; padding: 10px 24px; font-size: 15px; font-weight: bold; color: white; background: #007bff; border: none; border-radius: 6px; cursor: pointer;">Close Window</button>
          </div>
        </body>
      </html>
    `);
  }

  const state = req.query.state || 'login';
  const redirectUri = getGoogleRedirectUri(req);
  // gmail.readonly (not the full https://mail.google.com/ IMAP scope) — narrower scope means
  // Google's standard verification is enough; IMAP access needs the "restricted" mail.google.com
  // scope, which additionally requires a paid third-party security assessment. The tradeoff is
  // that email scanning must go through the Gmail REST API instead of raw IMAP — see
  // GmailApiClient.kt / EmailScanWorker.kt on the Android side.
  const scope = 'openid email profile https://www.googleapis.com/auth/gmail.readonly';
  
  const googleAuthUrl = `https://accounts.google.com/o/oauth2/v2/auth?` + 
    `client_id=${clientId}&` +
    `redirect_uri=${encodeURIComponent(redirectUri)}&` +
    `response_type=code&` +
    `scope=${encodeURIComponent(scope)}&` +
    `access_type=offline&` +
    `prompt=consent&` +
    `state=${encodeURIComponent(state)}`;

  res.redirect(googleAuthUrl);
});

// 2. Google Redirect Callback Handler
app.get('/api/auth/google/callback', async (req, res) => {
  try {
    const { code, state } = req.query;
    if (!code) {
      return res.status(400).send('Authorization code not provided from Google.');
    }

    const clientId = process.env.GOOGLE_CLIENT_ID;
    const clientSecret = process.env.GOOGLE_CLIENT_SECRET;
    const redirectUri = getGoogleRedirectUri(req);

    // Exchange authorization code for tokens
    const tokenResponse = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        code,
        client_id: clientId,
        client_secret: clientSecret,
        redirect_uri: redirectUri,
        grant_type: 'authorization_code'
      })
    });

    if (!tokenResponse.ok) {
      const errorText = await tokenResponse.text();
      console.error('Failed to exchange Google OAuth code:', errorText);
      return res.status(tokenResponse.status).send(`Failed to authenticate with Google: ${errorText}`);
    }

    const tokens = await tokenResponse.json();
    const idToken = tokens.id_token;
    if (!idToken) {
      return res.status(400).send('No ID token received from Google.');
    }

    // Decode ID token payload (no network request needed, safely decode base64)
    const payloadParts = idToken.split('.');
    if (payloadParts.length < 2) {
      return res.status(400).send('Invalid ID token format.');
    }
    const idTokenPayload = JSON.parse(Buffer.from(payloadParts[1], 'base64').toString('utf8'));
    
    const email = idTokenPayload.email?.toLowerCase();
    if (!email) {
      return res.status(400).send('Google did not return an email address.');
    }

    const givenName = idTokenPayload.given_name || 'Google';
    const familyName = idTokenPayload.family_name || 'User';
    const encryptedRefreshToken = tokens.refresh_token ? encrypt(tokens.refresh_token) : null;

    let targetUser = null;

    // Check if we are linking an account or logging in
    if (state && state.startsWith('link|')) {
      const stateParts = state.split('|');
      const jwtToken = stateParts[1];
      // Single-use correlator the app generated right before opening this flow — echoed back
      // on the redirect so the app can refuse to trust a deep link it didn't just request
      // (see Navigation.kt's oauth2/oauth2-link handling).
      const nonce = stateParts[2] || '';
      let loggedInUser = null;
      try {
        const payload = jwt.verify(jwtToken, JWT_SECRET);
        const result = await db.query('SELECT * FROM users WHERE id = $1', [payload.sub]);
        loggedInUser = result.rows[0];
      } catch (e) {
        return res.status(401).send('Session expired. Please log in again from the app settings page before linking Google.');
      }

      if (!loggedInUser) {
        return res.status(401).send('User not found.');
      }

      // Link the Google account details
      const updateQuery = encryptedRefreshToken 
        ? 'UPDATE users SET google_email = $1, google_refresh_token = $2 WHERE id = $3 RETURNING *'
        : 'UPDATE users SET google_email = $1 WHERE id = $2 RETURNING *';
      
      const updateParams = encryptedRefreshToken
        ? [email, encryptedRefreshToken, loggedInUser.id]
        : [email, loggedInUser.id];
      
      const updateResult = await db.query(updateQuery, updateParams);
      targetUser = updateResult.rows[0];

      // Redirect back to app linking callback
      const appLinkUrl = `medicalscanner://oauth2-link?google_email=${encodeURIComponent(email)}&google_access_token=${encodeURIComponent(tokens.access_token)}&nonce=${encodeURIComponent(nonce)}`;
      return res.redirect(appLinkUrl);
    }

    // Anything else (including the default 'login' when no state was supplied) carries no
    // JWT to link against, so the whole state value is the nonce — the app rejects this
    // deep link too unless it's holding a matching one it generated itself.
    const loginNonce = state && state !== 'login' ? state : '';

    // Default: Login flow
    // Find if user already exists (by email, or by google_email)
    const existingUserRes = await db.query(
      'SELECT * FROM users WHERE email = $1 OR google_email = $1',
      [email]
    );

    if (existingUserRes.rows.length > 0) {
      targetUser = existingUserRes.rows[0];
      // Update Google refresh token if we got a new one during this authentication
      if (encryptedRefreshToken) {
        await db.query(
          'UPDATE users SET google_email = $1, google_refresh_token = $2 WHERE id = $3',
          [email, encryptedRefreshToken, targetUser.id]
        );
      }
    } else {
      // Create new user (automatically generate details)
      const dummyDob = '2000-01-01';
      const dummyGender = 'prefer_not_to_say';
      // derive msisdn: unique string, max 20 chars
      const msisdnHash = crypto.createHash('md5').update(email).digest('hex').substring(0, 10);
      const dummyMsisdn = `google_${msisdnHash}`;
      const dummyPassword = crypto.randomBytes(16).toString('hex');
      const dummyPasswordHash = await hashPassword(dummyPassword);

      const insertRes = await db.query(
        `INSERT INTO users (first_name, last_name, date_of_birth, gender, email, msisdn, password_hash, google_email, google_refresh_token)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING *`,
        [givenName, familyName, dummyDob, dummyGender, email, dummyMsisdn, dummyPasswordHash, email, encryptedRefreshToken]
      );
      targetUser = insertRes.rows[0];
    }

    const appSessionToken = signToken(targetUser);
    
    // Redirect back to the android app
    const appUrl = `medicalscanner://oauth2?` +
      `token=${encodeURIComponent(appSessionToken)}&` +
      `email=${encodeURIComponent(targetUser.email)}&` +
      `google_email=${encodeURIComponent(email)}&` +
      `google_access_token=${encodeURIComponent(tokens.access_token)}&` +
      `nonce=${encodeURIComponent(loginNonce)}`;

    res.redirect(appUrl);
  } catch (error) {
    console.error('Google OAuth callback failed:', error);
    res.status(500).send(`Authentication failed: ${error.message}`);
  }
});

// 3. Authenticated endpoint to fetch a refreshed access token for the linked Gmail account
app.get('/api/auth/google/token', requireAuth, async (req, res) => {
  try {
    const user = req.user;
    if (!user.google_refresh_token) {
      return res.status(400).json({ error: 'No Google account linked to this user.' });
    }

    const decryptedRefreshToken = decrypt(user.google_refresh_token);
    if (!decryptedRefreshToken) {
      return res.status(500).json({ error: 'Failed to decrypt refresh token.' });
    }

    const clientId = process.env.GOOGLE_CLIENT_ID;
    const clientSecret = process.env.GOOGLE_CLIENT_SECRET;
    if (!clientId || !clientSecret) {
      return res.status(500).json({ error: 'Google OAuth client credentials are not configured on the server.' });
    }

    // Request fresh access token from Google
    const response = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: clientId,
        client_secret: clientSecret,
        refresh_token: decryptedRefreshToken,
        grant_type: 'refresh_token'
      })
    });

    if (!response.ok) {
      const errorText = await response.text();
      console.error('Failed to refresh Google access token:', errorText);
      return res.status(response.status).json({ error: `Failed to refresh Google access token: ${errorText}` });
    }

    const data = await response.json();
    res.json({ access_token: data.access_token });
  } catch (error) {
    console.error('Failed to refresh Google access token:', error);
    res.status(500).json({ error: 'Internal server error while refreshing token.' });
  }
});



// Issues the Gemini/Sarvam key the phone should use right now, and accounts for free-tier
// usage. Call once per app session (or once/day) rather than before every AI call.
app.get('/api/auth/keys', requireAuth, async (req, res) => {
  try {
    const resolved = await resolveKeysForUser(req.user);
    // Never forward geminiKey/sarvamKey to the client — the app no longer calls Gemini/Sarvam
    // directly, everything goes through the server-side /api/ai/* proxy routes, so the phone
    // has no legitimate use for the raw key and shipping it would defeat the whole point of
    // the proxy (Play Store review flags embedded/leaked provider keys).
    res.json({
      plan: resolved.plan,
      billedTo: resolved.billedTo,
      usageToday: resolved.usageToday,
      limit: resolved.limit,
      quotaExceeded: resolved.quotaExceeded,
      hasOwnGeminiKey: !!req.user.own_gemini_key,
      hasOwnSarvamKey: !!req.user.own_sarvam_key,
    });
  } catch (error) {
    console.error('Error resolving keys for user:', error);
    res.status(500).json({ error: 'Failed to resolve API keys' });
  }
});

// Read-only usage/quota display (e.g. the Account screen) — unlike /api/auth/keys, this
// never consumes a free-tier issuance, so it's safe to call every time the screen opens.
app.get('/api/auth/usage', requireAuth, async (req, res) => {
  try {
    const usage = await peekAssignmentForUser(req.user);
    // Same rule as /api/auth/keys: only non-secret fields leave the server.
    res.json({
      plan: usage.plan,
      billedTo: usage.billedTo,
      usageToday: usage.usageToday,
      limit: usage.limit,
      quotaExceeded: usage.quotaExceeded,
      hasOwnGeminiKey: !!req.user.own_gemini_key,
      hasOwnSarvamKey: !!req.user.own_sarvam_key,
    });
  } catch (error) {
    console.error('Error reading usage for user:', error);
    res.status(500).json({ error: 'Failed to read usage' });
  }
});

app.post('/api/user/gemini-key', requireAuth, async (req, res) => {
  try {
    const { api_key } = req.body || {};
    const value = api_key && String(api_key).trim() ? encrypt(String(api_key).trim()) : null;
    await db.query('UPDATE users SET own_gemini_key = $1 WHERE id = $2', [value, req.user.id]);
    res.json({ message: value ? 'Gemini API key saved.' : 'Gemini API key removed.', hasOwnGeminiKey: !!value });
  } catch (error) {
    console.error('Error saving Gemini key:', error);
    res.status(500).json({ error: 'Failed to save Gemini API key' });
  }
});

app.post('/api/user/sarvam-key', requireAuth, async (req, res) => {
  try {
    const { api_key } = req.body || {};
    const value = api_key && String(api_key).trim() ? encrypt(String(api_key).trim()) : null;
    await db.query('UPDATE users SET own_sarvam_key = $1 WHERE id = $2', [value, req.user.id]);
    res.json({ message: value ? 'Sarvam API key saved.' : 'Sarvam API key removed.', hasOwnSarvamKey: !!value });
  } catch (error) {
    console.error('Error saving Sarvam key:', error);
    res.status(500).json({ error: 'Failed to save Sarvam API key' });
  }
});

// DESTRUCTIVE AND IRREVERSIBLE: permanently deletes the caller's account and everything tied
// to it. Required for Play Store compliance (a working in-app account-deletion path).
//
// Tables that reference users(id) (see db_init.sql): api_usage_events.user_id and
// uhi_search_sessions.user_id are both `ON DELETE SET NULL`, so the DB itself detaches those
// rows (kept only as anonymized usage/analytics history, no longer identifying this user) the
// moment the users row is gone — no explicit dependent-row cleanup is needed for them. No other
// table has a foreign key into users. Still wrapped in a transaction so a mid-flight failure
// can never leave the account half-deleted.
app.delete('/api/user/account', requireAuth, async (req, res) => {
  const client = await db.pool.connect();
  try {
    await client.query('BEGIN');
    const result = await client.query('DELETE FROM users WHERE id = $1', [req.user.id]);
    await client.query('COMMIT');
    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'Account not found.' });
    }
    res.json({ message: 'Account deleted.' });
  } catch (error) {
    await client.query('ROLLBACK');
    console.error('Account deletion failed:', error);
    res.status(500).json({ error: 'Failed to delete account.' });
  } finally {
    client.release();
  }
});

// ─── Anonymous device identity + AI proxy ─────────────────────────────────────
// No OTP/login involved — the app calls this once on first launch (or whenever it has no
// token yet) with a UUID it generated itself, and gets back a long-lived device token. This
// lets /api/ai/generate meter/pool a Gemini key per install without requiring an account,
// since phone OTP sign-in is optional/off by default.
app.post('/api/device/register', async (req, res) => {
  try {
    const { deviceId } = req.body || {};
    if (typeof deviceId !== 'string' || deviceId.length < 8 || deviceId.length > 128) {
      return res.status(400).json({ error: 'A valid deviceId is required.' });
    }
    await getOrCreateDevice(deviceId);
    res.json({ token: signDeviceToken(deviceId) });
  } catch (error) {
    console.error('Error registering device:', error);
    res.status(500).json({ error: 'Failed to register device.' });
  }
});

// Generic Gemini text/vision proxy — the phone sends a prompt (+ optional page images) and
// gets back the raw text response, exactly like calling Gemini directly used to, EXCEPT the
// Gemini API key never reaches the device: it's resolved server-side (pooled/BYOK) per call.
// Accepts either a real user session or an anonymous device token (see requireDeviceOrUser).
const MAX_AI_PROXY_IMAGES = 30;

// How long a finished answer stays cached and reusable for a retried identical request (same
// caller, operation, prompt and images) instead of paying Gemini again.
const AI_RESPONSE_CACHE_TTL_MINUTES = 10;

// A 'pending' (in-progress) row older than this is treated as an abandoned leader — its Lambda
// invocation died or was killed without ever finishing — rather than one still genuinely
// working, and becomes safe to reclaim. Kept just under the Lambda's own hard 120s timeout
// (template.yaml): nothing can still legitimately be running past that.
const AI_LEADER_STALE_SECONDS = 110;

function aiRequestHash(callerId, operation, prompt, imgs) {
  const h = crypto.createHash('sha256');
  h.update(String(callerId));
  h.update('|');
  h.update(operation);
  h.update('|');
  h.update(prompt);
  for (const img of imgs) h.update('|').update(img.data);
  return h.digest('hex');
}

/**
 * Single-flight de-dup for identical AI requests. The Function URL has no request timeout, but
 * its API Gateway fallback hard-caps at 30s (not configurable) while a slow Gemini call can take
 * up to ~60s — so API Gateway can 504 the client while the Lambda keeps running to completion
 * (and gets billed) in the background. The client's retry used to land as a brand new request
 * and pay a second time. Now: only the first request for a given hash ("the leader") is allowed
 * to call Gemini; anyone else asking about the exact same content while that's in flight is told
 * to check back shortly (see the 202 branch below) instead of starting a second paid call.
 *
 * Returns one of:
 *   { done: true, text }          — an answer already exists and is fresh; use it, no Gemini call.
 *   { done: false, leader: true } — caller must call Gemini, then finishAiRequest()/releaseAiRequest().
 *   { done: false, leader: false }— someone else is already working on this; wait and retry.
 */
async function claimAiRequest(requestHash) {
  const peek = await db.query(
    `SELECT status, response_text,
            (status = 'done' AND completed_at > now() - ($2 || ' minutes')::interval) AS fresh_done,
            (status = 'pending' AND started_at > now() - ($3 || ' seconds')::interval) AS active_pending
     FROM ai_response_cache WHERE request_hash = $1`,
    [requestHash, AI_RESPONSE_CACHE_TTL_MINUTES, AI_LEADER_STALE_SECONDS]
  );
  const row = peek.rows[0];
  if (row?.fresh_done) return { done: true, text: row.response_text };
  if (row?.active_pending) return { done: false, leader: false };

  // No row, or a done/pending row old enough to be considered expired/abandoned — try to
  // become the leader. The WHERE clause re-checks the same freshness conditions atomically,
  // so a race between two concurrent callers can only let one of them win.
  const claim = await db.query(
    `INSERT INTO ai_response_cache (request_hash, status, started_at, response_text)
     VALUES ($1, 'pending', now(), NULL)
     ON CONFLICT (request_hash) DO UPDATE SET
       status = 'pending', started_at = now(), response_text = NULL
     WHERE (ai_response_cache.status = 'done' AND ai_response_cache.completed_at <= now() - ($2 || ' minutes')::interval)
        OR (ai_response_cache.status = 'pending' AND ai_response_cache.started_at <= now() - ($3 || ' seconds')::interval)
     RETURNING request_hash`,
    [requestHash, AI_RESPONSE_CACHE_TTL_MINUTES, AI_LEADER_STALE_SECONDS]
  );
  if (claim.rows.length > 0) return { done: false, leader: true };
  // Lost the race between the peek and the claim — someone else just became leader.
  return { done: false, leader: false };
}

async function finishAiRequest(requestHash, text) {
  await db.query(
    `UPDATE ai_response_cache SET status = 'done', response_text = $2, completed_at = now() WHERE request_hash = $1`,
    [requestHash, text]
  );
  if (Math.random() < 0.02) {
    db.query(`DELETE FROM ai_response_cache WHERE status = 'done' AND completed_at < now() - interval '30 minutes'`)
      .catch((err) => console.error('Failed to sweep ai_response_cache:', err.message));
  }
}

// Called when the leader fails BEFORE reaching Gemini (rate-limited, quota exceeded, error) —
// removes the 'pending' row immediately so the next attempt can retry right away instead of
// waiting out the full AI_LEADER_STALE_SECONDS thinking someone else is still working on it.
function releaseAiRequest(requestHash) {
  db.query(`DELETE FROM ai_response_cache WHERE request_hash = $1 AND status = 'pending'`, [requestHash])
    .catch((err) => console.error('Failed to release ai_response_cache row:', err.message));
}

app.post('/api/ai/generate', requireDeviceOrUser, async (req, res) => {
  let requestHash = null;
  try {
    const { prompt, images, operation } = req.body || {};
    if (typeof prompt !== 'string' || !prompt.trim()) {
      return res.status(400).json({ error: 'A prompt is required.' });
    }
    const imgs = Array.isArray(images) ? images : [];
    if (imgs.length > MAX_AI_PROXY_IMAGES) {
      return res.status(400).json({ error: 'Too many images in one request.' });
    }
    for (const img of imgs) {
      if (typeof img?.data !== 'string' || !img.data) {
        return res.status(400).json({ error: 'Each image needs base64 "data".' });
      }
    }

    const op = operation || 'scan';
    const callerId = req.auth.kind === 'user' ? req.auth.user.id : req.auth.deviceId;
    requestHash = aiRequestHash(callerId, op, prompt, imgs);

    const claim = await claimAiRequest(requestHash);
    if (claim.done) {
      return res.json({ text: claim.text });
    }
    if (!claim.leader) {
      return res.status(202).set('Retry-After', '3').json({ processing: true });
    }

    const resolved = req.auth.kind === 'user'
      ? await resolveKeysForUser(req.auth.user)
      : await resolveKeysForDevice(req.auth.deviceId);

    if (resolved.rateLimited) {
      releaseAiRequest(requestHash);
      return res.status(503).set('Retry-After', '10').json({
        error: 'High demand right now. Please try again in a few seconds.',
      });
    }
    if (resolved.quotaExceeded || !resolved.geminiKey) {
      releaseAiRequest(requestHash);
      return res.status(429).json({
        error: 'Daily free analysis limit reached. Try again tomorrow, or add your own Gemini key in Settings.',
      });
    }

    const ai = new GoogleGenAI({ apiKey: resolved.geminiKey });
    const parts = [
      ...imgs.map((img) => ({ inlineData: { data: img.data, mimeType: img.mimeType || 'image/jpeg' } })),
      prompt,
    ];

    const deviceRowId = req.auth.kind === 'device' ? await getOrCreateDevice(req.auth.deviceId) : null;
    const response = await runWithUsageContext(
      { userId: req.auth.kind === 'user' ? req.auth.user.id : null, deviceId: deviceRowId, operation: op, keyIndex: resolved.geminiKeyIndex },
      () => trackGemini(ai, { model: process.env.GEMINI_MODEL || 'gemini-3.6-flash', contents: parts })
    );

    const text = (response.text || '').trim();
    await finishAiRequest(requestHash, text);

    res.json({ text });
  } catch (error) {
    console.error('AI proxy error:', error);
    if (requestHash) releaseAiRequest(requestHash);
    res.status(502).json({ error: 'The analysis service is temporarily unavailable. Please try again shortly.' });
  }
});

const MAX_TTS_TEXT_LENGTH = 5000;

// Text-to-speech proxy — the phone used to call Sarvam/Gemini TTS directly with an embedded
// key; now it just sends the text and gets back audio clips, same as /api/ai/generate. A TTS
// miss (bad language, upstream down, no key configured) is NOT an error from the client's
// point of view — it responds 200 with an empty audios array so the UI can just skip playback.
app.post('/api/ai/tts', requireDeviceOrUser, async (req, res) => {
  try {
    const { text, language, engine } = req.body || {};
    if (typeof text !== 'string' || !text.trim()) {
      return res.status(400).json({ error: 'Text is required.' });
    }
    // Truncate rather than reject. The old on-device path silently capped at 8 chunks of 450
    // chars (~3600) and still spoke the beginning, so rejecting a long Doctor Brief outright
    // would regress it from "reads the first part" to total silence — a worse outcome than
    // the cap it replaced.
    const safeText = text.length > MAX_TTS_TEXT_LENGTH ? text.slice(0, MAX_TTS_TEXT_LENGTH) : text;

    const result = await generateSpeech(safeText, language || 'English', engine || 'sarvam');
    res.json({ audios: Array.isArray(result?.audios) ? result.audios : [] });
  } catch (error) {
    console.error('TTS proxy error:', error);
    res.json({ audios: [] });
  }
});

// Translation proxy — same idea, previously the phone called Sarvam directly. On any failure
// this responds with the ORIGINAL text (not an error), since untranslated text is still
// usable and better than blocking the caller.
app.post('/api/ai/translate', requireDeviceOrUser, async (req, res) => {
  const { text, targetLanguage } = req.body || {};
  if (typeof text !== 'string') {
    return res.status(400).json({ error: 'Text is required.' });
  }
  try {
    const translated = await translateText(text, targetLanguage || 'English');
    res.json({ translated_text: translated });
  } catch (error) {
    console.error('Translate proxy error:', error);
    res.json({ translated_text: text });
  }
});

// ─── Healthcare & Lab Test Discovery ──────────────────────────────────────────

// 1. Search (Universal endpoint for UHI and Commercial)
app.post('/api/discovery/search', requireAuth, async (req, res) => {
  try {
    const { latitude, longitude, category, query, mode = 'commercial' } = req.body || {};

    if (latitude === undefined || longitude === undefined) {
      return res.status(400).json({ error: 'Latitude and longitude coordinates are required.' });
    }
    if (!category || !['hospitals', 'lab_tests', 'doctors'].includes(category)) {
      return res.status(400).json({ error: 'Valid category (hospitals, lab_tests, doctors) is required.' });
    }

    const latFloat = parseFloat(latitude);
    const lngFloat = parseFloat(longitude);

    if (isNaN(latFloat) || isNaN(lngFloat)) {
      return res.status(400).json({ error: 'Latitude and longitude must be valid numbers.' });
    }

    if (mode === 'uhi') {
      const response = await startUhiSearch(req.user.id, {
        latitude: latFloat,
        longitude: lngFloat,
        category,
        query
      });
      return res.status(202).json(response);
    } else {
      const results = await searchCommercial(latFloat, lngFloat, category, query);
      return res.json({ results });
    }
  } catch (error) {
    console.error('Discovery search failed:', error);
    res.status(500).json({ error: 'Healthcare discovery search failed.' });
  }
});

// 2. Poll/Retrieve UHI Webhook Results
app.get('/api/discovery/results', requireAuth, async (req, res) => {
  try {
    const { search_id } = req.query;
    if (!search_id) {
      return res.status(400).json({ error: 'search_id is required.' });
    }

    const result = await db.query(
      'SELECT results, intent, created_at FROM uhi_search_sessions WHERE search_id = $1',
      [search_id]
    );

    if (result.rows.length === 0) {
      return res.status(404).json({ error: 'Search session not found.' });
    }

    res.json({
      search_id,
      intent: result.rows[0].intent,
      created_at: result.rows[0].created_at,
      results: result.rows[0].results
    });
  } catch (error) {
    console.error('Failed to retrieve UHI results:', error);
    res.status(500).json({ error: 'Failed to retrieve search results.' });
  }
});

// 3. Public Webhook callback endpoint for UHI/Beckn Protocol
app.post('/api/discovery/uhi/on_search', async (req, res) => {
  try {
    await processUhiWebhook(req.body);
    res.json({ status: 'ACK' });
  } catch (error) {
    console.error('UHI Webhook handler failed:', error);
    res.status(500).json({ error: 'Webhook processing failed.' });
  }
});

// Global error handler
app.use((err, req, res, next) => {
  if (err) {
    return res.status(400).json({ error: err.message });
  }
  next();
});

// Start listening only when run directly (not when imported by lambda.js on AWS)
if (!isLambda) {
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`Backend server running on http://0.0.0.0:${PORT}`);
    console.log(`Test connection locally at http://localhost:${PORT}/api/health`);
  });
}

export default app;

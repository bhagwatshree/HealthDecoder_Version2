import crypto from 'crypto';
import db from './db.js';
import { decrypt } from './auth.js';

// How many AI "issuances" (see resolveKeysForUser below) a free-tier user gets per day
// before they must add their own Gemini key. This is OUR OWN cap — separate from
// whatever request/day limit Google enforces on each pooled key — and exists purely to
// keep any single free user from starving the others sharing their pool key.
export const FREE_TIER_DAILY_LIMIT = parseInt(process.env.FREE_TIER_DAILY_LIMIT || '50', 10);

// Comma-separated in GEMINI_API_KEYS — scales automatically with however many keys are
// listed there (no code change needed to add/remove pool capacity, just update the secret).
function loadGeminiKeyPool() {
  const raw = process.env.GEMINI_API_KEYS || process.env.GEMINI_API_KEY || '';
  return raw.split(',').map(k => k.trim()).filter(Boolean);
}

/**
 * Deterministically (stickily) assigns each caller to ONE key in the pool, based on a hash
 * of their id (a logged-in user's id, or an anonymous device's device_id — this function
 * doesn't care which, it just needs a stable string). This is the "multiple free tiers"
 * mechanism: if you configure N Gemini API keys (each its own Google Cloud project, each
 * with its own free-tier quota) in GEMINI_API_KEYS, callers are spread evenly across the N
 * projects. A heavy caller only ever consumes the daily quota of THEIR assigned project, not
 * everyone else's — so adding more keys to the pool directly raises how many the free tier supports.
 */
function assignedHouseKey(id) {
  const pool = loadGeminiKeyPool();
  if (pool.length === 0) return null;
  const hash = crypto.createHash('sha256').update(String(id)).digest();
  const index = hash.readUInt32BE(0) % pool.length;
  return pool[index];
}

// The day-rollover check is done entirely in SQL (comparing Postgres's own CURRENT_DATE to
// the stored DATE column) rather than in JS — round-tripping a DATE through a JS Date and
// toISOString() converts it through UTC, which spuriously looks like "a new day" for a large
// part of each day in any timezone ahead of UTC (e.g. IST). Never compare those two ad hoc.

/** Read-only: today's usage count, without mutating anything. */
async function peekUsage(userId) {
  const result = await db.query(
    `SELECT CASE WHEN usage_period_start = CURRENT_DATE THEN usage_count ELSE 0 END AS effective_count
     FROM users WHERE id = $1`,
    [userId]
  );
  return result.rows[0]?.effective_count ?? 0;
}

/** Atomically increments (or resets-then-sets-to-1 on a new day) and returns the new count. */
async function incrementUsage(userId) {
  const result = await db.query(
    `UPDATE users SET
       usage_count = CASE WHEN usage_period_start = CURRENT_DATE THEN usage_count + 1 ELSE 1 END,
       usage_period_start = CURRENT_DATE
     WHERE id = $1
     RETURNING usage_count`,
    [userId]
  );
  return result.rows[0].usage_count;
}

// ── Anonymous device identity (no OTP/login) ───────────────────────────────────────────────
// Phone OTP sign-in is optional/off by default, so most installs never become a `users` row.
// These mirror peekUsage/incrementUsage exactly, but against the `devices` table keyed by the
// client-generated device_id, so the AI proxy can still meter/pool a key per install.

/** Inserts the device on first contact (idempotent) and returns its row id (used to attribute
 *  api_usage_events.device_id). Safe to call on every request — cheap upsert, just bumps last_seen_at. */
export async function getOrCreateDevice(deviceId) {
  const result = await db.query(
    `INSERT INTO devices (device_id) VALUES ($1)
     ON CONFLICT (device_id) DO UPDATE SET last_seen_at = CURRENT_TIMESTAMP
     RETURNING id`,
    [deviceId]
  );
  return result.rows[0].id;
}

async function peekUsageForDevice(deviceId) {
  const result = await db.query(
    `SELECT CASE WHEN usage_period_start = CURRENT_DATE THEN usage_count ELSE 0 END AS effective_count
     FROM devices WHERE device_id = $1`,
    [deviceId]
  );
  return result.rows[0]?.effective_count ?? 0;
}

async function incrementUsageForDevice(deviceId) {
  const result = await db.query(
    `UPDATE devices SET
       usage_count = CASE WHEN usage_period_start = CURRENT_DATE THEN usage_count + 1 ELSE 1 END,
       usage_period_start = CURRENT_DATE,
       last_seen_at = CURRENT_TIMESTAMP
     WHERE device_id = $1
     RETURNING usage_count`,
    [deviceId]
  );
  return result.rows[0]?.usage_count ?? 0;
}

/** Read-only device usage view, mirroring peekAssignmentForUser. */
export async function peekAssignmentForDevice(deviceId) {
  const usageToday = await peekUsageForDevice(deviceId);
  return {
    billedTo: usageToday >= FREE_TIER_DAILY_LIMIT ? 'none' : 'free',
    usageToday,
    limit: FREE_TIER_DAILY_LIMIT,
    quotaExceeded: usageToday >= FREE_TIER_DAILY_LIMIT,
  };
}

/**
 * Resolves which Gemini/Sarvam keys an ANONYMOUS device (no login) should use for one AI
 * proxy call. Unlike resolveKeysForUser's original design (meter once per "issuance" because
 * the phone used to call Gemini directly, many times, per issued key), the app now calls our
 * own /api/ai/generate proxy once per actual Gemini request — so this metiers per call, which
 * is a more accurate cost signal and is what both resolveKeysForUser and this are used for now.
 */
export async function resolveKeysForDevice(deviceId) {
  const sarvamKey = process.env.SARVAM_API_KEY || null;
  const usageToday = await peekUsageForDevice(deviceId);
  if (usageToday >= FREE_TIER_DAILY_LIMIT) {
    return { geminiKey: null, sarvamKey, billedTo: 'none', usageToday, limit: FREE_TIER_DAILY_LIMIT, quotaExceeded: true };
  }
  const newCount = await incrementUsageForDevice(deviceId);
  return {
    geminiKey: assignedHouseKey(deviceId),
    sarvamKey,
    billedTo: 'free',
    usageToday: newCount,
    limit: FREE_TIER_DAILY_LIMIT,
    quotaExceeded: false,
  };
}

/**
 * Read-only view of the same assignment resolveKeysForUser would return, WITHOUT consuming
 * a free-tier issuance. Safe to call as often as you like (e.g. every time the Account screen
 * is opened, to just display today's usage) — unlike resolveKeysForUser, it never increments.
 */
export async function peekAssignmentForUser(user) {
  const ownGeminiKey = decrypt(user.own_gemini_key);
  const usageToday = await peekUsage(user.id);
  const billedTo = ownGeminiKey ? 'own' : (user.plan === 'premium' ? 'premium' : (usageToday >= FREE_TIER_DAILY_LIMIT ? 'none' : 'free'));
  return {
    plan: user.plan,
    billedTo,
    usageToday,
    limit: FREE_TIER_DAILY_LIMIT,
    quotaExceeded: billedTo === 'none',
  };
}

/**
 * Resolves which Gemini/Sarvam keys a logged-in user's device should use right now, and
 * accounts for free-tier usage. Call this once per "issuance" (e.g. once per app session
 * or once per day on the phone, NOT once per Gemini call — the phone calls Gemini directly,
 * so we meter at key-handout time rather than per-request). Use peekAssignmentForUser instead
 * for anything that just displays usage (e.g. the Account screen) — this one increments.
 */
export async function resolveKeysForUser(user) {
  const ownGeminiKey = decrypt(user.own_gemini_key);
  const ownSarvamKey = decrypt(user.own_sarvam_key);
  const sarvamKey = ownSarvamKey || process.env.SARVAM_API_KEY || null;

  if (ownGeminiKey) {
    return {
      geminiKey: ownGeminiKey,
      sarvamKey,
      plan: user.plan,
      billedTo: 'own',
      usageToday: await peekUsage(user.id),
      limit: FREE_TIER_DAILY_LIMIT,
      quotaExceeded: false,
    };
  }

  if (user.plan === 'premium') {
    return {
      geminiKey: assignedHouseKey(user.id),
      sarvamKey,
      plan: user.plan,
      billedTo: 'premium',
      usageToday: await peekUsage(user.id),
      limit: FREE_TIER_DAILY_LIMIT,
      quotaExceeded: false,
    };
  }

  const usageToday = await peekUsage(user.id);
  if (usageToday >= FREE_TIER_DAILY_LIMIT) {
    return {
      geminiKey: null,
      sarvamKey,
      plan: user.plan,
      billedTo: 'none',
      usageToday,
      limit: FREE_TIER_DAILY_LIMIT,
      quotaExceeded: true,
    };
  }

  const newCount = await incrementUsage(user.id);

  return {
    geminiKey: assignedHouseKey(user.id),
    sarvamKey,
    plan: user.plan,
    billedTo: 'free',
    usageToday: newCount,
    limit: FREE_TIER_DAILY_LIMIT,
    quotaExceeded: false,
  };
}

import { AsyncLocalStorage } from 'async_hooks';
import db from './db.js';
import { costGeminiUsd, costSarvamUsd, costFirebaseVerifyUsd } from './pricing.js';

const als = new AsyncLocalStorage();

/** Wraps a request handler so trackGemini/trackSarvam/trackFirebaseVerify calls inside it
 *  are attributed to the given operation/user (or anonymous device — [deviceId] is the
 *  `devices.id` UUID row, from keyPool.getOrCreateDevice, not the client's own device_id
 *  string), then flushes them to api_usage_events once the handler settles (success or
 *  failure) — logging failures never affect the response. */
export async function runWithUsageContext({ userId, deviceId, operation, keyIndex }, fn) {
  const events = [];
  try {
    return await als.run({ userId, deviceId, operation, keyIndex, events }, fn);
  } finally {
    if (events.length > 0) {
      await flushEvents(events).catch((err) => console.error('Failed to write usage events:', err.message));
    }
  }
}

function record(event) {
  const store = als.getStore();
  if (!store) return; // Not inside a tracked request (e.g. called from a script) — skip silently.
  store.events.push({ userId: store.userId, deviceId: store.deviceId, operation: store.operation, keyIndex: store.keyIndex, ...event });
}

async function flushEvents(events) {
  for (const e of events) {
    await db.query(
      `INSERT INTO api_usage_events
         (user_id, device_id, provider, operation, model, input_tokens, output_tokens, units, latency_ms, cost_usd, success, gemini_key_index)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)`,
      [e.userId || null, e.deviceId || null, e.provider, e.operation, e.model || null, e.inputTokens ?? null,
       e.outputTokens ?? null, e.units ?? null, e.latencyMs ?? null, e.costUsd || 0, e.success !== false,
       e.provider === 'gemini' ? (e.keyIndex ?? null) : null]
    );
  }
}

/** Wraps `ai.models.generateContent(params)`, timing it and logging tokens + estimated cost. */
export async function trackGemini(ai, params) {
  const start = Date.now();
  let success = true;
  try {
    const response = await ai.models.generateContent(params);
    const usage = response.usageMetadata || {};
    record({
      provider: 'gemini',
      model: params.model,
      inputTokens: usage.promptTokenCount,
      outputTokens: usage.candidatesTokenCount,
      latencyMs: Date.now() - start,
      costUsd: costGeminiUsd(params.model, usage.promptTokenCount, usage.candidatesTokenCount),
      success: true,
    });
    return response;
  } catch (error) {
    success = false;
    record({ provider: 'gemini', model: params.model, latencyMs: Date.now() - start, costUsd: 0, success: false });
    throw error;
  }
}

/** Wraps a Sarvam API call. `sarvamOp` ('translate' | 'tts' | 'doc-digitization' | 'chat') is
 *  stored in the `model` column so the request-level `operation` (ocr/chat/compare/...) set by
 *  runWithUsageContext is preserved for feature-level grouping. `meta` describes what to bill
 *  it as: { chars } for translate/tts, { pages } for doc-digitization, {inputTokens,outputTokens}
 *  for chat. `fn` is the actual fetch/network call to run and time. */
export async function trackSarvam(sarvamOp, meta, fn) {
  const start = Date.now();
  try {
    const result = await fn();
    record({
      provider: 'sarvam',
      model: sarvamOp,
      units: meta.chars ?? meta.pages ?? null,
      latencyMs: Date.now() - start,
      costUsd: costSarvamUsd(sarvamOp, meta),
      success: true,
    });
    return result;
  } catch (error) {
    record({ provider: 'sarvam', model: sarvamOp, latencyMs: Date.now() - start, costUsd: 0, success: false });
    throw error;
  }
}

/** Logs one Firebase phone-auth ID token verification. Writes directly (fire-and-forget) rather
 *  than through the request-scoped `record`/`runWithUsageContext` machinery, since signup/login
 *  routes call this standalone rather than around an AI operation. */
export function trackFirebaseVerify(userId, success) {
  db.query(
    `INSERT INTO api_usage_events (user_id, provider, operation, units, cost_usd, success)
     VALUES ($1, 'firebase', 'otp-verify', 1, $2, $3)`,
    [userId || null, success ? costFirebaseVerifyUsd() : 0, success]
  ).catch((err) => console.error('Failed to write usage event:', err.message));
}

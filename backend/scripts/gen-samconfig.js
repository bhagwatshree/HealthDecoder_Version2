// Generates backend/samconfig.toml from environment variables so `sam deploy` never needs
// secrets typed on a command line (fragile to escape, visible in shell history/process list).
// Used by both local deploys (env vars sourced from a developer's shell) and CI (sourced from
// GitHub Actions secrets) — see .github/workflows/backend-deploy.yml.
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const paramMap = {
  DATABASE_URL: 'DatabaseUrl',
  GEMINI_API_KEY: 'GeminiApiKey',
  GEMINI_API_KEYS: 'GeminiApiKeys',
  SARVAM_API_KEY: 'SarvamApiKey',
  JWT_SECRET: 'JwtSecret',
  ENCRYPTION_KEY: 'EncryptionKey',
  FIREBASE_SERVICE_ACCOUNT_JSON: 'FirebaseServiceAccountJson',
  GOOGLE_CLIENT_ID: 'GoogleClientId',
  GOOGLE_CLIENT_SECRET: 'GoogleClientSecret',
};

const missing = Object.keys(paramMap).filter((k) => process.env[k] === undefined);
if (missing.length) {
  console.error(`Missing required env vars: ${missing.join(', ')}`);
  process.exit(1);
}

// CloudFormation parameter_overrides is a single space-separated `Key="Value"` string.
// Escape backslashes and double quotes so multi-line JSON (FirebaseServiceAccountJson)
// survives intact inside the TOML string.
function esc(v) {
  return String(v ?? '').split('\\').join('\\\\').split('"').join('\\"');
}

const overrides = Object.entries(paramMap)
  .map(([envKey, paramName]) => `${paramName}=\\"${esc(process.env[envKey])}\\"`)
  .join(' ');
const freeTierLimit = process.env.FREE_TIER_DAILY_LIMIT || '50';

const toml = [
  'version = 0.1',
  '[default.deploy.parameters]',
  'stack_name = "medical-scanner"',
  'region = "us-east-1"',
  'resolve_s3 = true',
  'capabilities = "CAPABILITY_IAM"',
  'confirm_changeset = false',
  `parameter_overrides = "${overrides} FreeTierDailyLimit=\\"${freeTierLimit}\\""`,
  '',
].join('\n');

const outPath = path.join(__dirname, '..', 'samconfig.toml');
fs.writeFileSync(outPath, toml, { mode: 0o600 });
console.log(`wrote ${outPath} (${fs.statSync(outPath).size} bytes, ${Object.keys(paramMap).length + 1} parameters)`);

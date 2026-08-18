// One-time helper: reads the locally generated samconfig.toml (produced by gen-samconfig.js /
// today's manual deploy) and writes each deploy secret as a plain KEY=value line to a separate
// file, for copying into GitHub repo secrets by hand. Run this yourself; delete the output file
// once you've copied the values into GitHub. Never commit or share that output file.
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const samconfigPath = path.join(__dirname, '..', 'samconfig.toml');
const outPath = path.join(__dirname, '..', 'github-secrets.txt');

const toml = fs.readFileSync(samconfigPath, 'utf8');
const match = toml.match(/parameter_overrides\s*=\s*"([\s\S]*)"\s*$/m);
if (!match) throw new Error('Could not find parameter_overrides in samconfig.toml');

// Un-escape the TOML string, then split "Key=\"Value\" Key2=\"Value2\" ..." back into pairs.
// Values are matched non-greedily up to the next `="` boundary that starts a new Key=, since
// values themselves may contain spaces (e.g. JSON blobs).
const raw = match[1].replace(/\\"/g, '"').replace(/\\\\/g, '\\');
const pairs = [];
const re = /([A-Za-z]+)="((?:[^"]|(?<!\s)"(?!\s*[A-Za-z]+="))*)"(?=\s+[A-Za-z]+="|\s*$)/g;
let m;
while ((m = re.exec(raw))) pairs.push([m[1], m[2]]);

const nameMap = {
  DatabaseUrl: 'DATABASE_URL',
  GeminiApiKey: 'GEMINI_API_KEY',
  GeminiApiKeys: 'GEMINI_API_KEYS',
  SarvamApiKey: 'SARVAM_API_KEY',
  JwtSecret: 'JWT_SECRET',
  EncryptionKey: 'ENCRYPTION_KEY',
  FirebaseServiceAccountJson: 'FIREBASE_SERVICE_ACCOUNT_JSON',
  GoogleClientId: 'GOOGLE_CLIENT_ID',
  GoogleClientSecret: 'GOOGLE_CLIENT_SECRET',
  FreeTierDailyLimit: 'FREE_TIER_DAILY_LIMIT (not needed as a GitHub secret, has a default)',
};

const lines = pairs.map(([k, v]) => `${nameMap[k] || k} = ${v}`);
fs.writeFileSync(outPath, lines.join('\n\n') + '\n', { mode: 0o600 });
console.log(`Wrote ${pairs.length} values to ${outPath} — open it yourself, copy into GitHub, then delete it.`);

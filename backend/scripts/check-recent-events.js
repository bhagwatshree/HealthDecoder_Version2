import db from '../db.js';

const { rows } = await db.query(
  `SELECT operation, provider, model, cost_usd, success, created_at
   FROM api_usage_events
   ORDER BY created_at DESC
   LIMIT 12`
);
for (const r of rows) {
  console.log(`${r.created_at.toISOString()}  op=${r.operation}  provider=${r.provider}  cost=$${r.cost_usd}  success=${r.success}`);
}
process.exit(0);

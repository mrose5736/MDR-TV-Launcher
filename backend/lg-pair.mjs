// Usage: node lg-pair.mjs <tvId> <tv-ip>   (accept the prompt on the TV within 60s)
import { lgSession, saveCredentials } from "./lgtv.js";

const [tvId, ip] = process.argv.slice(2);
if (!tvId || !ip) {
  console.error("Usage: node lg-pair.mjs <tvId> <tv-ip>");
  process.exit(1);
}

console.log("Accept the pairing prompt on the TV...");
const clientKey = await lgSession({ ip }, async (_send, key) => key, 60000);
saveCredentials(tvId, { ip, clientKey });
console.log(`Paired ${tvId} (${ip}); saved to lgtv.json`);

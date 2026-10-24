// Usage: node lg-pair.mjs <tvId> <tv-ip> [netcast]
//   webOS:   accept the prompt on the TV within 60s.
//   netcast: (2013-2014 sets, e.g. 42LB570V) type the 6-digit key the TV displays.
import readline from "node:readline/promises";
import { lgSession, netcastAuth, saveCredentials } from "./lgtv.js";

const [tvId, ip, kind] = process.argv.slice(2);
if (!tvId || !ip) {
  console.error("Usage: node lg-pair.mjs <tvId> <tv-ip> [netcast]");
  process.exit(1);
}

if (kind === "netcast") {
  const tv = { ip };
  await fetch(`http://${ip}:8080/roap/api/auth`, {
    method: "POST",
    headers: { "Content-Type": "application/atom+xml" },
    body: '<?xml version="1.0" encoding="utf-8"?><auth><type>AuthKeyReq</type></auth>'
  });
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const clientKey = (await rl.question("Key shown on the TV: ")).trim();
  rl.close();
  await netcastAuth(tv, clientKey);
  saveCredentials(tvId, { ip, type: "netcast", clientKey });
} else {
  console.log("Accept the pairing prompt on the TV...");
  const clientKey = await lgSession({ ip }, async (_send, key) => key, 60000);
  saveCredentials(tvId, { ip, clientKey });
}
console.log(`Paired ${tvId} (${ip}); saved to lgtv.json`);

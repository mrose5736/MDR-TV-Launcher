// Usage: node lg-pair.mjs <tvId> <tv-ip> [netcast]
//   webOS:   accept the prompt on the TV within 60s.
//   netcast: (2013-2014 sets, e.g. 42LB570V) type the 6-digit key the TV displays.
import readline from "node:readline/promises";
import { netcastFinishPair, netcastStartPair, pairWebos } from "./lgtv.js";

const [tvId, ip, kind] = process.argv.slice(2);
if (!tvId || !ip) {
  console.error("Usage: node lg-pair.mjs <tvId> <tv-ip> [netcast]");
  process.exit(1);
}

if (kind === "netcast") {
  await netcastStartPair(ip);
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const key = (await rl.question("Key shown on the TV: ")).trim();
  rl.close();
  await netcastFinishPair(tvId, ip, key);
} else {
  console.log("Accept the pairing prompt on the TV...");
  await pairWebos(tvId, ip);
}
console.log(`Paired ${tvId} (${ip}); saved to lgtv.json`);

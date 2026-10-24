import fs from "node:fs";

// Minimal LG webOS (SSAP) client. One short-lived connection per call keeps it stateless.
// Pairing details live in lgtv.json ({ "<tvId>": { "ip": "...", "clientKey": "..." }}),
// kept out of the launcher config because that config is served to the browser.
const credentialsFile = process.env.LG_TVS_FILE ?? new URL("./lgtv.json", import.meta.url);

const manifest = {
  manifestVersion: 1,
  appVersion: "1.0",
  permissions: ["LAUNCH", "CONTROL_INPUT_TV", "READ_INPUT_DEVICE_LIST", "READ_RUNNING_APPS", "CONTROL_POWER", "READ_POWER_STATE"]
};

export function readCredentials() {
  try {
    return JSON.parse(fs.readFileSync(credentialsFile, "utf8"));
  } catch {
    return {};
  }
}

export function saveCredentials(tvId, tv) {
  fs.writeFileSync(credentialsFile, JSON.stringify({ ...readCredentials(), [tvId]: tv }, null, 2));
}

export function lgSession(tv, run, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://${tv.ip}:3000`);
    const pending = new Map();
    let counter = 0;
    const timer = setTimeout(() => finish(reject, new Error("LG TV timed out")), timeoutMs);

    const finish = (settle, value) => {
      clearTimeout(timer);
      try { ws.close(); } catch {}
      settle(value);
    };
    const send = (uri, payload) => new Promise((ok, fail) => {
      const id = `r${++counter}`;
      pending.set(id, { ok, fail });
      ws.send(JSON.stringify({ type: "request", id, uri, payload }));
    });

    ws.onerror = () => finish(reject, new Error("Cannot reach LG TV"));
    ws.onopen = () => ws.send(JSON.stringify({
      type: "register",
      id: "reg",
      payload: { forcePairing: false, pairingType: "PROMPT", "client-key": tv.clientKey, manifest }
    }));
    ws.onmessage = async event => {
      const message = JSON.parse(event.data);
      if (message.type === "registered") {
        try { finish(resolve, await run(send, message.payload["client-key"])); }
        catch (error) { finish(reject, error); }
      } else if (message.id === "reg" && message.type === "error") {
        finish(reject, new Error(message.error));
      } else if (pending.has(message.id)) {
        const { ok, fail } = pending.get(message.id);
        pending.delete(message.id);
        if (message.type === "error") fail(new Error(message.error));
        else ok(message.payload);
      }
    };
  });
}

export function lgCall(tvId, run) {
  const tv = readCredentials()[tvId];
  if (!tv?.ip || !tv?.clientKey) {
    return Promise.reject(Object.assign(new Error("TV is not paired. Run: node lg-pair.mjs <tvId> <ip>"), { status: 404 }));
  }
  return lgSession(tv, run);
}

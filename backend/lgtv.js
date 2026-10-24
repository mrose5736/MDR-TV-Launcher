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

function pairedTv(tvId) {
  const tv = readCredentials()[tvId];
  if (!tv?.ip || !tv?.clientKey) {
    throw Object.assign(new Error("TV is not paired. Run: node lg-pair.mjs <tvId> <ip> [netcast]"), { status: 404 });
  }
  return tv;
}

// 2013-2014 "Netcast" sets (e.g. 42LB570V) only take remote-key presses over HTTP.
// Input switching = open the input list, move along its tile row, press OK.
const netcastDefaultOrder = ["ANTENNA", "HDMI1", "AV1", "AV2", "COMPONENT", "HDMI2", "HDMI3"];
const KEY = { power: 1, up: 12, down: 13, left: 14, right: 15, ok: 20, input: 47, exit: 412 };
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const queues = new Map();

async function roap(tv, path, body) {
  const response = await fetch(`http://${tv.ip}:8080/roap/api/${path}`, {
    method: body ? "POST" : "GET",
    headers: { "Content-Type": "application/atom+xml" },
    body: body && `<?xml version="1.0" encoding="utf-8"?>${body}`,
    signal: AbortSignal.timeout(8000)
  });
  const text = await response.text();
  const code = /<ROAPError>(\d+)/.exec(text)?.[1];
  if (!response.ok || (code && code !== "200")) throw new Error(`LG TV error ${code ?? response.status}`);
  return text;
}

export const netcastKey = (tv, code) => roap(tv, "command", `<command><name>HandleKeyInput</name><value>${code}</value></command>`);
export const netcastAuth = (tv, key) => roap(tv, "auth", `<auth><type>AuthReq</type><value>${key}</value></auth>`);
const normalizeInput = id => {
  const name = String(id).toUpperCase().replace(/_/g, "");
  return name.startsWith("COMP") ? "COMPONENT" : name;
};
async function netcastCurrentInput(tv) {
  const name = /<inputSourceName>([^<]*)/.exec(await roap(tv, "data?target=cur_channel"))?.[1];
  return normalizeInput(name || "ANTENNA");
}

// Key sequences from different requests must not interleave on one TV.
function netcast(tvId, tv, task) {
  const run = (queues.get(tvId) ?? Promise.resolve()).then(async () => {
    await netcastAuth(tv, tv.clientKey);
    return task();
  });
  queues.set(tvId, run.catch(() => {}));
  return run;
}

async function netcastSwitch(tv, inputId) {
  const order = (tv.inputOrder ?? netcastDefaultOrder).map(normalizeInput);
  const target = order.indexOf(normalizeInput(inputId));
  if (target < 0) throw Object.assign(new Error(`Unknown input ${inputId}; known: ${order.join(", ")}`), { status: 400 });
  const current = order.indexOf(await netcastCurrentInput(tv));
  if (current < 0) throw new Error("Cannot tell which input the TV is on");
  if (current === target) return;
  await netcastKey(tv, KEY.input);
  await sleep(2000);
  await netcastKey(tv, KEY.down);
  const step = target > current ? KEY.right : KEY.left;
  for (let i = 0; i < Math.abs(target - current); i++) { await sleep(500); await netcastKey(tv, step); }
  await sleep(500);
  await netcastKey(tv, KEY.ok);
}

export async function lgInputs(tvId) {
  const tv = pairedTv(tvId);
  if (tv.type === "netcast") {
    return (tv.inputOrder ?? netcastDefaultOrder).map(id => ({ id, label: id, connected: null }));
  }
  const { devices = [] } = await lgSession(tv, send => send("ssap://tv/getExternalInputList"));
  return devices.map(({ id, label, connected }) => ({ id, label, connected }));
}

export async function lgSwitchInput(tvId, inputId) {
  const tv = pairedTv(tvId);
  if (tv.type === "netcast") return netcast(tvId, tv, () => netcastSwitch(tv, inputId));
  await lgSession(tv, send => send("ssap://tv/switchInput", { inputId }));
}

export async function lgOff(tvId) {
  const tv = pairedTv(tvId);
  if (tv.type === "netcast") return netcast(tvId, tv, () => netcastKey(tv, KEY.power));
  await lgSession(tv, send => send("ssap://system/turnOff"));
}

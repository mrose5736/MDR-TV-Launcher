import express from "express";
import { MongoClient } from "mongodb";

const app = express();
const port = Number(process.env.PORT ?? 3000);
const mongoUrl = process.env.MONGO_URL ?? "mongodb://127.0.0.1:27017";
const dbName = process.env.MONGO_DB ?? "tv_launcher_example";

app.use(express.json({ limit: "1mb" }));

const client = new MongoClient(mongoUrl);
await client.connect();

const db = client.db(dbName);
const tvLauncher = db.collection("TVLauncher");

function unixTime() {
  return Math.floor(Date.now() / 1000);
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function validateTvId(req, res) {
  const tvId = String(req.params.tvId ?? "").trim();

  if (!tvId) {
    res.status(400).json({ error: "Missing TV id", code: 400 });
    return null;
  }

  return tvId;
}

function validateJsonBody(req, res) {
  if (!isPlainObject(req.body)) {
    res.status(400).json({ error: "Expected JSON object body", code: 400 });
    return null;
  }

  return req.body;
}

async function getLauncherData(tvId, res) {
  const record = await tvLauncher.findOne(
    { tvId },
    { projection: { _id: 0, data: 1 } }
  );

  if (!record) {
    res.status(404).json({
      ok: false,
      error: "TV launcher data not found",
      code: 404
    });
    return null;
  }

  return record.data ?? {};
}

function localTvBaseUrl(data, res) {
  const notificationStatusUrl = String(data?.deviceStatus?.notificationStatusUrl ?? "");
  const notificationUrl = String(data?.deviceStatus?.notificationUrl ?? "");
  const sourceUrl = notificationStatusUrl || notificationUrl;

  if (!sourceUrl) {
    res.status(409).json({
      ok: false,
      error: "TV has not reported a local API URL yet",
      code: 409
    });
    return null;
  }

  try {
    const url = new URL(sourceUrl);
    return `${url.protocol}//${url.host}`;
  } catch {
    res.status(409).json({
      ok: false,
      error: "TV reported an invalid local API URL",
      code: 409
    });
    return null;
  }
}

async function callLocalTv(data, path, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), options.timeoutMs ?? 2500);
  try {
    const baseUrl = localTvBaseUrl(data, options.res);
    if (!baseUrl) return null;

    try {
      const response = await fetch(`${baseUrl}${path}`, {
        method: options.method ?? "GET",
        headers: options.body ? { "Content-Type": "application/json" } : undefined,
        body: options.body ? JSON.stringify(options.body) : undefined,
        signal: controller.signal
      });

      const text = await response.text();
      let json;
      try {
        json = text ? JSON.parse(text) : {};
      } catch {
        json = { raw: text };
      }

      return {
        ok: response.ok && json?.ok !== false,
        status: response.status,
        tvResponse: json
      };
    } catch (error) {
      return {
        ok: false,
        status: 0,
        tvResponse: {
          ok: false,
          error: error.name === "AbortError" ? "TV local API timed out" : String(error.message ?? error)
        }
      };
    }
  } finally {
    clearTimeout(timeout);
  }
}

function encodePathPart(value) {
  return encodeURIComponent(String(value ?? ""));
}

app.post("/api/tvlauncher/:tvId/create", async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const data = validateJsonBody(req, res);
  if (!data) return;

  const now = unixTime();
  const result = await tvLauncher.updateOne(
    { tvId },
    {
      $setOnInsert: {
        tvId,
        data,
        createdAt: now,
        createdAtFormatted: new Date(now * 1000).toISOString(),
        updatedAt: now,
        updatedAtFormatted: new Date(now * 1000).toISOString()
      }
    },
    { upsert: true }
  );

  if (result.upsertedCount === 0) {
    return res.status(409).json({
      ok: false,
      error: "TV launcher data already exists",
      code: 409
    });
  }

  res.json({ ok: true, created: true, tvId, createdAt: now, data });
});

app.post(["/api/tvlauncher/:tvId", "/api/tvlauncher/:tvId/data"], async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const data = validateJsonBody(req, res);
  if (!data) return;

  const now = unixTime();
  const existing = await tvLauncher.findOne(
    { tvId },
    { projection: { createdAt: 1 } }
  );

  await tvLauncher.updateOne(
    { tvId },
    {
      $set: {
        tvId,
        data,
        createdAt: existing?.createdAt ?? now,
        updatedAt: now,
        updatedAtFormatted: new Date(now * 1000).toISOString()
      }
    },
    { upsert: true }
  );

  res.json({ ok: true, tvId, updatedAt: now, data });
});

app.post("/api/tvlauncher/:tvId/keys", async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const data = validateJsonBody(req, res);
  if (!data) return;

  const keys = Object.keys(data);
  if (keys.length === 0) {
    return res.status(400).json({ error: "No keys supplied", code: 400 });
  }

  const existing = await tvLauncher.findOne(
    { tvId },
    { projection: { _id: 1 } }
  );

  if (!existing) {
    return res.status(404).json({
      ok: false,
      error: "TV launcher data not found",
      code: 404
    });
  }

  const now = unixTime();
  const set = {
    updatedAt: now,
    updatedAtFormatted: new Date(now * 1000).toISOString()
  };

  for (const key of keys) {
    const cleanKey = String(key).trim();

    if (!cleanKey || cleanKey.includes(".") || cleanKey.includes("$")) {
      return res.status(400).json({ error: `Invalid key: ${cleanKey}`, code: 400 });
    }

    set[`data.${cleanKey}`] = data[key];
  }

  await tvLauncher.updateOne({ tvId }, { $set: set });

  const record = await tvLauncher.findOne(
    { tvId },
    { projection: { _id: 0, data: 1 } }
  );

  res.json({
    ok: true,
    tvId,
    updatedAt: now,
    updatedKeys: keys,
    data: record?.data ?? {}
  });
});

app.get(["/api/tvlauncher/:tvId", "/api/tvlauncher/:tvId/data"], async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const data = await getLauncherData(tvId, res);
  if (!data) return;

  res.json(data);
});

app.get("/api/tvlauncher/:tvId/local/options", async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const data = await getLauncherData(tvId, res);
  if (!data) return;

  const result = await callLocalTv(data, "/options", { res });
  if (!result) return;

  res.status(result.ok ? 200 : 502).json({ ok: result.ok, tvId, ...result });
});

app.post("/api/tvlauncher/:tvId/local/notify", async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const body = validateJsonBody(req, res);
  if (!body) return;

  const data = await getLauncherData(tvId, res);
  if (!data) return;

  const result = await callLocalTv(data, "/notify", { method: "POST", body, res });
  if (!result) return;

  res.status(result.ok ? 200 : 502).json({ ok: result.ok, tvId, ...result });
});

app.post("/api/tvlauncher/:tvId/local/input", async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const body = validateJsonBody(req, res);
  if (!body) return;

  const data = await getLauncherData(tvId, res);
  if (!data) return;

  const result = await callLocalTv(data, "/input", { method: "POST", body, res });
  if (!result) return;

  res.status(result.ok ? 200 : 502).json({ ok: result.ok, tvId, ...result });
});

app.get("/api/tvlauncher/:tvId/local/input/:inputId", async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const data = await getLauncherData(tvId, res);
  if (!data) return;

  const result = await callLocalTv(data, `/input/${encodePathPart(req.params.inputId)}`, { res });
  if (!result) return;

  res.status(result.ok ? 200 : 502).json({ ok: result.ok, tvId, ...result });
});

app.post("/api/tvlauncher/:tvId/local/key", async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const body = validateJsonBody(req, res);
  if (!body) return;

  const data = await getLauncherData(tvId, res);
  if (!data) return;

  const result = await callLocalTv(data, "/key", { method: "POST", body, res });
  if (!result) return;

  res.status(result.ok ? 200 : 502).json({ ok: result.ok, tvId, ...result });
});

app.post("/api/tvlauncher/:tvId/local/action", async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const body = validateJsonBody(req, res);
  if (!body) return;

  const data = await getLauncherData(tvId, res);
  if (!data) return;

  const result = await callLocalTv(data, "/action", { method: "POST", body, res });
  if (!result) return;

  res.status(result.ok ? 200 : 502).json({ ok: result.ok, tvId, ...result });
});

app.get("/api/tvlauncher/:tvId/local/action/:action", async (req, res) => {
  const tvId = validateTvId(req, res);
  if (!tvId) return;

  const data = await getLauncherData(tvId, res);
  if (!data) return;

  const result = await callLocalTv(data, `/action/${encodePathPart(req.params.action)}`, { res });
  if (!result) return;

  res.status(result.ok ? 200 : 502).json({ ok: result.ok, tvId, ...result });
});

app.get("/health", (_req, res) => {
  res.json({ ok: true });
});

app.listen(port, () => {
  console.log(`TV Launcher example backend listening on :${port}`);
});

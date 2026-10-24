import crypto from "node:crypto";
import express from "express";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lgStatus, netcastFinishPair, netcastStartPair, pairWebos, pairedTvs } from "./lgtv.js";

const adminDirectory = path.join(path.dirname(fileURLToPath(import.meta.url)), "public", "admin");
const tvIdPattern = /^[a-z0-9][a-z0-9_-]{0,39}$/i;
const privateIpv4 = /^(10\.\d{1,3}|192\.168|172\.(1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}$/;
const inputKeyPattern = /^[a-z0-9_-]{1,40}$/i;
const inputIdPattern = /^[A-Z0-9_]{2,16}$/;

const digest = value => crypto.createHash("sha256").update(String(value)).digest();

function requireAdmin(req, res, next) {
  const password = process.env.ADMIN_PASSWORD;
  if (!password) return res.status(503).send("Set ADMIN_PASSWORD to enable the admin dashboard");

  const [scheme, token = ""] = (req.headers.authorization ?? "").split(" ");
  const given = scheme === "Basic" ? Buffer.from(token, "base64").toString().split(":").slice(1).join(":") : "";
  if (crypto.timingSafeEqual(digest(given), digest(password))) return next();

  res.set("WWW-Authenticate", 'Basic realm="TV Launcher admin"').status(401).send("Authentication required");
}

function badRequest(message) {
  return Object.assign(new Error(message), { status: 400 });
}

function cleanInputs(inputs) {
  if (inputs === null || typeof inputs !== "object" || Array.isArray(inputs)) throw badRequest("inputs must be an object");
  const result = {};
  for (const [key, value] of Object.entries(inputs)) {
    if (!inputKeyPattern.test(key)) throw badRequest(`Invalid input key: ${key}`);
    if (!inputIdPattern.test(String(value?.inputId ?? ""))) throw badRequest(`Invalid inputId for ${key}, expected e.g. HDMI_2`);
    result[key] = {
      inputId: value.inputId,
      label: String(value.label ?? value.inputId).slice(0, 40),
      hidden: value.hidden === true,
      order: Number.isFinite(Number(value.order)) ? Number(value.order) : 99
    };
  }
  return result;
}

function route(handler) {
  return async (req, res) => {
    try {
      if (req.params.tvId !== undefined && !tvIdPattern.test(req.params.tvId)) throw badRequest("Invalid TV id");
      res.json({ ok: true, ...(await handler(req)) });
    } catch (error) {
      res.status(error.status ?? 502).json({ ok: false, error: error.message });
    }
  };
}

export function registerAdmin(app, { tvLauncher, unixTime }) {
  app.use(["/admin", "/api/admin"], requireAdmin);
  app.use("/admin", express.static(adminDirectory, {
    index: "index.html",
    maxAge: 0,
    setHeaders(response) {
      response.setHeader("Cache-Control", "no-cache");
      response.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
      response.setHeader("X-Content-Type-Options", "nosniff");
    }
  }));

  app.get("/api/admin/tvs", route(async () => {
    const records = await tvLauncher.find({}, { projection: { _id: 0, tvId: 1, data: 1 } }).toArray();
    const paired = pairedTvs();
    const tvs = records.map(({ tvId, data = {} }) => ({
      tvId,
      name: data.name ?? tvId,
      room: data.room ?? "",
      inputs: data.inputs ?? {},
      paired: paired[tvId] ?? null
    }));
    return { tvs };
  }));

  app.put("/api/admin/tvs/:tvId", route(async req => {
    const { name, room = "", inputs = {} } = req.body ?? {};
    if (!String(name ?? "").trim()) throw badRequest("name is required");
    const now = unixTime();
    await tvLauncher.updateOne(
      { tvId: req.params.tvId },
      {
        $set: {
          "data.name": String(name).trim().slice(0, 60),
          "data.room": String(room).trim().slice(0, 60),
          "data.inputs": cleanInputs(inputs),
          updatedAt: now
        },
        $setOnInsert: { tvId: req.params.tvId, createdAt: now, "data.uiVersion": 2 }
      },
      { upsert: true }
    );
    return { tvId: req.params.tvId };
  }));

  app.get("/api/admin/tvs/:tvId/status", route(async req => ({ status: await lgStatus(req.params.tvId) })));

  // webOS: blocks up to 60s while the owner accepts the prompt on the TV.
  // Netcast: first call (no key) makes the TV show a key, second call sends it back.
  app.post("/api/admin/tvs/:tvId/pair", route(async req => {
    const { ip, type, key } = req.body ?? {};
    if (!privateIpv4.test(String(ip))) throw badRequest("ip must be a private IPv4 address");
    if (type === "netcast") {
      if (!key) {
        await netcastStartPair(ip);
        return { needsKey: true };
      }
      if (!/^\d{4,8}$/.test(String(key))) throw badRequest("key should be the digits shown on the TV");
      await netcastFinishPair(req.params.tvId, ip, String(key));
    } else if (type === "webos") {
      await pairWebos(req.params.tvId, ip);
    } else {
      throw badRequest("type must be webos or netcast");
    }
    return { paired: true };
  }));
}

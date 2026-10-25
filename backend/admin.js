import crypto from "node:crypto";
import fs from "node:fs";
import express from "express";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { lgStatus, netcastFinishPair, netcastStartPair, pairWebos, pairedTvs } from "./lgtv.js";

const adminDirectory = path.join(path.dirname(fileURLToPath(import.meta.url)), "public", "admin");
// Logos come from thesvg.org, are downloaded once and kept beside the pairing keys
// (not in git: they are third-party trademarks).
const iconsDirectory = process.env.ICONS_DIR ?? path.join(path.dirname(fileURLToPath(import.meta.url)), "icons");
const iconSpecPattern = /^[a-z0-9][a-z0-9-]{0,60}(\/[a-zA-Z]{3,15})?$/;
let registry = { loadedAt: 0, icons: [] };
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

async function fetchIcon(spec) {
  if (!iconSpecPattern.test(spec)) throw badRequest(`Invalid icon ${spec}, expected e.g. youtube or apple-tv/mono`);
  const [slug, variant = "default"] = spec.split("/");
  const name = `${slug}-${variant}.svg`;
  const file = path.join(iconsDirectory, name);
  if (!fs.existsSync(file)) {
    const response = await fetch(`https://thesvg.org/icons/${slug}/${variant}.svg`, { signal: AbortSignal.timeout(10000) });
    let svg = await response.text();
    if (!response.ok || svg.length > 300000 || !/^\s*(<\?xml[^>]*>\s*)?<svg[\s>]/i.test(svg)) throw badRequest(`Icon not found on thesvg.org: ${spec}`);
    // Mono icons carry no colour of their own; the launcher is dark, so draw them white.
    if (variant === "mono") svg = svg.replace(/<svg/i, '<svg fill="#ffffff"');
    fs.mkdirSync(iconsDirectory, { recursive: true });
    fs.writeFileSync(file, svg);
  }
  return `/tvlauncher/icons/${name}`;
}

async function searchIcons(query) {
  if (Date.now() - registry.loadedAt > 3600000) {
    const response = await fetch("https://thesvg.org/api/registry.json", { signal: AbortSignal.timeout(20000) });
    registry = { loadedAt: Date.now(), icons: (await response.json()).icons ?? [] };
  }
  const needle = String(query).toLowerCase().trim();
  if (needle.length < 2) return [];
  return registry.icons
    .filter(icon => icon.slug.includes(needle) || String(icon.title).toLowerCase().includes(needle) || icon.aliases?.some(alias => String(alias).toLowerCase().includes(needle)))
    .slice(0, 12)
    .map(({ slug, title, variants }) => ({ slug, title, variants }));
}

async function cleanApps(apps) {
  if (!Array.isArray(apps)) throw badRequest("webApps must be an array");
  return Promise.all(apps.slice(0, 20).map(async (app, index) => {
  if (!Array.isArray(apps)) throw badRequest("webApps must be an array");
    let url;
    try { url = new URL(String(app?.url)); } catch { throw badRequest(`Invalid URL for ${app?.label ?? "web app"}`); }
    if (!/^https?:$/.test(url.protocol)) throw badRequest("Web app URLs must be http or https");
    const label = String(app.label || url.hostname).trim().slice(0, 40);
    const slug = label.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "app";
    const icon = String(app.icon ?? "").trim();
    return {
      id: `${slug}-${index}`, label, url: url.href, subtitle: "Web app", order: 200 + index,
      ...(icon ? { icon, iconImageUrl: await fetchIcon(icon) } : {})
    };
  }));
}

async function geocode(place) {
  const response = await fetch(`https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=${encodeURIComponent(place)}`, { signal: AbortSignal.timeout(8000) });
  const hit = (await response.json()).results?.[0];
  if (!hit) throw badRequest(`Place not found: ${place}`);
  return { place: [hit.name, hit.admin1].filter(Boolean).join(", "), latitude: hit.latitude, longitude: hit.longitude };
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
  app.use("/tvlauncher/icons", express.static(iconsDirectory, {
    maxAge: "7d",
    setHeaders(response) {
      response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
      response.setHeader("X-Content-Type-Options", "nosniff");
    }
  }));
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
      webApps: data.webApps ?? [],
      weatherPlace: data.weather?.place ?? "",
      paired: paired[tvId] ?? null
    }));
    return { tvs };
  }));

  app.put("/api/admin/tvs/:tvId", route(async req => {
    const { name, room = "", inputs = {}, webApps = [], weatherPlace } = req.body ?? {};
    if (!String(name ?? "").trim()) throw badRequest("name is required");
    const now = unixTime();
    const weather = String(weatherPlace ?? "").trim() ? await geocode(String(weatherPlace).trim()) : null;
    await tvLauncher.updateOne(
      { tvId: req.params.tvId },
      {
        ...(weather || weatherPlace === undefined ? {} : { $unset: { "data.weather": "" } }),
        $set: {
          ...(weather ? { "data.weather": weather } : {}),
          "data.webApps": await cleanApps(webApps),
          "data.name": String(name).trim().slice(0, 60),
          "data.room": String(room).trim().slice(0, 60),
          "data.inputs": cleanInputs(inputs),
          updatedAt: now
        },
        $setOnInsert: {
          tvId: req.params.tvId, createdAt: now, "data.uiVersion": 2,
          "data.deviceCommands": [{ id: "off", label: "Turn TV off", order: 10, url: `/api/tvlauncher/${req.params.tvId}/lg/off`, method: "POST" }]
        }
      },
      { upsert: true }
    );
    return { tvId: req.params.tvId };
  }));

  app.get("/api/admin/icons", route(async req => ({ icons: await searchIcons(req.query.q ?? "") })));

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

const params = new URLSearchParams(window.location.search);
const tvId = params.get('tvId') || '';
const nativeBridge = params.get('nativeBridge') === '1';
const apiBase = new URL((params.get('apiBase') || location.origin).replace(/\/$/, '') + '/');
const $ = selector => document.querySelector(selector);
const elements = {
  name: $('#tv-name'), subtitle: $('#tv-subtitle'), clock: $('#clock'),
  sources: $('#sources'), quick: $('#quick-select'), quickSection: $('#quick-section'),
  controls: $('#device-controls'), controlsPanel: $('#controls-panel'),
  wallpaper: $('#wallpaper'), weather: $('#weather'), apps: $('#web-apps'), appsSection: $('#apps-section'), status: $('#status'), statusDot: $('#status-dot')
};
let config = {}, loadedRevision, configTimer, stateTimer, stateGeneration = 0;
let stateUrl = '', stateSnapshot = {}, stateAbort;
let currentFocus = '', primaryFocus = '', didInitialFocus = false;
try { currentFocus = sessionStorage.getItem('tv-focus:' + tvId) || ''; } catch (_) {}

function text(element, value) {
  if (element && element.textContent !== String(value)) element.textContent = String(value);
}
function list(value) { return Array.isArray(value) ? value : []; }
function sorted(items) { return items.filter(item => item && !item.hidden).sort((a, b) => (a.order ?? 99999) - (b.order ?? 99999)); }
function nativeUrl(command, id) {
  const url = new URL('tvlauncher://' + command);
  if (id !== undefined) url.searchParams.set('id', id);
  return url.href;
}
// Web apps are plain links: the kiosk browser navigates to them. Only http(s) is allowed.
function webAppUrl(raw) {
  try { const url = new URL(raw); return /^https?:$/.test(url.protocol) ? url.href : '#'; } catch (_) { return '#'; }
}
function visible(selector = '.focusable') {
  return [...document.querySelectorAll(selector)].filter(el => el.offsetParent !== null);
}
function firstPrimary() { return visible('.source-card')[0] || visible('.quick-card')[0] || visible('.control-row')[0] || elements.clock; }
function byFocus(id) { return visible().find(el => el.dataset.focusId === id); }
function focusTile(element) {
  if (!element) return;
  if (document.activeElement !== element) element.focus({preventScroll: true});
  rememberFocus(element);
}
function rememberFocus(element) {
  if (!element.classList.contains('focusable')) return;
  currentFocus = element.dataset.focusId;
  if (element.dataset.zone === 'sources' || element.dataset.zone === 'quick') primaryFocus = currentFocus;
  try { sessionStorage.setItem('tv-focus:' + tvId, currentFocus); } catch (_) {}
}
document.addEventListener('focusin', event => rememberFocus(event.target));
window.addEventListener('focus', () => requestAnimationFrame(() => focusTile(byFocus(currentFocus) || firstPrimary())));

// Keep anchor nodes across native injections and API polls. A state update
// must never destroy the user's current focus target.
function reconcile(container, items, zone, command) {
  const remaining = new Map([...container.children].map(el => [el.dataset.focusId, el]));
  items.forEach((item, index) => {
    const id = String(command === 'input' ? item.inputId || item.key : item.id || (command + '-' + index));
    const focusId = command + ':' + id;
    let tile = remaining.get(focusId);
    remaining.delete(focusId);
    if (!tile) {
      tile = document.createElement('a');
      tile.className = 'card focusable ' + ({sources:'source-card', quick:'quick-card', controls:'control-row'})[zone];
      tile.dataset.focusId = focusId;
      tile.dataset.zone = zone;
      tile.href = command === 'app' ? webAppUrl(item.url) : nativeUrl(command, id);
      tile.innerHTML = '<span class="icon" aria-hidden="true"></span><span class="tile-copy"><span class="label"></span><span class="detail"></span></span><span class="tile-affordance" aria-hidden="true">›</span>';
    }
    tile._item = item;
    tile.dataset.kind = kindFor(item, zone);
    text(tile.querySelector('.label'), item.label || 'Device');
    updateTile(tile);
    if (container.children[index] !== tile) container.insertBefore(tile, container.children[index] || null);
  });
  remaining.forEach(tile => tile.remove());
}

function kindFor(item, zone) {
  if (item.iconType) return item.iconType;
  const label = String(item.label || '').toLowerCase();
  if (zone === 'sources' && label.includes('sky')) return 'sky';
  if (zone === 'sources' && label.includes('apple')) return 'apple';
  if (zone === 'quick' && /^f1\b/.test(label)) return 'f1';
  if (item.id === 'lights') return 'bulb';
  if (item.id === 'speaker') return 'speaker';
  if (item.id === 'guide') return 'guide';
  if (item.id === 'off' || item.id === 'power') return 'power';
  if (item.id === 'controls') return 'controls';
  return 'tv';
}
const iconFiles = {apple:'apple.logo', bulb:'lightbulb.2', speaker:'hifispeaker.2', guide:'list.bullet.rectangle', controls:'slider.horizontal.3', f1:'flag.checkered', power:'power', tv:'tv'};
function updateTile(tile) {
  const item = tile._item, kind = tile.dataset.kind;
  const state = item.stateKey ? stateSnapshot[item.stateKey] : null;
  const active = state && state.active === true;
  tile.dataset.state = state ? state.state : 'unknown';
  const icon = tile.querySelector('.icon');
  const imageUrl = item.iconImageUrl || item.iconUrl;
  const iconSignature = [kind, imageUrl || '', active].join('|');
  if (icon.dataset.signature !== iconSignature) {
    icon.dataset.signature = iconSignature;
    icon.replaceChildren();
    if (imageUrl) {
      const image = document.createElement('img');
      image.src = imageUrl; image.alt = ''; icon.append(image);
    } else if (kind === 'sky') {
      const image = document.createElement('img'); image.src = './symbols/sky-q.png'; image.alt = ''; image.className = 'sky-logo'; icon.append(image);
    } else {
      const image = document.createElement('img');
      image.src = './symbols/' + (kind === 'bulb' && active ? 'lightbulb.2.fill' : iconFiles[kind] || 'tv') + '.svg';
      image.alt = ''; image.className = 'symbol'; icon.append(image);
      if (kind === 'apple') { const suffix = document.createElement('span'); suffix.textContent = 'tv'; icon.append(suffix); }
    }
  }
  let detail = item.subtitle || ({sky:'Live TV & recordings', apple:'Apps, films & streaming', f1:'Sky Sports · Channel 407', guide:'Browse the programme guide', controls:'Playback & more'})[kind] || 'Select to open';
  if (item.stateKey) detail = state ? state.label : 'Checking state…';
  text(tile.querySelector('.detail'), detail);
  tile.classList.toggle('is-on', !!active);
  tile.classList.toggle('has-state', !!item.stateKey);
  tile.setAttribute('aria-label', (item.label || 'Device') + '. ' + detail);
}

// Start-up animation: plays when the launcher starts, not on every config-driven reload.
(function intro() {
  const overlay = $('#intro');
  if (!overlay) return;
  let skip = typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;
  try {
    skip = skip || Date.now() - Number(sessionStorage.getItem('intro-at') || 0) < 10 * 60 * 1000;
    sessionStorage.setItem('intro-at', Date.now());
  } catch (_) {}
  if (skip) { overlay.remove(); return; }
  const hour = new Date().getHours();
  text($('#intro-greeting'), hour < 5 ? 'Good night' : hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening');
  let name = '';
  try { name = localStorage.getItem('tv-name:' + tvId) || ''; } catch (_) {}
  text($('#intro-name'), name || 'Welcome');
  overlay.addEventListener('animationend', event => { if (event.animationName === 'introOut') overlay.remove(); });
  document.addEventListener('keydown', () => overlay.remove(), {once: true, capture: true});
})();

// Silk background unless a wallpaper image is configured. Plain dark page if WebGL2 is missing.
let silk = null, silkKey = null;
function configureBackground(wallpaper, options) {
  const holder = $('#silk');
  if (!holder || !window.TVSilk) return;
  holder.hidden = !!wallpaper;
  document.body.classList.toggle('has-silk', !wallpaper && !!silk);
  if (wallpaper) return;
  const key = JSON.stringify(options || {});
  if (!silk && silkKey === null) {
    silk = window.TVSilk.start(holder, options);
    silkKey = key;
    document.body.classList.toggle('has-silk', !!silk);
  } else if (silk && key !== silkKey) {
    silk.update(options);
    silkKey = key;
  }
}

function applyConfig(payload) {
  try {
    const parsed = typeof payload === 'string' ? JSON.parse(payload) : payload;
    config = parsed && parsed.data ? parsed.data : parsed || {};
    const settings = config.settings || {};
    const revision = String(config.webAppRevision || settings.webAppRevision || '');
    if (loadedRevision !== undefined && revision && loadedRevision !== revision) { location.reload(); return; }
    loadedRevision = revision;
    const focused = document.activeElement?.dataset?.focusId || currentFocus;
    text(elements.name, config.name || 'Your TV');
    try { localStorage.setItem('tv-name:' + tvId, config.name || ''); } catch (_) {}
    text(elements.subtitle, config.subtitle || '');
    elements.subtitle.hidden = !config.subtitle;
    const wallpaper = settings.backgroundImageUrl || settings.wallpaperUrl || '';
    if (elements.wallpaper.dataset.url !== wallpaper) {
      elements.wallpaper.dataset.url = wallpaper;
      elements.wallpaper.style.backgroundImage = wallpaper ? 'url(' + JSON.stringify(wallpaper) + ')' : '';
    }
    configureBackground(wallpaper, config.background);
    reconcile(elements.sources, sorted(Object.entries(config.inputs || {}).map(([key, value]) => ({key, ...value}))), 'sources', 'input');
    const quick = sorted(list(config.quickSelect || settings.quickSelect));
    reconcile(elements.quick, quick, 'quick', 'quick');
    elements.quickSection.hidden = !quick.length;
    const webApps = sorted(list(config.webApps || settings.webApps));
    reconcile(elements.apps, webApps, 'quick', 'app');
    elements.appsSection.hidden = !webApps.length;
    const commands = sorted(list(config.deviceCommands || settings.deviceCommands || config.projectorMenu || settings.projectorMenu));
    reconcile(elements.controls, commands, 'controls', 'command');
    elements.controlsPanel.hidden = !commands.length;
    $('.content-grid').classList.toggle('no-controls', !commands.length);
    // A comma-separated querySelector follows DOM order, not selector priority.
    // Explicitly choose a source first. Only refocus if a node was removed.
    if (!didInitialFocus) {
      focusTile(byFocus(currentFocus) || firstPrimary());
      didInitialFocus = true;
    } else if (!document.activeElement?.classList.contains('focusable')) focusTile(byFocus(focused) || firstPrimary());
    setStatus('Connected', 'online');
    configureState(config.roomStateUrl || '');
    configureWeather(config.weather);
  } catch (error) { setStatus('Configuration unavailable', 'offline'); }
}
window.tvLauncherApplyConfig = applyConfig;
window.tvLauncherRestoreFocus = () => focusTile(byFocus(currentFocus) || firstPrimary());

function directionForEvent(event) {
  return ({ArrowLeft:'left', Left:'left', ArrowRight:'right', Right:'right', ArrowUp:'up', Up:'up', ArrowDown:'down', Down:'down'})[event.key]
    || ({19:'up',20:'down',21:'left',22:'right',37:'left',38:'up',39:'right',40:'down'})[event.keyCode || event.which];
}
function neighbour(current, direction) {
  const sources = visible('.source-card'), quick = visible('.quick-card'), controls = visible('.control-row');
  const zone = current.dataset.zone;
  const row = zone === 'sources' ? sources : zone === 'quick' ? quick : controls;
  const index = row.indexOf(current);
  if (current === elements.clock) return direction === 'down' || direction === 'left' ? byFocus(primaryFocus) || firstPrimary() : current;
  if (zone === 'controls') {
    if (direction === 'up') return controls[index - 1] || elements.clock;
    if (direction === 'down') return controls[index + 1] || current;
    if (direction === 'left') return byFocus(primaryFocus) || sources[sources.length - 1] || quick[0] || current;
    return current;
  }
  if (direction === 'left') return row[index - 1] || current;
  if (direction === 'right') return row[index + 1] || controls[0] || current;
  if (direction === 'up') return zone === 'quick' ? sources[Math.min(index, sources.length - 1)] || elements.clock : elements.clock;
  if (direction === 'down') return zone === 'sources' ? quick[Math.min(index, quick.length - 1)] || current : current;
  return current;
}
function isSelect(event) { return event.key === 'Enter' || event.key === ' ' || [13,23,66].includes(event.keyCode || event.which); }
document.addEventListener('keydown', event => {
  const direction = directionForEvent(event);
  if (!direction && !isSelect(event)) return;
  event.preventDefault(); event.stopImmediatePropagation();
  const current = document.activeElement?.classList.contains('focusable') ? document.activeElement : byFocus(currentFocus) || firstPrimary();
  if (direction) focusTile(neighbour(current, direction));
  else if (!event.repeat) current.click();
}, true);
document.addEventListener('keyup', event => {
  if (directionForEvent(event) || isSelect(event)) { event.preventDefault(); event.stopImmediatePropagation(); }
}, true);
document.addEventListener('click', event => {
  const tile = event.target.closest('.card');
  if (tile?._item?.stateKey) {
    text(tile.querySelector('.detail'), 'Request sent · checking…');
    clearTimeout(stateTimer);
    stateTimer = setTimeout(refreshState, 1200);
  }
});

// Plain browser (kiosk PC, no Android shell): run each tile through the backend instead of tvlauncher://.
async function post(url, method, body) {
  const response = await fetch(new URL(url, apiBase), {
    method: method || 'GET', headers: body ? {'Content-Type': 'application/json'} : undefined,
    body: body ? JSON.stringify(body) : undefined
  });
  if (!response.ok) throw new Error((await response.json().catch(() => ({}))).error || response.statusText);
}
if (!nativeBridge) document.addEventListener('click', async event => {
  const tile = event.target.closest('a.card[href^="tvlauncher:"]');
  if (!tile) return;
  event.preventDefault();
  const item = tile._item || {};
  const lg = 'api/tvlauncher/' + encodeURIComponent(tvId) + '/lg/';
  setStatus('Opening ' + (item.label || 'input') + '…', 'pending');
  try {
    if (item.url && item.requestBeforeInput !== false) await post(item.url, item.method, item.body);
    if (item.inputId) await post(lg + 'input', 'POST', {inputId: item.inputId});
    if (item.url && item.requestBeforeInput === false) await post(item.url, item.method, item.body);
    setStatus('Connected', 'online');
  } catch (error) { setStatus(error.message, 'offline'); }
});

const weatherWords = code => code === 0 ? 'Clear' : code <= 2 ? 'Partly cloudy' : code === 3 ? 'Cloudy' : code <= 48 ? 'Fog' : code <= 57 ? 'Drizzle'
  : code <= 67 ? 'Rain' : code <= 77 ? 'Snow' : code <= 82 ? 'Showers' : code <= 86 ? 'Snow showers' : 'Thunderstorm';
let weatherKey = '', weatherTimer;
function configureWeather(place) {
  const key = place && isFinite(place.latitude) && isFinite(place.longitude) ? place.latitude + ',' + place.longitude : '';
  if (key === weatherKey) return;
  weatherKey = key; clearTimeout(weatherTimer); elements.weather.hidden = !key;
  if (key) refreshWeather(place);
}
async function refreshWeather(place) {
  try {
    const url = 'https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&timezone=auto&latitude=' + Number(place.latitude) + '&longitude=' + Number(place.longitude);
    const current = (await (await fetch(url)).json()).current;
    text(elements.weather, Math.round(current.temperature_2m) + '° · ' + weatherWords(current.weather_code));
  } catch (_) {}
  weatherTimer = setTimeout(() => refreshWeather(place), 15 * 60 * 1000);
}

function configureState(raw) {
  let next = '';
  try { const url = new URL(raw, location.href); if (raw && url.origin === location.origin) next = url.href; } catch (_) {}
  if (next === stateUrl) return;
  stateUrl = next; stateGeneration++;
  clearTimeout(stateTimer); stateAbort?.abort(); stateSnapshot = {};
  if (stateUrl) refreshState();
}
async function refreshState() {
  clearTimeout(stateTimer);
  if (!stateUrl || document.hidden) return;
  const generation = stateGeneration;
  stateAbort?.abort();
  const controller = new AbortController(); stateAbort = controller;
  const timeout = setTimeout(() => controller.abort(), 6000);
  try {
    const response = await fetch(stateUrl, {cache:'no-store', signal:controller.signal});
    if (!response.ok) throw new Error('State unavailable');
    const payload = await response.json();
    if (generation !== stateGeneration || stateAbort !== controller) return;
    stateSnapshot = payload.states || {};
  } catch (_) {
    if (generation !== stateGeneration || stateAbort !== controller) return;
    stateSnapshot = Object.fromEntries(visible('.control-row').filter(el => el._item.stateKey).map(el => [el._item.stateKey, {state:'unknown', active:false, label:'Unavailable'}]));
  } finally {
    clearTimeout(timeout);
    if (generation === stateGeneration && stateAbort === controller) {
      visible('.control-row').forEach(updateTile);
      if (!document.hidden) stateTimer = setTimeout(refreshState, 8000);
    }
  }
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { clearTimeout(stateTimer); stateAbort?.abort(); }
  else refreshState();
});
function updateClock() {
  text(elements.clock, new Intl.DateTimeFormat('en-GB', {hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date()));
  text($('#date'), new Intl.DateTimeFormat('en-GB', {weekday:'long',day:'numeric',month:'long'}).format(new Date()));
}
function setStatus(message, state) { text(elements.status, message); elements.statusDot.className = 'status-dot ' + state; }
async function loadConfig() {
  if (!tvId) { setStatus('Open on your TV', 'pending'); return; }
  try {
    const response = await fetch(new URL('api/tvlauncher/' + encodeURIComponent(tvId), apiBase), {cache:'no-store'});
    if (!response.ok) throw new Error('Config unavailable');
    applyConfig(await response.json());
  } catch (_) { setStatus('Server unavailable', 'offline'); }
  clearTimeout(configTimer);
  configTimer = setTimeout(loadConfig, Math.max(10, Number(config.settings?.configPollSeconds) || 20) * 1000);
}
updateClock(); setInterval(updateClock, 1000);
window.addEventListener('offline', () => setStatus('Offline', 'offline'));
if (nativeBridge) setStatus('Connecting…', 'pending'); else loadConfig();

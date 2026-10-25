// Household widgets for the launcher, built from the Home app's read-only calendar feed.
// The feed address contains a secret token, so it lives only in HOME_FEED_URL on the server;
// TVs just ask /api/widgets for already-formatted text (which also works in very old browsers).
const TIME_ZONE = process.env.DISPLAY_TIMEZONE ?? "Europe/London";
const CACHE_MS = 5 * 60 * 1000;
let cache = { at: 0, events: null };

const unescapeText = value => value.replace(/\\([nN,;\\])/g, (_m, c) => (c === "n" || c === "N" ? "\n" : c));
const dayFormat = new Intl.DateTimeFormat("en-CA", { timeZone: TIME_ZONE });
const timeFormat = new Intl.DateTimeFormat("en-GB", { timeZone: TIME_ZONE, hour: "2-digit", minute: "2-digit", hour12: false });
const labelFormat = new Intl.DateTimeFormat("en-GB", { timeZone: "UTC", weekday: "short", day: "numeric", month: "short" });
const dayOf = date => dayFormat.format(date);
const dayNumber = day => Date.parse(`${day}T00:00:00Z`) / 86400000;
const addDays = (day, n) => new Date((dayNumber(day) + n) * 86400000).toISOString().slice(0, 10);

function parseDate(value, params) {
  const m = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})Z?)?$/.exec(value);
  if (!m) return null;
  if (params.includes("VALUE=DATE") || !m[4]) return { allDay: true, day: `${m[1]}-${m[2]}-${m[3]}` };
  return { allDay: false, date: new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6])) };
}

export function parseIcs(text) {
  const lines = text.replace(/\r?\n[ \t]/g, "").split(/\r?\n/);
  const events = [];
  let current = null;
  for (const line of lines) {
    if (line === "BEGIN:VEVENT") current = {};
    else if (line === "END:VEVENT") {
      if (current?.start) events.push(current);
      current = null;
    } else if (current) {
      const colon = line.indexOf(":");
      if (colon < 0) continue;
      const [name, ...params] = line.slice(0, colon).split(";");
      const value = line.slice(colon + 1);
      if (name === "SUMMARY") current.title = unescapeText(value);
      else if (name === "LOCATION") current.location = unescapeText(value);
      else if (name === "DTSTART") current.start = parseDate(value, params);
      else if (name === "DTEND") current.end = parseDate(value, params);
    }
  }
  return events;
}

// Normalise to local calendar days so "today" and "in N days" behave across midnight and DST.
function normalise(event) {
  const title = (event.title ?? "").replace(/^[^\p{L}\p{N}]+/u, "").trim() || "Event";
  const { start, end } = event;
  if (start.allDay) {
    const last = end?.allDay && end.day > start.day ? addDays(end.day, -1) : start.day;
    return { title, raw: event.title ?? "", allDay: true, startDay: start.day, endDay: last, startTime: "", endMs: Date.parse(`${last}T23:59:59Z`) };
  }
  const endDate = end && !end.allDay ? end.date : start.date;
  return { title, raw: event.title ?? "", allDay: false, startDay: dayOf(start.date), endDay: dayOf(endDate), startTime: timeFormat.format(start.date), endMs: endDate.getTime(), startMs: start.date.getTime() };
}

const label = (day, today) => (day === today ? "Today" : day === addDays(today, 1) ? "Tomorrow" : labelFormat.format(new Date(`${day}T12:00:00Z`)));
const when = (e, today) => `${label(e.startDay, today)}${e.allDay ? "" : " · " + e.startTime}`;
const sortKey = e => `${e.startDay}T${e.startTime || "00:00"}`;
const isF1 = e => /🏁|\bF1\b|formula 1/i.test(e.raw);
const isNfl = e => /🏈|\bNFL\b|\bGiants\b/i.test(e.raw);
const isBirthday = e => /birthday/i.test(e.raw);

export function buildWidgets(rawEvents, now = new Date()) {
  const today = dayOf(now);
  const events = rawEvents.map(normalise).sort((a, b) => sortKey(a).localeCompare(sortKey(b)));
  const live = events.filter(e => e.endMs >= now.getTime() || e.endDay > today);
  const general = live.filter(e => !isF1(e) && !isNfl(e) && !isBirthday(e));
  const race = list => list.slice(0, 3).map(e => ({ title: e.title, when: when(e, today) }));

  return {
    today: general.filter(e => e.startDay <= today && e.endDay >= today).slice(0, 4).map(e => ({ title: e.title, when: e.allDay ? "All day" : e.startTime })),
    upcoming: general.filter(e => e.startDay > today).slice(0, 4).map(e => ({ title: e.title, when: when(e, today) })),
    birthdays: events.filter(e => isBirthday(e) && e.startDay >= today).slice(0, 3).map(e => {
      const days = dayNumber(e.startDay) - dayNumber(today);
      return { title: e.title, when: days === 0 ? "Today" : days === 1 ? "Tomorrow" : `in ${days} days` };
    }),
    f1: race(live.filter(isF1)),
    nfl: race(live.filter(isNfl))
  };
}

export async function getWidgets() {
  const url = process.env.HOME_FEED_URL;
  if (!url) throw new Error("HOME_FEED_URL is not set");
  if (!cache.events || Date.now() - cache.at > CACHE_MS) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(10000) });
      if (!response.ok) throw new Error(`feed HTTP ${response.status}`);
      cache = { at: Date.now(), events: parseIcs(await response.text()) };
    } catch (error) {
      // Keep serving the last good copy if the Home app is briefly unreachable.
      if (!cache.events) throw new Error("Home feed unavailable");
      cache.at = Date.now() - CACHE_MS + 30000;
    }
  }
  return { updated: new Date(cache.at).toISOString(), ...buildWidgets(cache.events) };
}

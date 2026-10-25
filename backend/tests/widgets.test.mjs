import assert from "node:assert/strict";
import { buildWidgets, parseIcs } from "../widgets.js";

const ics = [
  "BEGIN:VCALENDAR", "VERSION:2.0",
  "BEGIN:VEVENT", "UID:a", "DTSTART:20261026T140000Z", "DTEND:20261026T150000Z", "SUMMARY:Dentist\\, Rotherham", "END:VEVENT",
  "BEGIN:VEVENT", "UID:b", "DTSTART;VALUE=DATE:20261026", "DTEND;VALUE=DATE:20261027", "SUMMARY:Bins out", "END:VEVENT",
  "BEGIN:VEVENT", "UID:c", "DTSTART:20261031T120000Z", "DTEND:20261031T130000Z", "SUMMARY:\u{1F3C1} F1 Race — Mexico City Grand Prix", "END:VEVENT",
  "BEGIN:VEVENT", "UID:d", "DTSTART;VALUE=DATE:20261028", "DTEND;VALUE=DATE:20261029", "SUMMARY:Sam's 7th Birthday", "END:VEVENT",
  "BEGIN:VEVENT", "UID:e", "DTSTART:20261104T180000Z", "DTEND:20261104T190000Z", "SUMMARY:Parents' ", " evening", "END:VEVENT",
  "BEGIN:VEVENT", "UID:f", "DTSTART:20261001T100000Z", "DTEND:20261001T110000Z", "SUMMARY:Long past", "END:VEVENT",
  "END:VCALENDAR"
].join("\r\n");

const events = parseIcs(ics);
assert.equal(events.length, 6);
assert.equal(events[0].title, "Dentist, Rotherham"); // escaped comma
assert.equal(events[4].title, "Parents' evening"); // folded line

const w = buildWidgets(events, new Date("2026-10-26T09:00:00Z"));
assert.deepEqual(w.today.map(e => e.title), ["Bins out", "Dentist, Rotherham"].sort((a, b) => (a === "Bins out" ? -1 : 1)));
assert.equal(w.today.find(e => e.title === "Bins out").when, "All day");
assert.equal(w.birthdays[0].when, "in 2 days");
assert.equal(w.f1[0].title, "F1 Race — Mexico City Grand Prix"); // emoji stripped
assert.match(w.f1[0].when, /Sat 31 Oct · 12:00/);
assert.ok(!w.upcoming.some(e => /F1|Birthday|Long past/.test(e.title)));
assert.equal(w.upcoming[0].title, "Parents' evening");
assert.deepEqual(w.nfl, []);
console.log("PASS: widgets feed parsing and grouping");

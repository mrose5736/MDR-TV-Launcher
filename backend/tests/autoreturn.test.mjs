import assert from "node:assert/strict";
import { decide } from "../autoreturn.js";

let s;
// Signal seen, then lost: leave only after two consecutive missing polls.
({ next: s } = decide(undefined, true, 0));
assert.equal(decide(s, false, 10000).leave, false);
const second = decide(decide(s, false, 10000).next, false, 20000);
assert.equal(second.leave, true);
// A blip that recovers resets the count.
const blip = decide(decide(s, false, 10000).next, true, 20000);
assert.equal(decide(blip.next, false, 30000).leave, false);
// Never had signal: wait out the grace period, then leave.
({ next: s } = decide(undefined, false, 0));
assert.equal(decide(s, false, 30000).leave, false);
assert.equal(decide(s, false, 61000).leave, true);
console.log("PASS: auto-return decisions");

import assert from "node:assert/strict";
import { withSize } from "../admin.js";

const mono = '<svg fill="#ffffff" role="img" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M0 0"/></svg>';
assert.match(withSize(mono), /^<svg width="24" height="24" fill="#ffffff"/);
const wide = '<svg viewBox="0 0 256.5 180" xmlns="http://www.w3.org/2000/svg"><path/></svg>';
assert.match(withSize(wide), /width="256.5" height="180"/);
const sized = '<svg width="10" height="10" viewBox="0 0 24 24"><path/></svg>';
assert.equal(withSize(sized), sized); // already sized: untouched
assert.equal(withSize("<svg><path/></svg>"), "<svg><path/></svg>"); // no viewBox: untouched
console.log("PASS: svg intrinsic size");

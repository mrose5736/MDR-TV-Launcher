import { lgSnapshot, lgSwitchInput, pairedTvs } from "./lgtv.js";

const normalize = id => String(id).toUpperCase().replace(/_/g, "");
const NO_SIGNAL_GRACE_MS = 60000;
const OFF_POLLS_NEEDED = 2;

// Decide from one poll of the watched port. The signal must have been seen first, so
// choosing a source that is still waking up doesn't bounce straight back to the launcher.
// ponytail: webOS TVs only (Netcast sets don't report per-port signal).
export function decide(state, connected, now) {
  const next = { since: state?.since ?? now, seen: state?.seen ?? false, off: state?.off ?? 0 };
  if (connected) {
    next.seen = true;
    next.off = 0;
  } else {
    next.off += 1;
  }
  const leave = next.seen ? next.off >= OFF_POLLS_NEEDED : now - next.since > NO_SIGNAL_GRACE_MS;
  return { next, leave };
}

export function startAutoReturn(tvLauncher, { intervalMs = 10000, log = console } = {}) {
  const states = new Map();
  let running = false;

  async function check(tvId, inputs) {
    const watched = Object.values(inputs).filter(input => input.returnWhenOff);
    const launcher = inputs.launcher?.inputId;
    if (!watched.length || !launcher) return;

    const { current, inputs: ports } = await lgSnapshot(tvId);
    const input = watched.find(item => normalize(item.inputId) === normalize(current ?? ""));
    if (!input) return states.delete(tvId);

    const port = ports.find(item => normalize(item.id) === normalize(current));
    const { next, leave } = decide(states.get(tvId), port?.connected === true, Date.now());
    if (!leave) return states.set(tvId, next);

    states.delete(tvId);
    log.log(`${tvId}: ${input.label ?? current} is off, returning to ${launcher}`);
    await lgSwitchInput(tvId, launcher);
  }

  setInterval(async () => {
    if (running) return;
    running = true;
    try {
      const paired = pairedTvs();
      const records = await tvLauncher.find({ tvId: { $in: Object.keys(paired) } }, { projection: { tvId: 1, "data.inputs": 1 } }).toArray();
      for (const { tvId, data } of records) {
        if (paired[tvId].type === "netcast") continue;
        // An unreachable (off) TV is normal; try again next cycle.
        await check(tvId, data?.inputs ?? {}).catch(() => states.delete(tvId));
      }
    } finally {
      running = false;
    }
  }, intervalMs).unref();
}

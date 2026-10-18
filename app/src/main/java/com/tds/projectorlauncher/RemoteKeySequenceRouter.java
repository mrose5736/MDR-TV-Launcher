package com.tds.projectorlauncher;

import java.util.HashMap;
import java.util.Map;

/** Keep key-down and key-up on the same route across input/activity changes. */
final class RemoteKeySequenceRouter {
    interface Handler {
        boolean handle();
    }

    private final Map<Long, Boolean> routes = new HashMap<>();

    boolean route(int deviceId, int keyCode, boolean down, boolean up, int repeat,
                  Handler handler) {
        long key = ((long) deviceId << 32) | (keyCode & 0xffffffffL);
        if (down && repeat == 0) {
            boolean consumed = handler.handle();
            routes.put(key, consumed);
            return consumed;
        }
        Boolean consumed = up ? routes.remove(key) : routes.get(key);
        // A release/repeat without a down observed here belongs to the TV.
        // In particular, never swallow the release of OK used to open HDMI.
        if (!Boolean.TRUE.equals(consumed)) {
            return false;
        }
        if (up) {
            handler.handle();
        }
        return true;
    }

    void clear() {
        routes.clear();
    }
}

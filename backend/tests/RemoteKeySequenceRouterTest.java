package com.tds.projectorlauncher;

public final class RemoteKeySequenceRouterTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        RemoteKeySequenceRouter router = new RemoteKeySequenceRouter();
        int[] calls = {0};
        RemoteKeySequenceRouter.Handler bridge = () -> { calls[0]++; return true; };
        // OK starts on launcher; HDMI becomes active before key-up.
        check(!router.route(8, 23, true, false, 0, () -> false), "launcher down");
        check(!router.route(8, 23, true, false, 1, bridge), "launcher repeat");
        check(!router.route(8, 23, false, true, 0, bridge), "launcher release");
        check(calls[0] == 0, "launcher release must not send Sky command");
        // Next press starts on Sky and is consumed through release.
        check(router.route(8, 23, true, false, 0, bridge), "Sky down");
        check(router.route(8, 23, true, false, 1, bridge), "Sky repeat");
        check(calls[0] == 1, "repeat must not dispatch another command");
        check(!router.route(9, 23, false, true, 0, bridge), "other device release");
        check(router.route(8, 23, false, true, 0, bridge), "Sky release");
        check(calls[0] == 2, "Sky handler receives down and up exactly once");
        check(!router.route(8, 23, false, true, 0, bridge), "orphan release");
        check(!router.route(8, 23, true, false, 4, bridge), "orphan repeat");
        router.route(8, 19, true, false, 0, bridge);
        check(router.route(8, 19, false, true, 0, () -> false),
                "consumed down stays consumed if input/config changes");
        router.route(8, 23, true, false, 0, bridge);
        router.clear();
        check(!router.route(8, 23, false, true, 0, bridge), "service reset");
        System.out.println("Remote key sequence tests passed");
    }
}

package dev.kb896.aiusage;

/**
 * From the shell service back to the main app process: what was decided on the back that
 * only the app can keep. Registered with IRearService.setEvents. oneway: the shell never
 * waits on the app.
 */
oneway interface IRearEvents {
    // An app's DPI was nudged on the back: [dpi] for [pkg], 0 for its default (the entry
    // cleared). The app saves it in the config and pushes the payload back.
    void onPortalDpi(String pkg, int dpi) = 1;
    // A tile of the control centre the shell holds over a guest was tapped: switch [which]
    // ("silent", "bluetooth", "data" or "wifi") [on], as the panel's own tiles are switched,
    // and send the states back (IRearService.setQuickStates).
    void onQuick(String which, boolean on) = 2;
    // That control centre is coming down: send the tiles' states, as the panel's opening asks.
    void onControlCenterOpening() = 3;
    // The portal sheet came to rest open on the back: said once a process, the first time
    // (and again later only if nobody was listening). The app keeps it as portalSheetSeen,
    // which ticks the Portal tab's "Learn the gestures" step.
    void onPortalOpened() = 4;
    // The panel's broadcasts the shell used to send itself (`am broadcast -p`), now sent by the
    // app: the panel's receiver takes nothing from outside the app (RearActivity's receiver).
    // The sheet was touched or opened over the panel: re-arm its sleep (ACTION_PORTAL_POKE).
    void onPanelPoke() = 5;
    // Resume: the panel moves its own task back, the guest on top (ACTION_PORTAL_STEP_BACK).
    void onPanelStepBack() = 6;
    // "Keep right of the cameras" switched on the back for [pkg]: the app saves it in the config
    // (PanelConfig.portalConfined) and pushes the payload back, as onPortalDpi.
    void onPortalConfined(String pkg, boolean on) = 7;
    // A guest on a private display is up (true) or hidden (false), in our layers over the panel:
    // the panel, still resumed under them, stands down (ACTION_PORTAL_COVERED).
    void onPanelCovered(boolean covered) = 8;
    // A row of the notifications over a guest was swiped away.
    void onNoticeDismissed(String key) = 9;
    // An app kept right of the cameras set to [width] px wide on the back, 0 for the whole width
    // (PanelConfig.portalWidth): saved, and the payload pushed back, as onPortalDpi.
    void onPortalWidth(String pkg, int width) = 10;
    // A pull up from the very bottom with the panel's control centre down: close it, as a swipe up
    // on it would (IRearService.setPanelControlCenter).
    void onPanelCloseControlCenter() = 11;
    // The back's lock (BackLock) came on or went: the panel hides its notifications' words and
    // locks its control centre while it is on. Said on every change and on (re)connect.
    void onBackLock(boolean locked) = 12;
    // A recording of the back ([video] true), made by the shell in its own space and handed over open
    // ([file], read-only): the app copies it into the gallery. A path in the app's files was tried first:
    // what the shell writes there the app may not read. A screenshot comes by onShot now; one that still
    // comes here ([video] false, an older shell) is saved where onShot's are.
    void onCaptured(in ParcelFileDescriptor file, boolean video) = 13;
    // The back's recording began or ended: the panel's recording tile follows.
    void onRecording(boolean on) = 14;
    // The screenshot just taken, binned from its polaroid on the back: out of the gallery.
    void onDeleteLastShot() = 15;
    // Companion mode: the back went dark right after a double tap on it (ManualOff.byDoubleTap).
    // The app sets the double tap's latch, so no wake of its own lights it again until the user
    // does (ShizukuBridge.wake); the rear lighting up clears it (UsagePollService).
    void onSwitchedOffByHand() = 16;
    /**
     * A camera on the phone was opened or closed, as the camera service itself says (the shell
     * asks it: `dumpsys media.camera`). The app's own CameraManager callback does not hear
     * Xiaomi's Camera on this phone.
     */
    void onCameraOpen(boolean open) = 17;
    /** A notification's button pressed on the back: [index] among the ones the list was given (NoticeShade.Item.actions). */
    void onNoticeAction(String key, int index) = 18;
    /** A press and hold on the back picked the face [file] (a clip's file name): the app keeps it (PanelConfig.sceneLoop). */
    void onFacePicked(String file) = 19;
    /** The usage page has been swiped in on the back: fresh figures are welcome (the app keeps its own floor between fetches). */
    void onUsageShown() = 20;
    /** The note pinned at [at] was removed on the back (its bin): the app takes it off its list (PanelConfig.pins). */
    void onPinRemoved(long at) = 21;
    /** A button of the playing card pressed on the back: "previous", "playpause", "next" (RearActivity.MEDIA_*). */
    void onMediaCommand(String action) = 22;
    /** Button [index] of the live card pressed on the back: handed to the notification [key] it came from. */
    void onLiveAction(String key, int index) = 23;
    /** A finger on the back while the back is not being held lit: the app's stay-on rule looks again (it does not look by itself then). */
    void onRearTouch() = 24;
    /** A double tap on the back while it is dark: he wakes it by hand, which also ends "switched off by hand". */
    void onWokenByHand() = 25;
    // The face picker on the back chose a face ([key]: "moon" or "xiaomi:<id>", FaceCatalog), choice number [seq]
    // of the shell's: the app keeps it (PanelConfig.face, faceSeq) and sends it back with the payload. Code 28: 26 is
    // onNoticeSnoozed's.
    void onFaceChosen(String key, long seq) = 28;
    // The faces on the phone changed (FaceScan): the list is in the app's files (faces/faces.json, faces/<key>.jpg).
    void onFacesChanged() = 27;
    /**
     * The pinned notes were put in order on the back (held, carried, swapped): [order] is their names (Pin.at), the
     * first first. The app keeps it on its list as it has it then (PinOrder.apply: a note pinned since keeps its place
     * in front, one binned since is not there). Code 41, out of the way of the next ones in sequence.
     */
    void onPinsOrdered(in long[] order) = 41;
    /** "Not now" on a note standing on the back at its place (a stroke down on it): it waits for his next visit (place/Places.later). */
    void onArrivalLater(long at) = 61;
    /**
     * A notification thrown on the back the snoozing way (NoticeThrow; the other way is onNoticeDismissed): snoozed
     * on the phone for [ms] (an hour), from the app's listener (NotificationRelay); Android brings it back after.
     */
    void onNoticeSnoozed(String key, long ms) = 26;
    /**
     * A screenshot of the back (the control centre's tile, or three fingers held: rear/ThreeHold), made by the shell
     * and handed over open, read-only, with its file name (rear/BackShots.name). The app first CLAIMS it
     * (IRearService.claimShot) and saves it into DCIM/Screenshots only when told to: not claimed within two seconds
     * (HyperOS had the app frozen) the shell has saved it itself (rear/ShotKeeper). A high number of its own, as
     * IRearService's 117: other branches take the next free ones.
     */
    void onShot(in ParcelFileDescriptor file, String name) = 113;
    void onCircleGesture(long session, String action, long uptime, boolean onHome) = 114;
    // Real rear display wake edge; location access stays with the app.
    void onRearWoke() = 181;
    // The charging page is being shown. Read Android's battery now and return it through chargeDetail.
    void onChargeReading() = 182;
    void onRestingFaceDownChanged() = 189;
}

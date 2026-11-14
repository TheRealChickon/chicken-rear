package dev.kb896.aiusage;

import android.os.Bundle;
import dev.kb896.aiusage.IRearEvents;

/** Runs inside Shizuku's shell-privileged process (uid 2000). */
interface IRearService {
    // Shizuku-reserved destroy transaction id.
    void destroy() = 16777114;

    /**
     * Place RearActivity on displayId with an initial payload. A recovery: the rear is lit
     * afterwards only if it was lit before — place(..., light = true) is the one that lights it.
     */
    void show(String payload, int displayId) = 1;

    /**
     * Push a new payload to the already-placed activity. Never relaunches. Oneway: the app
     * calls it from its main thread and nothing waits on it, while the shell runs execs for it
     * (the broadcast, a density change). Oneway calls from one client run in order; a show()
     * (not oneway) may overtake one still queued, and both carry the whole payload.
     */
    oneway void update(String payload) = 2;

    /** Wake the panel. Required: FLAG_TURN_SCREEN_ON does not cover this. */
    void wake(int displayId) = 3;

    /** Tell the activity to finish. */
    void hide() = 4;

    /**
     * Whether RearActivity's task still exists. Task EXISTENCE, not display
     * placement — am stack list bounds and dumpsys window displays have both been
     * observed lying about which display something is actually on (see the progress
     * ledger's "CRITICAL METHOD FINDING"), but a task either shows up in `am stack list` or it does not. Used
     * by UsagePollService's respawn watchdog (Task 15) to decide whether the
     * always-on panel needs re-placing after being killed by memory pressure, a
     * crash, or a reboot.
     */
    boolean isAlive() = 5;

    /**
     * If RearActivity is compositing on the MAIN display, move it back to displayId.
     *
     * Separate from show() because the strand does not only happen at placement time.
     * Locking the device makes the system hoist this activity onto the primary display
     * over the keyguard — it holds FLAG_SHOW_WHEN_LOCKED, so that is the system doing
     * exactly what it was asked — and no placement attempt is in flight when it
     * happens. Recovery therefore has to be callable on its own, from the watchdog and
     * from the screen-off/on broadcasts.
     *
     * Returns true only when it actually moved something, so the caller can log a real
     * correction without logging every quiet check.
     */
    boolean ensureOnRear(int displayId) = 6;

    /**
     * Put the system camera on [displayId].
     *
     * A NAMED action rather than a general "run this component", deliberately. This
     * interface runs at shell uid, so anything it will launch for the asking is a
     * privilege the app hands to whatever can reach it. One camera is a feature; an
     * arbitrary component is a lever.
     */
    boolean openCamera(int displayId) = 7;

    /**
     * The panel has deliberately let its display go dark. Stand down until told otherwise.
     *
     * Needed because this service cannot tell a panel that is asleep on purpose from one
     * that has been shoved aside, and the two look identical from here: our layer stops
     * compositing and MIUI's rear-screen doze service starts. The keep-alive read that as
     * "rear display taken", re-placed the panel, and re-placing wakes it — so the panel
     * woke itself about ten seconds after every deliberate sleep, and the sleep looked
     * like it simply did not work. Composition cannot answer this question; only the panel
     * knows, so the panel says.
     */
    void setDozing(boolean dozing) = 8;

    /**
     * Hide HyperOS's own rear-screen app from this user — `pm uninstall -k --user 0`, which
     * removes nothing from the system partition. Two NAMED actions for one fixed package,
     * for the same reason openCamera is not "run this component": at shell uid a general
     * uninstall would be a lever, not a feature.
     */
    boolean removeXiaomiRearApp() = 9;

    /**
     * Bring it back — `pm install-existing`, the exact undo of the above — and lift a
     * suspension left by an earlier workaround, so restored means running, not present.
     */
    boolean restoreXiaomiRearApp() = 10;
    /** What the system says about the rear display, for a bug report. See RearUserService. */
    String rearReport() = 11;
    // The rear display's own brightness, HyperOS's system sub_display_screen_brightness
    // (1 to 255); -1 when it cannot be read. Setting it also puts its mode on manual.
    int rearBrightness() = 12;
    void setRearBrightness(int value) = 13;
    // Whether HyperOS sets the rear brightness from the rear light sensor
    // (secure sub_display_screen_brightness_mode 1), and switching it.
    boolean rearAutoBrightness() = 14;
    void setRearAutoBrightness(boolean on) = 15;
    // The control centre's switches, as "bluetooth,data,wifi" each 1 or 0; and switching
    // one ("bluetooth", "data" or "wifi").
    String quickStates() = 16;
    void setQuick(String which, boolean on) = 17;
    // App Portal. Launch [pkg] onto the rear as the guest; false if it could not be placed.
    boolean launchGuest(String pkg, int displayId) = 18;
    // The app focused on the front, moved to the rear as the guest; its package, or "".
    String sendFocusedToBack(int displayId) = 19;
    // What to do with the guest: "panel" — the panel stays in front, the guest alive behind
    // it; "resume" — the guest on top again, the panel behind; "front" — the guest moved to
    // the main display; "close" — the guest force-stopped. The last two bring the panel back.
    void guestAction(String which) = 20;
    // 21: retired (setPortalCallback)
    // The pinned apps for the shell's sheet: "packages" and "labels" (String[], same order)
    // and "icon:<pkg>" (WebP bytes, 96x96). See PortalAppsSnapshot.
    // oneway: the bridge re-sends this from the main thread when the service connects, and
    // nothing waits on the result.
    oneway void setPortalApps(in Bundle apps) = 22;
    // The panel's lock slider, pocket lock or always-on is up (true) or not. While it is, and
    // the panel (not a guest) is on top of the rear, a bottom pull opens no portal sheet.
    // From RearActivity through the app process, like setDozing; oneway, nothing waits on it.
    oneway void setPanelGuarded(boolean guarded) = 23;
    // The app process's ear for what the back decides (IRearEvents). Re-sent by the bridge
    // whenever the service (re)connects; oneway, nothing waits on it.
    oneway void setEvents(IRearEvents events) = 24;
    // The control centre's tiles as the app has them — silent, Bluetooth, mobile data, Wi-Fi —
    // and whether the last three can be switched, for the one the shell holds over a guest.
    // Sent with every change the app sees, and on (re)connect; oneway, nothing waits on it.
    oneway void setQuickStates(in boolean[] states, boolean reachable) = 25;
    /** Whether to keep poking the rear display awake while the panel holds it. See RearUserService. */
    // show(), and [light]: true when somebody ASKED for the panel ("Show on the back", the
    // notification's tap) — the rear is lit whatever it was. False is a recovery (the app's
    // watchdog): lit afterwards only if it was lit before, so a back asleep in a pocket stays
    // asleep. See PlaceLight.
    void place(String payload, int displayId, boolean light) = 26;
    // wake(), unless the other build's panel (release vs dev) is on top of displayId: then
    // nothing, and false. What the app lights on its own goes through this.
    boolean wakeOwn(int displayId) = 27;
    // Whether the App Portal's guest is on top of the rear (on the rear, in front of the panel)
    // as the shell last saw it. A field read, no command: the app asks it at the lock.
    boolean guestOnTop() = 28;
    // Pocket mode: switch the rear display off NOW by sleeping its power group alone
    // (`cmd power sleep --group-id`), the front untouched; and until pocketEnd, wakeOwn
    // lights nothing. False if it was not done (the other build's panel has the back, the
    // command refused). See PocketWatch.
    boolean pocketSleep(int displayId) = 29;
    // Out of the pocket ([light]: switch the back on again) or the pocket called off (the
    // switch turned off, the watch gone): wakeOwn lights the back again either way.
    void pocketEnd(int displayId, boolean light) = 30;

    /**
     * Milliseconds since the rear touchscreen last reported a touch (elapsedRealtime), or -1
     * when it has reported none (or is not being read). Pocket mode's "the hand, not a pocket".
     */
    long msSinceRearTouch() = 31;
    // The notifications the list over a guest shows, whole (ShellNotices.encode), with every
    // change and on (re)connect. [pop]: the key of one that has just arrived, for its banner;
    // empty for none. oneway: nothing waits on it.
    oneway void setNotices(in Bundle list, String pop) = 32;
    // The panel's control centre is down (true) or not. Xiaomi's own window over the rear's
    // bottom 93 px takes a swipe that starts there, so the panel never sees one closing it: the
    // shell, which reads the touchscreen itself, closes it (IRearEvents.onPanelCloseControlCenter).
    oneway void setPanelControlCenter(boolean up) = 33;
    // Something the back's lock holds was asked for on the panel (a held switch tapped): the
    // keypad comes up over it, and the right code leaves it where it was.
    oneway void askBackCode() = 34;
    // The control centre's screenshot tile ([record] false), or its recording tile ([record] true:
    // starts one, or stops the one running). The shell captures the back into this app's files and
    // says so (IRearEvents.onCaptured).
    oneway void capture(boolean record) = 35;
    // Camera mode (CameraWatch in the app): whether the rear is the camera's or a guest's right
    // now — the App Portal's guest on top, the camera this app opened there, or the camera's own
    // rear preview (com.android.camera focused on, or on top of, displayId). The panel then
    // stands aside instead of lighting over it.
    boolean cameraOnRear(int displayId) = 36;
    // A camera is in use on the phone (true) or not any more: while it is, the keep-alive never
    // re-places the panel over whatever has the lit rear — the camera's own preview. oneway.
    oneway void setCameraInUse(boolean inUse) = 37;
    // Camera mode's double tap: switch the rear off NOW (`cmd power sleep --group-id`, the front
    // untouched), with none of the pocket's after-effects. False if it was not done.
    boolean rearSleep(int displayId) = 38;
    // Companion settings: notification access for THIS app's own listener (`cmd notification
    // allow_listener`), which also gets past Android's restricted-settings block for a sideloaded
    // app. [component] must be one of this app's own (package/class); anything else is refused, as
    // a general grant at shell uid would be a lever. True when the listener is allowed afterwards.
    boolean allowListener(String component) = 39;
    // Xiaomi's own rear notifications show their text (secure sub_screen_notification_detail_enable
    // is 1), and switching it. Named for that one key, nothing general; written only on a tap.
    boolean xiaomiNoticeText() = 40;
    boolean setXiaomiNoticeText(boolean on) = 41;
    // The charging animation over the rear (ShellCharge), as the charger goes in at [level]
    // percent ([fast]: a fast charger). Through RearShow: not over a banner, not over Xiaomi's
    // rear camera; compact over a guest of ours. Waits for the rear to light (the app wakes it).
    void showCharge(int level, boolean fast) = 42;
    // Which way is down for its liquid: the app's gravity sensor, x and y, about 30 a second
    // for the animation's five seconds. oneway, nothing waits on it.
    oneway void chargeTilt(float x, float y) = 43;
    // The charger came out: the animation, if it is up, drains and fades over 400 ms.
    oneway void endCharge() = 44;
    // "Play on the back" from the settings (ShellWake / ShellCharge): [style] is a WakeAnimStyle
    // name ("EDGE_GLOW") or a charge style at a battery level ("charge:LIQUID:73"), as
    // WakeStyle.encode writes it. A wake style plays now on a lit back, else on the next wake
    // within 1.5 s (the app wakes it right after). Never over Xiaomi's rear camera.
    void playWake(String style) = 45;
    /**
     * The scene over Xiaomi's rear screen, for the settings' "Play on the back": "wake" plays the
     * wake clip and what follows it, "loop" the loop alone, "stop" takes it away.
     */
    oneway void playScene(String what) = 46;
    /** The battery as the app reads it (the shell cannot): for the scene's percent. */
    oneway void setBattery(int level, boolean charging) = 47;
    /** A PNG of the rear display as it is now, or null: the settings' preview shows Xiaomi's screen under what it adds. */
    byte[] rearShot() = 48;
    /**
     * Whether the back is to stay lit with the scene on it (StayOn.Level's ordinal, the app's
     * posture rules): 0 no, the back sleeps by Xiaomi's own timeout; 1 a dim still picture;
     * 2 dim, moving (lying face down); 3 on, a little dim (in use); 4 full (the camera is open).
     * The shell holds the rear lit, wakes it if it is dark, and keeps the scene up.
     */
    oneway void setStay(int level, String why) = 49;
    /**
     * What is playing on the phone, for the music page that takes the whole back while it plays (rear/MusicFace): a Bundle of
     * active (boolean), title, artist (String), playing (boolean), art (a small JPEG's bytes, or absent).
     */
    oneway void setMedia(in Bundle media) = 50;
    /**
     * Something going on now that has buttons, for a page of its own on the back (rear/CallFace, rear/TimerFace): a timer, a stopwatch, a
     * call. A Bundle of key, pkg, title, text (String), running, down (boolean), base (long, wall clock
     * ms), actions (String[]), icon (PNG bytes, or absent); an empty Bundle or null for nothing.
     */
    oneway void setLive(in Bundle live) = 51;

    // The battery while the charging page is up (ShellCharge.detail): "level" (int), and, where a real source
    // gives it, "fraction" of a percent (float, under 0 for unknown) at "at" (uptime) rising by "rate" a second;
    // "kind": what charger, in words.
    oneway void chargeDetail(in Bundle b) = 52;

    // The light around the phone, in lux, as the app's sensors read it (the shell has none): for the back's
    // brightness (rear/RearLight). [source]: 0 the sensor beside the rear screen, 1 the front one (the rear one is
    // covered, or the phone has none), 2 none at all ([lux] then means nothing).
    oneway void setAmbient(float lux, int source) = 53;
    // How loud what is playing is, 0 to 1, as the app hears it (service/MusicLevel; only with "Follows the music"
    // on and the permission he gave for it): the music page's glows rise and fall with it. Not told: they breathe.
    oneway void setMusicLevel(float level) = 54;

    // A pin of ours has just been handed to Xiaomi's own pin (rear/PinVia): "text" the pinned words, "at" the uptime
    // at which Xiaomi's activity was started. The back lets Xiaomi's arrival through and ends on our notes page.
    oneway void pinVia(in Bundle pin) = 55;

    /**
     * The battery is full with the charger still in (service/ChargeState): charging is not live any more and its
     * page leaves the back as a page does, by its slide; it is shown again by showCharge.
     */
    oneway void chargeFull() = 56;
}

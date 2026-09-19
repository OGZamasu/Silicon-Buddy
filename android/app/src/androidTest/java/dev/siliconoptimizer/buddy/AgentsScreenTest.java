package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The Agents tab on the minified release build, end to end.
 *
 * Driven from the outside, through the accessibility tree, against the APK R8 produced —
 * nothing here names an app class, because R8 renames them and a test that reached in
 * would be testing the unminified app by accident. What it proves is what matters on a
 * real phone: that the new wire types survive R8 and decode (the cards show what the Mac
 * said), that an approval can be answered from the session, that a press through another
 * window is refused there, and that with the app in the background the watcher posts one
 * notification per approval whose buttons — Decline and Review, never Accept — demand the
 * device be unlocked.
 *
 * Java, like {@link FakeMac}, so the test APK needs nothing from the Kotlin standard
 * library that the shrunk app might not have kept.
 */
@RunWith(AndroidJUnit4.class)
public class AgentsScreenTest {

    private static final long WAIT = 20_000;
    private static final String PACKAGE = "dev.siliconoptimizer.buddy";

    private Instrumentation instrumentation;
    private UiDevice device;
    private Context context;
    private FakeMac mac;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        context = instrumentation.getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.getUiAutomation().grantRuntimePermission(
                PACKAGE, "android.permission.POST_NOTIFICATIONS");
        }
        mac = new FakeMac();
        device.wakeUp();
        device.pressHome();
    }

    @After
    public void tearDown() throws Exception {
        mac.close();
        device.pressHome();
    }

    /** Pairs through the link the Mac's QR carries, replacing whatever an earlier test left. */
    private void pair() {
        Intent link = new Intent(Intent.ACTION_VIEW,
            Uri.parse("siliconbuddy://pair?host=127.0.0.1&port=" + mac.port() + "&code=123456"))
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(link);
        UiObject2 confirm = device.wait(Until.findObject(By.text(Pattern.compile("Pair|Replace this Mac…"))), WAIT);
        assertNotNull("the pairing confirmation never appeared", confirm);
        boolean replacing = confirm.getText().startsWith("Replace");
        confirm.click();
        if (replacing) {
            UiObject2 replace = device.wait(Until.findObject(By.textStartsWith("Replace with")), WAIT);
            assertNotNull(replace);
            replace.click();
        }
        assertTrue("the app never asked the stand-in for a token",
            waitFor(() -> mac.saw("POST", "/buddy/pair")));
    }

    private void openAgents() {
        UiObject2 tab = device.wait(Until.findObject(By.text("Agents")), WAIT);
        assertNotNull("a full-control device has an Agents tab", tab);
        tab.click();
    }

    private void openCodex() {
        UiObject2 card = device.wait(Until.findObject(By.text("Codex")), WAIT);
        assertNotNull("the Codex card never appeared", card);
        List<UiObject2> open = device.findObjects(By.text("Open"));
        assertTrue(open.size() >= 1);
        open.get(0).click();
    }

    @Test
    public void theAgentsTabShowsTheMacsSessionsAndAnswersAnApproval() {
        pair();
        openAgents();

        // Both engines, as the Mac listed them, with what is waiting and what never asks.
        assertNotNull(device.wait(Until.findObject(By.text("Codex")), WAIT));
        assertNotNull(device.wait(Until.findObject(By.text("Pi")), WAIT));
        assertNotNull("the pending approval is counted on the card",
            device.wait(Until.findObject(By.text("1 approval waiting")), WAIT));
        assertNotNull("Pi's unattended mode is said on its card",
            device.wait(Until.findObject(By.text("Runs without asking")), WAIT));
        assertNotNull("the tab's badge is said, not only drawn",
            device.wait(Until.findObject(By.desc("Agents, 1 approval waiting")), WAIT));

        openCodex();
        assertNotNull("the transcript is the Mac's",
            device.wait(Until.findObject(By.text("Running the suite now.")), WAIT));
        assertNotNull("reasoning is folded away until asked for",
            device.wait(Until.findObject(By.text("Reasoning")), WAIT));
        assertNotNull(device.wait(Until.findObject(By.text("Codex wants to run a command")), WAIT));

        UiObject2 accept = device.wait(Until.findObject(By.text("Accept")), WAIT);
        assertNotNull(accept);
        accept.click();

        assertTrue("the answer reached the Mac",
            waitFor(() -> "accept".equals(mac.decision)));
        assertTrue(mac.bodyOf("POST", "/agent/sessions/codex/approvals/" + FakeMac.APPROVAL)
            .contains("\"decision\":\"accept\""));
        assertTrue("the card came down, saying so",
            device.wait(Until.hasObject(By.textStartsWith("Accepted on this phone")), WAIT));
        assertTrue("the command's row arrived on the stream",
            device.wait(Until.hasObject(By.text("$ swift test --filter Lisbon")), WAIT));
    }

    @Test
    public void inTheBackgroundAnApprovalRingsWithDeclineAndReview() {
        pair();
        openAgents();
        openCodex();
        assertNotNull(device.wait(Until.findObject(By.text("Codex wants to run a command")), WAIT));

        // Leaving with a turn running in a session this phone opened starts the watcher.
        device.pressHome();
        assertTrue("the watcher did not start", waitFor(this::watcherRunning));

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification approval = waitForApproval("Codex wants to run a command");
        assertNotNull("no approval notification was posted", approval);

        // One per approval, private on the lock screen, with a public version that says
        // only that something is waiting.
        int approvals = 0;
        boolean watching = false;
        for (StatusBarNotification posted : manager.getActiveNotifications()) {
            CharSequence title = posted.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
            if (title != null && title.toString().equals("Codex wants to run a command")) approvals++;
            if (title != null && title.toString().startsWith("Watching Codex")) watching = true;
        }
        assertEquals("one notification per approval", 1, approvals);
        assertTrue("the watcher's own notification is up", watching);
        assertEquals(Notification.VISIBILITY_PRIVATE, approval.visibility);
        assertNotNull(approval.publicVersion);
        String publicText = String.valueOf(approval.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue(publicText.contains("waiting"));
        assertTrue("the lock screen is not told the command", !publicText.contains("swift"));
        // Android shows a private notification's own content on the lock screen unless the
        // owner hid it, so the notification itself never carries the command either.
        String privateText = String.valueOf(approval.extras.getCharSequence(Notification.EXTRA_TEXT))
            + " " + String.valueOf(approval.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        assertTrue(privateText, !privateText.contains("swift"));
        assertTrue(privateText, !privateText.contains("Codex asks before running"));
        assertTrue(privateText, privateText.contains("Screened: asks you"));

        // Nothing on it can allow a command nobody has read: Decline, and Review.
        assertEquals(2, approval.actions.length);
        assertEquals("Decline", approval.actions[0].title.toString());
        assertEquals("Review", approval.actions[1].title.toString());
        if (Build.VERSION.SDK_INT >= 31) {
            for (Notification.Action action : approval.actions) {
                assertTrue(action.title + " must demand the device be unlocked",
                    action.isAuthenticationRequired());
            }
            assertTrue("Decline answers from the shade", approval.actions[0].actionIntent.isBroadcast());
            assertTrue("Review opens the app", approval.actions[1].actionIntent.isActivity());
        }

        // And Decline works from the shade: the phone is unlocked, so the system sends the
        // broadcast at once, and the answer reaches the Mac.
        device.openNotification();
        UiObject2 decline = device.wait(Until.findObject(By.text(Pattern.compile("(?i)decline"))), WAIT);
        if (decline == null) {
            UiObject2 title = device.wait(Until.findObject(By.text("Codex wants to run a command")), WAIT);
            assertNotNull(title);
            title.swipe(androidx.test.uiautomator.Direction.DOWN, 1.0f);
            decline = device.wait(Until.findObject(By.text(Pattern.compile("(?i)decline"))), WAIT);
        }
        assertNotNull("the Decline button is in the shade", decline);
        decline.click();
        assertTrue("the answer from the shade reached the Mac",
            waitFor(() -> "decline".equals(mac.decision)));
        assertTrue(mac.bodyOf("POST", "/agent/sessions/codex/approvals/" + FakeMac.APPROVAL)
            .contains("\"decision\":\"decline\""));

        // The turn ended, so the watcher lets go: the service itself stops — not only its
        // cards — and its own notification goes with it.
        assertTrue("the watcher service is still running", waitFor(() -> !watcherRunning()));
        assertTrue("the watcher's notification is still up", waitFor(() -> {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                if (posted.getId() == WATCH_NOTIFICATION) return false;
            }
            return true;
        }));
        assertTrue("an approval notification is still up", waitFor(() -> {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                Notification notification = posted.getNotification();
                if (notification.actions != null && notification.actions.length == 2) return false;
            }
            return true;
        }));
        device.pressBack();
    }

    /**
     * Review on a notification opens the app on that approval's card — the second of two
     * here, not the one that has waited longest — and Accept is pressed there, under what it
     * allows.
     */
    @Test
    public void reviewOpensThatCardInTheAppWhereItIsAccepted() throws Exception {
        twoApprovals();
        pair();
        openAgents();
        openCodex();
        assertNotNull(device.wait(Until.findObject(By.text("Codex wants to run a command")), WAIT));

        device.pressHome();
        assertTrue("the watcher did not start", waitFor(this::watcherRunning));
        assertNotNull(waitForApproval("Codex wants to run a command"));
        Notification files = waitForApproval("Codex wants to change files");
        assertNotNull("each approval rings", files);
        assertEquals("Review", files.actions[files.actions.length - 1].title.toString());

        // What the shade's Review sends: the app's own intent, after the unlock the system
        // asks for on a locked phone. Sent here directly; this emulator is not locked.
        files.actions[files.actions.length - 1].actionIntent.send();

        assertNotNull("the app opened on the card Review was pressed for",
            device.wait(Until.findObject(By.text("Codex wants to change files")), WAIT));
        assertNotNull("with what it wants, in full",
            device.wait(Until.findObject(By.text("Sources/Lisbon/Itinerary.swift")), WAIT));
        assertTrue("the other card waits behind it",
            device.wait(Until.hasObject(By.text("1 more")), WAIT));
        assertTrue("the app is in front, so the watcher lets go", waitFor(() -> !watcherRunning()));

        UiObject2 accept = device.wait(Until.findObject(By.text("Accept")), WAIT);
        assertNotNull(accept);
        accept.click();
        assertTrue("accepted in the app, for the card on screen",
            waitFor(() -> "accept".equals(mac.secondDecision)));
        assertTrue("and only that one", mac.decision == null);
        assertNotNull("the next card comes to the front",
            device.wait(Until.findObject(By.text("Codex wants to run a command")), WAIT));
    }

    /**
     * Tapjacking. A press that came through another app's window at the point pressed is
     * refused, and says why; the answer given next without a touch — switch access, a
     * keyboard, TalkBack — is not held to it; and a window that merely overlaps some other
     * part of the screen (a video call in a corner) refuses nothing. The events are handed
     * to the window the way the system hands them, flags and all: no other app can be made
     * to draw over this one from here.
     */
    @Test
    public void aPressThroughAnotherWindowIsRefusedAndNothingElseIs() throws Exception {
        twoApprovals();
        pair();
        openAgents();
        openCodex();
        assertNotNull(device.wait(Until.findObject(By.text("Codex wants to run a command")), WAIT));
        UiObject2 accept = device.wait(Until.findObject(By.text("Accept")), WAIT);
        assertNotNull(accept);
        assertTrue("while a card waits, other apps' overlays are hidden (Android 12+)",
            Build.VERSION.SDK_INT < 31 || waitFor(this::hidingOverlays));

        press(accept, MotionEvent.FLAG_WINDOW_IS_OBSCURED);
        assertNotNull("the card says why nothing happened",
            device.wait(Until.findObject(By.textStartsWith("Something was drawn over Silicon Buddy")), WAIT));
        SystemClock.sleep(1_000);
        assertTrue("nothing was sent", !mac.saw("POST", "/agent/sessions/codex/approvals/"));

        clickWithoutTouching("Accept");
        assertTrue("the next answer, given without a touch, is not refused",
            waitFor(() -> "accept".equals(mac.decision)));

        assertNotNull(device.wait(Until.findObject(By.text("Codex wants to change files")), WAIT));
        UiObject2 next = device.wait(Until.findObject(By.text("Accept")), WAIT);
        assertNotNull(next);
        press(next, MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED);
        assertTrue("a window overlapping another part of the screen refuses nothing",
            waitFor(() -> "accept".equals(mac.secondDecision)));
        assertTrue("with no card waiting, overlays are drawn again",
            Build.VERSION.SDK_INT < 31 || waitFor(() -> !hidingOverlays()));
    }

    /**
     * Whether the app's window asks for other apps' overlays to be hidden, as the window
     * manager reports it: `setHideOverlayWindows` sets a private flag that only shows there.
     */
    private boolean hidingOverlays() {
        String dump;
        try {
            dump = device.executeShellCommand("dumpsys window windows");
        } catch (java.io.IOException failed) {
            return false;
        }
        int at = dump.indexOf(PACKAGE + "/" + PACKAGE + ".MainActivity}:");
        if (at < 0) return false;
        int end = dump.indexOf("mBaseLayer", at);
        String window = dump.substring(at, end < 0 ? Math.min(dump.length(), at + 2000) : end);
        return window.contains("HIDE_NON_SYSTEM_OVERLAY_WINDOWS");
    }

    /** Swaps in a stand-in Mac whose Codex holds two approvals. */
    private void twoApprovals() throws Exception {
        mac.close();
        mac = new FakeMac(true);
    }

    /** The approval notification with [title], once it is posted, or null. */
    private Notification waitForApproval(String title) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification[] found = new Notification[1];
        waitFor(() -> {
            for (StatusBarNotification posted : manager.getActiveNotifications()) {
                Notification notification = posted.getNotification();
                CharSequence heading = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
                if (heading != null && heading.toString().equals(title) && notification.actions != null) {
                    found[0] = notification;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    /** The activity in front, from the lifecycle the runner records. */
    private Activity resumed() {
        Activity[] found = new Activity[1];
        instrumentation.runOnMainSync(() -> {
            Collection<Activity> activities =
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);
            if (!activities.isEmpty()) found[0] = activities.iterator().next();
        });
        return found[0];
    }

    /**
     * A tap on [button], handed to its window with [flags] set — what the input system does
     * when the touch passed through another app's window.
     */
    private void press(UiObject2 button, int flags) {
        Activity activity = resumed();
        assertNotNull("the app is in front", activity);
        Rect bounds = button.getVisibleBounds();
        instrumentation.runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            int[] origin = new int[2];
            decor.getLocationOnScreen(origin);
            float x = bounds.exactCenterX() - origin[0];
            float y = bounds.exactCenterY() - origin[1];
            long now = SystemClock.uptimeMillis();
            MotionEvent down = touch(now, now, MotionEvent.ACTION_DOWN, x, y, flags);
            MotionEvent up = touch(now, now + 60, MotionEvent.ACTION_UP, x, y, flags);
            decor.dispatchTouchEvent(down);
            decor.dispatchTouchEvent(up);
            down.recycle();
            up.recycle();
        });
        instrumentation.waitForIdleSync();
    }

    private static MotionEvent touch(long downTime, long eventTime, int action, float x, float y, int flags) {
        MotionEvent.PointerProperties[] properties = { new MotionEvent.PointerProperties() };
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = { new MotionEvent.PointerCoords() };
        coords[0].x = x;
        coords[0].y = y;
        coords[0].pressure = 1f;
        coords[0].size = 1f;
        return MotionEvent.obtain(downTime, eventTime, action, 1, properties, coords, 0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags);
    }

    /**
     * The button with [label], pressed through accessibility — the way switch access and
     * TalkBack do. The tree is walked by hand: Compose's node provider does not answer a
     * search by text. The same UiAutomation flags as UiDevice's, so its connection is shared
     * rather than replaced.
     */
    private void clickWithoutTouching(String label) {
        int flags = androidx.test.uiautomator.Configurator.getInstance().getUiAutomationFlags();
        AccessibilityNodeInfo root = instrumentation.getUiAutomation(flags).getRootInActiveWindow();
        assertNotNull(root);
        AccessibilityNodeInfo target = withText(root, label);
        while (target != null && !target.isClickable()) target = target.getParent();
        if (target == null) throw new AssertionError("no clickable " + label + " in the window");
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }

    private static AccessibilityNodeInfo withText(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        CharSequence text = node.getText();
        if (text != null && label.contentEquals(text)) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo found = withText(node.getChild(index), label);
            if (found != null) return found;
        }
        return null;
    }

    /** The watcher's foreground notification, as the app numbers it. */
    private static final int WATCH_NOTIFICATION = 4201;

    /**
     * Whether this app's agent watcher is running. On Android 8 and later the list holds only
     * the caller's own services, which is exactly what is wanted; the class keeps its name
     * through R8 because the manifest names it.
     */
    @SuppressWarnings("deprecation")
    private boolean watcherRunning() {
        android.app.ActivityManager activities = context.getSystemService(android.app.ActivityManager.class);
        for (android.app.ActivityManager.RunningServiceInfo service : activities.getRunningServices(Integer.MAX_VALUE)) {
            if (service.service.getClassName().equals(PACKAGE + ".agents.AgentWatchService") && service.started) {
                return true;
            }
        }
        return false;
    }

    private interface Condition {
        boolean holds();
    }

    private static boolean waitFor(Condition condition) {
        long deadline = System.currentTimeMillis() + WAIT;
        while (System.currentTimeMillis() < deadline) {
            if (condition.holds()) return true;
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.holds();
    }
}

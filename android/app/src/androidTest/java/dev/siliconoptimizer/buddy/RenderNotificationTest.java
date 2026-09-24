package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.json.JSONObject;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What an image render is worth saying, against the stand-in Mac in the format the real one
 * sends today: each render under an id of its own (`image-<job>`), and a stream that opens told
 * how the last renders ended. On the minified release build.
 *
 * Java, like {@link FakeMac}, for the reason given there.
 */
@RunWith(AndroidJUnit4.class)
public class RenderNotificationTest {

    private static final long WAIT = 20_000;
    private static final String PACKAGE = "dev.siliconoptimizer.buddy";

    private Instrumentation instrumentation;
    private UiDevice device;
    private Context context;
    private StandInMac mac;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        context = instrumentation.getTargetContext();
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.getUiAutomation().grantRuntimePermission(PACKAGE, "android.permission.POST_NOTIFICATIONS");
        }
        mac = new StandInMac();
        mac.check();
        mac.unreachable(0);
        mac.renders("{\"failNextRequest\":null}");
        context.getSystemService(NotificationManager.class).cancelAll();
        device.wakeUp();
    }

    @After
    public void tearDown() throws Exception {
        mac.renders("{\"failNextRequest\":null}");
        device.pressHome();
    }

    /**
     * A render made on the Mac while the app was away. The Mac tells the app how it ended when
     * the app opens `/events` again — and that ending, the first this phone hears of the render,
     * happened while nobody was looking. It is on the Queue screen; it is not a notification.
     */
    @Test
    public void aRenderThatEndedWhileTheAppWasAwayDoesNotRingWhenItComesBack() throws Exception {
        int before = opened();
        pair();
        assertTrue("the app never opened /events", waitFor(() -> opened() > before));

        // Away: the app closes its stream as it leaves the screen, and hears nothing of what
        // happens on the Mac until it opens it again.
        device.pressHome();
        SystemClock.sleep(2_000);
        int away = opened();
        mac.renders("{\"start\":\"image\",\"seconds\":1,\"title\":\"Made while you were away\"}");
        assertTrue("the render never ended on the stand-in", waitFor(() -> {
            JSONObject state = mac.renders("{}");
            return !state.getJSONObject("running").has("image") && state.getJSONObject("endings").getJSONArray("image").length() > 0;
        }));

        bringToFront();
        assertTrue("the app did not open /events again", waitFor(() -> opened() > away));
        tap("Create");
        tap("Queue");
        assertTrue("the ending never reached the phone", device.wait(Until.hasObject(By.text("Made while you were away")), WAIT));
        SystemClock.sleep(3_000);
        assertEquals("a render that ended while the app was away rang when it came back",
            0, posted("Your image is ready"));
    }

    /**
     * This phone's own request, failing at once: the stream's first word about the render is its
     * failure. The service that held the request says so — once — and the stream's copy of the
     * same failure is left to it rather than said a second time.
     */
    @Test
    public void thePhonesOwnRequestThatFailsAtOnceIsSaidOnce() throws Exception {
        int before = opened();
        pair();
        assertTrue("the app never opened /events", waitFor(() -> opened() > before));
        mac.renders("{\"failNextRequest\":\"No image model is installed on this Mac.\"}");

        tap("Create");
        tap("Image");
        UiObject2 prompt = device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT);
        assertNotNull("no prompt field", prompt);
        prompt.setText("A tram at dawn");
        UiObject2 generate = device.wait(Until.findObject(By.text("Generate").enabled(true)), 5_000);
        for (int swipe = 0; generate == null && swipe < 4; swipe++) {
            UiObject2 list = device.findObject(By.scrollable(true));
            if (list != null) list.scroll(Direction.DOWN, 0.8f);
            generate = device.wait(Until.findObject(By.text("Generate").enabled(true)), 3_000);
        }
        assertNotNull("no Generate to press", generate);
        generate.click();

        assertTrue("the failure was never said", waitFor(() -> posted("That image failed") >= 1));
        SystemClock.sleep(4_000);
        assertEquals("said once, by the service that held the request", 1, posted("That image failed"));
        assertEquals(0, posted("Your image is ready"));
    }

    // MARK: - Helpers

    /** How many of this app's notifications carry this title right now. */
    private int posted(String title) {
        int count = 0;
        for (StatusBarNotification each : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
            CharSequence heading = each.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
            if (heading != null && heading.toString().equals(title)) count++;
        }
        return count;
    }

    /** How many `/events` streams the stand-in has had opened, ever. */
    private int opened() throws Exception {
        return mac.renders("{}").getInt("streamsOpened");
    }

    private void bringToFront() {
        context.startActivity(new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(PACKAGE, "dev.siliconoptimizer.buddy.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        assertTrue("the app came to the front", device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), WAIT));
        device.waitForIdle();
    }

    /** Pairs with the stand-in through the link its QR carries, replacing any earlier pairing. */
    private void pair() throws Exception {
        String code = mac.invitation();
        Intent link = new Intent(Intent.ACTION_VIEW,
            Uri.parse("siliconbuddy://pair?host=" + mac.host + "&port=" + mac.port + "&code=" + code))
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(link);
        Boolean replacing = null;
        for (int attempt = 0; attempt < 3 && replacing == null; attempt++) {
            UiObject2 confirm = device.wait(Until.findObject(By.text(Pattern.compile("Pair|Replace this Mac…"))), WAIT);
            assertNotNull("the pairing confirmation never appeared", confirm);
            try {
                replacing = confirm.getText().startsWith("Replace");
                confirm.click();
            } catch (androidx.test.uiautomator.StaleObjectException redrawn) {
                replacing = null;
            }
        }
        assertNotNull("the pairing confirmation kept moving", replacing);
        if (replacing) {
            UiObject2 replace = device.wait(Until.findObject(By.textStartsWith("Replace with")), WAIT);
            assertNotNull(replace);
            replace.click();
        }
        assertTrue("the app never paired with the stand-in", waitFor(() -> {
            List<String> all = mac.requests();
            for (String line : all.subList(Math.max(0, all.size() - 150), all.size())) {
                if (line.startsWith("POST /buddy/pair")) return true;
            }
            return false;
        }));
        device.waitForIdle();
    }

    private void tap(String text) {
        UiObject2 control = device.wait(Until.findObject(By.text(text)), WAIT);
        assertNotNull("no " + text + " to press", control);
        control.click();
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static boolean waitFor(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT;
        while (System.currentTimeMillis() < deadline) {
            if (condition.holds()) return true;
            Thread.sleep(250);
        }
        return condition.holds();
    }
}

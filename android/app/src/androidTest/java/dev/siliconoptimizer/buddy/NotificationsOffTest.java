package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What a phone that has never granted notifications is told — the owner's own case.
 *
 * They ran an evening of renders and agent approvals and saw nothing: Android had shown the
 * permission dialog twice, been dismissed twice, and after that `launch` returns denied
 * without showing anything at all. Nothing on screen said so.
 *
 * Its own class, and run in a second pass on a fresh install: every `connectedAndroidTest`
 * run installs the app once for the whole run, and another class grants notifications in its
 * setUp — so in the main pass this state cannot be reached. `scripts/ci-android.sh
 * --connected` runs the main pass with this class excluded and then this class alone.
 */
@RunWith(AndroidJUnit4.class)
public class NotificationsOffTest {

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
        // Nothing is granted here. That is the whole point.
        mac = new FakeMac();
        device.wakeUp();
        device.pressHome();
    }

    @After
    public void tearDown() throws Exception {
        mac.close();
        device.pressHome();
    }

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
        assertTrue("the app never asked the fake Mac for a token", waitFor(() -> mac.saw("POST", "/buddy/pair")));
    }

    private boolean waitFor(java.util.function.BooleanSupplier check) {
        long deadline = System.currentTimeMillis() + WAIT;
        while (System.currentTimeMillis() < deadline) {
            if (check.getAsBoolean()) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return check.getAsBoolean();
    }

    @Test
    public void theAgentsTabAndTheCreateQueueSayNotificationsAreOff() {
        pair();

        UiObject2 agents = device.wait(Until.findObject(By.text("Agents")), WAIT);
        assertNotNull("a full-control device has an Agents tab", agents);
        agents.click();
        assertNotNull("an agent waiting for you would wait in silence, and this says so",
            device.wait(Until.findObject(By.text("Notifications are off, so an agent waiting for you won't reach you.")), WAIT));
        assertNotNull("with the button that fixes it", device.findObject(By.text("Allow")));

        UiObject2 create = device.wait(Until.findObject(By.text("Create")), WAIT);
        assertNotNull(create);
        create.click();
        UiObject2 queue = device.wait(Until.findObject(By.text("Queue")), WAIT);
        assertNotNull(queue);
        queue.click();
        assertNotNull("and the queue, where a render finishing is the news",
            device.wait(Until.findObject(By.text("Notifications are off, so a render finishing won't reach you.")), WAIT));

        // The forms are not the place for it: they ask for the permission themselves when
        // something is actually started.
        UiObject2 video = device.wait(Until.findObject(By.text("Video")), WAIT);
        assertNotNull(video);
        video.click();
        assertNull("not on every screen — only where it matters",
            device.wait(Until.findObject(By.textStartsWith("Notifications are off")), 2_000));
    }
}

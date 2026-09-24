package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
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
 * Forget this Mac, as the launcher sees it, on the minified release build.
 *
 * Long-press the app's icon and the launcher offers "Ask <the model the Mac has loaded>",
 * written whenever the app hears the Mac's status. Forget clears what the app knows about
 * that Mac — and used to leave the shortcut naming its model on the launcher, because only a
 * status from a Mac ever rewrote it, and a phone that has forgotten its Mac hears none.
 *
 * Java, like {@link FakeMac}, for the reason given there.
 */
@RunWith(AndroidJUnit4.class)
public class ForgetMacTest {

    private static final long WAIT = 20_000;
    private static final String PACKAGE = "dev.siliconoptimizer.buddy";

    private UiDevice device;
    private Context context;
    private FakeMac mac;

    @Before
    public void setUp() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mac = new FakeMac();
        mac.status = FakeMac.LOADED;
        device.wakeUp();
        device.pressHome();
    }

    @After
    public void tearDown() throws Exception {
        mac.close();
        device.pressHome();
    }

    @Test
    public void forgetTakesTheForgottenMacsModelOffTheLauncher() throws Exception {
        pair();
        assertTrue("the app never opened the Mac's event stream", waitFor(() -> mac.saw("GET", "/events")));
        mac.publishStatus(FakeMac.LOADED);
        assertTrue("the Mac's model never reached the launcher",
            waitFor(() -> "Test Model".equals(loadedShortcut())));

        UiObject2 settings = device.wait(Until.findObject(By.desc("Settings")), WAIT);
        if (settings == null) settings = device.wait(Until.findObject(By.text("Settings")), WAIT);
        assertNotNull("no way to Settings", settings);
        settings.click();
        UiObject2 forget = device.wait(Until.findObject(By.text("Forget this Mac")), WAIT);
        assertNotNull("Settings has no Forget this Mac", forget);
        forget.click();
        assertTrue("the Mac was not forgotten", device.wait(Until.hasObject(By.text("Pair with a Mac")), WAIT));

        assertTrue("after Forget the launcher still offers the forgotten Mac's model: " + loadedShortcut(),
            waitFor(() -> loadedShortcut() == null));
        // And it stays gone: nothing writes the old Mac's model back a moment later.
        Thread.sleep(2_000);
        assertNull(loadedShortcut());
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

    /** The launcher's "Ask <model>" shortcut's label, or null when there is none. */
    private String loadedShortcut() {
        for (ShortcutInfo shortcut : context.getSystemService(ShortcutManager.class).getDynamicShortcuts()) {
            if ("loaded".equals(shortcut.getId())) return String.valueOf(shortcut.getShortLabel());
        }
        return null;
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

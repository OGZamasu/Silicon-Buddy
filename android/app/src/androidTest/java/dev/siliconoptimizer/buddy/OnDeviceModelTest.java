package dev.siliconoptimizer.buddy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.service.notification.StatusBarNotification;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Direction;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The phone's own model, on the minified release build, with models served by the stand-in
 * Mac through the real `/ondevice` routes.
 *
 * The engine is reached through `OnDeviceProbe`, kept by name, because R8 renames
 * everything else — and that is also the test that the JNI entry points survived: every
 * call here goes through `LlamaNative` in the APK R8 produced. The download and the
 * fallback are driven from the outside, through the screen.
 *
 * Java, like the other instrumented tests, so nothing here needs a Kotlin class the shrunk
 * app might not have kept.
 */
@RunWith(AndroidJUnit4.class)
public class OnDeviceModelTest {

    private static final long WAIT = 20_000;
    private static final String PACKAGE = "dev.siliconoptimizer.buddy";

    /** ggml-org/test-model-stories260K@479896ec924af6d40fd419ab8f4d1eb2101de00d, stories260K-f32.gguf. */
    static final String STORIES = "stories260k-f32";
    static final String STORIES_SHA256 = "270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d";

    /** bartowski/SmolLM2-135M-Instruct-GGUF@09816acd5d99df7be770d85ea30822623dab342c, SmolLM2-135M-Instruct-Q8_0.gguf. */
    static final String SMOL = "smollm2-135m-q8_0";
    static final String SMOL_SHA256 = "5a1395716f7913741cc51d98581b9b1228d80987a9f7d3664106742eb06bba83";

    /** bartowski/Qwen_Qwen3.5-2B-GGUF@7d26695454df6de5fbcce2e58681e62dae06ce43, the owner's default. */
    static final String QWEN = "qwen3.5-2b-q4_0";

    /**
     * What upstream's own `llama-simple`, built from the same pinned llama.cpp (b11053) with
     * the same flags, wrote for "Once upon a time" — 32 greedy tokens — on this emulator.
     */
    static final String STORIES_32 =
        ", there was a little girl named Lily. She loved to play outside in the park. One day, she saw";

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
        device.wakeUp();
    }

    @After
    public void tearDown() throws Exception {
        mac.unreachable(0);
        probe("unload", context);
    }

    // MARK: - The probe, by name

    private Object probe(String name, Object... arguments) throws Exception {
        Class<?> probe = Class.forName("dev.siliconoptimizer.buddy.ondevice.OnDeviceProbe", false, context.getClassLoader());
        for (Method method : probe.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == arguments.length) {
                return method.invoke(null, arguments);
            }
        }
        throw new AssertionError("OnDeviceProbe." + name + " did not survive R8");
    }

    private String state() throws Exception {
        return (String) probe("state", context);
    }

    /** The model is on the phone, fetched through the app's own downloader from the stand-in. */
    private void install(String id) throws Exception {
        if (((String) probe("installed", context)).contains(id)) return;
        pair();
        assertEquals("", probe("download", context, id));
    }

    private JSONArray events() throws Exception {
        return new JSONArray((String) probe("events"));
    }

    private JSONObject lastEvent(String type) throws Exception {
        JSONArray all = events();
        for (int i = all.length() - 1; i >= 0; i--) {
            if (all.getJSONObject(i).getString("type").equals(type)) return all.getJSONObject(i);
        }
        return null;
    }

    private int count(String type) throws Exception {
        JSONArray all = events();
        int n = 0;
        for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getString("type").equals(type)) n++;
        return n;
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    private boolean waitFor(long millis, Check check) throws Exception {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (check.holds()) return true;
            Thread.sleep(50);
        }
        return check.holds();
    }

    /**
     * Whether the stand-in has just seen a request starting with [prefix].
     *
     * Its full log is the last two thousand lines, and a test that polls it while it waits
     * rolls that over in seconds — so neither an index nor a count taken earlier means
     * anything by the time it is compared. Only the newest lines are read. Where a test
     * needs more than "just now", the `/ondevice` log is the one to read: it is cleared on
     * demand and only the phone writes to it.
     */
    private boolean justSaw(String prefix) throws Exception {
        List<String> all = mac.requests();
        for (String line : all.subList(Math.max(0, all.size() - 150), all.size())) {
            if (line.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Whether the phone has asked this of the `/ondevice` routes since they were cleared. */
    private boolean asked(String prefix) throws Exception {
        for (String line : mac.ondeviceRequests()) if (line.startsWith(prefix)) return true;
        return false;
    }

    private String shell(String command) throws Exception {
        ParcelFileDescriptor output = instrumentation.getUiAutomation().executeShellCommand(command);
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(output.getFileDescriptor())))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString();
    }

    /**
     * The screen and its accessibility tree, left in the emulator's scratch folder when a
     * screen test fails — the uninstall after the run takes the app's own folders with it.
     */
    private void evidence(String name) throws Exception {
        shell("screencap -p /data/local/tmp/m5-" + name + ".png");
        shell("uiautomator dump /data/local/tmp/m5-" + name + ".xml");
        shell("sh -c 'dumpsys activity processes " + PACKAGE + " > /data/local/tmp/m5-" + name + "-proc.txt'");
    }

    /** The first [text] button below [label] on screen: the one in that model's row. */
    private UiObject2 buttonUnder(String label, String text) {
        UiObject2 row = device.findObject(By.text(label));
        if (row == null) return null;
        UiObject2 nearest = null;
        for (UiObject2 button : device.findObjects(By.text(text))) {
            int top = button.getVisibleBounds().top;
            if (top > row.getVisibleBounds().top && (nearest == null || top < nearest.getVisibleBounds().top)) nearest = button;
        }
        return nearest;
    }

    /** What a shell command wrote to stderr — where `am` puts its refusals. */
    private String shellErrors(String command) throws Exception {
        ParcelFileDescriptor[] pipes = instrumentation.getUiAutomation().executeShellCommandRwe(command);
        pipes[0].close();
        pipes[1].close();
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(pipes[2].getFileDescriptor())))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString();
    }

    /**
     * The app on the screen. An answer is only written while it is — the rule these tests
     * check elsewhere — so a test that generates starts here, whatever the last one left.
     */
    private void bringToFront() throws Exception {
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
        UiObject2 confirm = device.wait(Until.findObject(By.text(Pattern.compile("Pair|Replace this Mac…"))), WAIT);
        assertNotNull("the pairing confirmation never appeared", confirm);
        boolean replacing = confirm.getText().startsWith("Replace");
        confirm.click();
        if (replacing) {
            UiObject2 replace = device.wait(Until.findObject(By.textStartsWith("Replace with")), WAIT);
            assertNotNull(replace);
            replace.click();
        }
        assertTrue("the app never paired with the stand-in",
            waitFor(WAIT, () -> justSaw("POST /buddy/pair")));
        device.waitForIdle();
    }

    // MARK: - llama.cpp itself, through the JNI R8 kept

    @Test
    public void stories260KWritesWhatLlamaSimpleWrote_andTheSameEveryTime() throws Exception {
        install(STORIES);
        assertEquals("the phone checked the bytes itself", STORIES_SHA256, probe("sha256", context, STORIES));
        bringToFront();

        JSONObject first = new JSONObject((String) probe("complete", context, STORIES, "Once upon a time", 32, true));
        assertFalse(first.optString("failure"), first.has("failure"));
        assertEquals(32, first.getInt("generated"));
        assertEquals("the pinned build's own llama-simple, token for token", STORIES_32, first.getString("text"));

        probe("unload", context);
        JSONObject again = new JSONObject((String) probe("complete", context, STORIES, "Once upon a time", 32, true));
        assertEquals("greedy is deterministic across a reload", first.getString("text"), again.getString("text"));
    }

    @Test
    public void theCpuVariantIsOneThisCpuCanRun() throws Exception {
        String backends = (String) probe("backends", context);
        Matcher variant = Pattern.compile("libggml-cpu-android_armv([0-9.]+)_(\\d)\\.so").matcher(backends);
        assertTrue("llama.cpp loaded a CPU variant: " + backends, variant.find());
        String features = new String(Files.readAllBytes(Paths.get("/proc/cpuinfo")));
        String version = variant.group(1);
        boolean needsI8mm = version.equals("8.6") || version.startsWith("9");
        if (needsI8mm) {
            assertTrue("armv" + version + " needs i8mm, which this CPU says it has", features.contains(" i8mm"));
        }
        if (features.contains(" asimddp")) {
            assertFalse("a CPU with dot products gets more than the baseline", version.equals("8.0"));
        }
        assertFalse("no GPU backend on this build", backends.contains("OpenCL"));
    }

    @Test
    public void smolLMStreamsToAFinishedEventWithMetrics() throws Exception {
        install(SMOL);
        assertEquals(SMOL_SHA256, probe("sha256", context, SMOL));
        bringToFront();
        assertEquals("", probe("startChat", context, SMOL, "Say hello in five words.", 48));
        assertTrue("it finished", waitFor(90_000, () -> lastEvent("closed") != null));

        JSONObject finished = lastEvent("finished");
        assertNotNull("a finished event, not a failure: " + events(), finished);
        assertTrue("tokens came before it", count("token") >= 1);
        JSONArray all = events();
        int finishedAt = -1;
        for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getString("type").equals("finished")) finishedAt = i;
        for (int i = finishedAt + 1; i < all.length(); i++) {
            assertEquals("nothing after finished but the close", "closed", all.getJSONObject(i).getString("type"));
        }
        assertTrue(finished.getInt("generated") > 0);
        assertTrue(finished.getInt("promptTokens") > 0);
        assertTrue(finished.getDouble("tokensPerSecond") > 0);
        assertTrue("the time to the first word is measured", finished.getDouble("firstToken") > 0);
        assertTrue(state().startsWith("Loaded:" + SMOL));
    }

    @Test
    public void smolLMStopsWithin500Milliseconds() throws Exception {
        install(SMOL);
        bringToFront();
        assertEquals("", probe("startChat", context, SMOL, "Write a very long story about a lighthouse keeper and the sea.", 2000));
        assertTrue("it started writing", waitFor(90_000, () -> count("token") >= 8));
        long cancelledAt = (Long) probe("cancel", context);
        assertTrue(waitFor(10_000, () -> lastEvent("closed") != null));

        JSONArray all = events();
        long stoppedAt = -1;
        for (int i = 0; i < all.length(); i++) {
            JSONObject event = all.getJSONObject(i);
            if (event.getString("type").equals("failed") || event.getString("type").equals("finished")) {
                stoppedAt = event.getLong("at");
            }
            if (event.getString("type").equals("token")) {
                assertTrue("no token more than 500 ms after the cancel", event.getLong("at") <= cancelledAt + 500);
            }
        }
        assertTrue("it said it stopped", stoppedAt >= 0);
        assertTrue("stopped " + (stoppedAt - cancelledAt) + " ms after the cancel", stoppedAt - cancelledAt <= 500);
        assertEquals("Stopped.", lastEvent("failed").getString("message"));
    }

    @Test
    public void unloadingGivesTheMemoryBack() throws Exception {
        install(SMOL);
        bringToFront();
        probe("backends", context);
        probe("unload", context);
        Runtime.getRuntime().gc();
        long before = (Long) probe("residentBytes");
        assertEquals("", probe("startChat", context, SMOL, "Name three colours.", 32));
        assertTrue(waitFor(90_000, () -> lastEvent("closed") != null));
        assertNotNull("it answered, so the weights were read: " + events(), lastEvent("finished"));
        long loaded = (Long) probe("residentBytes");
        probe("unload", context);
        assertEquals("Unloaded", state());
        long after = (Long) probe("residentBytes");
        long mb = 1024 * 1024;
        assertTrue("loading SmolLM2 took memory: " + (loaded - before) / mb + " MB", loaded - before > 100 * mb);
        assertTrue("and unloading gave it back: " + (after - before) / mb + " MB left", after - before < 40 * mb);
    }

    // MARK: - The chat template

    @Test
    public void theChatTemplateIsTheModelsOwn() throws Exception {
        install(SMOL);
        String prompt = (String) probe("renderPrompt", context, SMOL, "Hello there", "recommended");
        assertTrue("SmolLM2's own ChatML, with the question in it: " + prompt,
            prompt.contains("<|im_start|>user\nHello there<|im_end|>"));
        assertTrue("and the model's turn next: " + prompt, prompt.endsWith("<|im_start|>assistant\n"));
    }

    /**
     * Qwen3.5 2B, the owner's default, answers with thinking off: its template closes an
     * empty thinking block before the answer, and opens one only when asked to think. Read
     * from the real file's vocabulary — the weights do not fit this emulator — when the
     * stand-in serves it (`QWEN=1 standin.sh start`).
     */
    @Test
    public void qwenAnswersWithThinkingOff() throws Exception {
        org.junit.Assume.assumeTrue("the stand-in serves Qwen3.5 2B only with QWEN=1", mac.offers(QWEN));
        try {
            install(QWEN);
            String off = (String) probe("renderPrompt", context, QWEN, "What is a tailnet?", "recommended");
            assertTrue("thinking off, as the Mac recommends: " + off,
                off.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"));
            String on = (String) probe("renderPrompt", context, QWEN, "What is a tailnet?", "on");
            assertTrue("and the switch reaches the template: " + on, on.endsWith("<|im_start|>assistant\n<think>\n"));
        } finally {
            // Not left behind to be offered by the tests after this one.
            probe("delete", context, QWEN);
        }
    }

    // MARK: - The guards

    @Test
    public void pressingHomeStopsTheAnswerAndTheModelGoesAfterTheGrace() throws Exception {
        install(SMOL);
        pair(); // the app in front
        assertEquals("", probe("startChat", context, SMOL, "Write a very long story about a lighthouse keeper and the sea.", 2000));
        assertTrue(waitFor(90_000, () -> count("token") >= 4));

        device.pressHome();
        assertTrue("the answer stopped when the app left the screen", waitFor(5_000, () -> lastEvent("closed") != null));
        assertEquals("Stopped when you left the app.", lastEvent("failed").getString("message"));
        assertTrue("still loaded during the grace", state().startsWith("Loaded:"));

        assertTrue("unloaded after 30 s in the background", waitFor(45_000, () -> state().equals("Unloaded")));
        assertEquals("background", probe("lastUnloadReason", context));
    }

    @Test
    public void aBackgroundTrimUnloadsAtOnce() throws Exception {
        install(SMOL);
        bringToFront();
        assertEquals("", probe("startChat", context, SMOL, "Name three colours.", 16));
        assertTrue(waitFor(90_000, () -> lastEvent("closed") != null));
        assertNotNull(lastEvent("finished"));
        assertTrue(state().startsWith("Loaded:"));

        // Android only sends a background trim to a process in the background; the 30 s grace
        // starts there too, so "at once" means well inside it.
        device.pressHome();
        String refusal = shellErrors("am send-trim-memory " + PACKAGE + " BACKGROUND");
        if (refusal.contains("Unable to set a background trim level on a foreground process")) {
            // Android keeps a process with an instrumentation attached in a foreground state
            // (OomAdjuster: "instrumentation"), so `am send-trim-memory` refuses every
            // background level here, whatever the app is doing. The same level goes through
            // the same door the system uses — the Application's component callbacks — and
            // the real command is exercised on an uninstrumented install (docs/PLAN.md, M5).
            instrumentation.runOnMainSync(() ->
                ((android.app.Application) context.getApplicationContext())
                    .onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND));
        } else {
            assertEquals("the trim was delivered", "", refusal.trim());
        }
        assertTrue("Android asked for memory back, and got it", waitFor(5_000, () -> state().equals("Unloaded")));
        assertEquals("trim-memory", probe("lastUnloadReason", context));
    }

    // MARK: - Through the screen

    @Test
    public void aModelComesFromTheMacThroughSettings_afterConsent() throws Exception {
        probe("delete", context, STORIES);
        // …and a clean `/ondevice` request log, which is what this test reads back.
        mac.ondevice("{\"state\":{\"" + STORIES + "\":\"absent\"},\"fetchSeconds\":2,\"clearRequests\":true}");
        pair();

        UiObject2 settings = device.wait(Until.findObject(By.desc("Settings")), WAIT);
        assertNotNull(settings);
        settings.click();
        UiObject2 list = device.wait(Until.findObject(By.scrollable(true)), WAIT);
        assertNotNull(list);
        UiObject2 label = list.scrollUntil(Direction.DOWN, Until.findObject(By.text("Stories 260K")));
        assertNotNull("the Mac's list is in Settings", label);
        list.scrollUntil(Direction.DOWN, Until.findObject(By.desc("Stories 260K: Not on this phone")));
        UiObject2 download = buttonUnder("Stories 260K", "Download…");
        assertNotNull("a Download… button under Stories 260K", download);
        download.click();

        // Consent first: what, how big, whose licence, and the room on this phone.
        assertNotNull(device.wait(Until.findObject(By.text("Get Stories 260K for this phone?")), WAIT));
        assertNotNull(device.findObject(By.text("Size: 1.19 MB")));
        assertNotNull(device.findObject(By.text("Licence: MIT")));
        assertNotNull(device.findObject(By.textStartsWith("Free on this phone: ")));
        assertNotNull("Wi-Fi unless the owner says otherwise", device.findObject(By.text("Download on Wi-Fi")));
        final String fetch = "GET /ondevice/models/" + STORIES + "/file";
        final String prepare = "POST /ondevice/models/" + STORIES + "/prepare";
        device.findObject(By.text("Download on Wi-Fi")).click();

        // This emulator's only network is mobile data, so a Wi-Fi-only download waits — and
        // says so — rather than spending the owner's data.
        assertNotNull("it waits for Wi-Fi", device.wait(Until.findObject(By.descStartsWith("Stories 260K: Waiting for Wi-Fi")), WAIT));
        Thread.sleep(3_000);
        assertFalse("nothing was fetched on mobile data", asked(fetch));
        assertFalse("and the Mac was not even asked to get it ready", asked(prepare));
        UiObject2 cancel = device.wait(Until.findObject(By.text("Cancel")), WAIT);
        assertNotNull(cancel);
        cancel.click();

        // Asked again, this time with mobile data allowed for this one download.
        device.wait(Until.findObject(By.desc("Stories 260K: Not on this phone")), WAIT);
        UiObject2 again = buttonUnder("Stories 260K", "Download…");
        assertNotNull(again);
        again.click();
        assertNotNull(device.wait(Until.findObject(By.text("Get Stories 260K for this phone?")), WAIT));
        device.findObject(By.clazz("android.widget.CheckBox")).click();
        UiObject2 go = device.wait(Until.findObject(By.text("Download")), WAIT);
        assertNotNull("the button says it will use mobile data", go);
        go.click();

        assertTrue("it arrived and was verified", waitFor(90_000, () -> ((String) probe("installed", context)).contains(STORIES)));
        assertEquals(STORIES_SHA256, probe("sha256", context, STORIES));
        assertTrue("the Mac was asked to fetch it: " + mac.ondeviceRequests(), asked(prepare));
        assertTrue("and the phone fetched it from the Mac", asked(fetch));
        assertTrue("and said so", waitFor(10_000, () -> {
            for (StatusBarNotification posted : context.getSystemService(NotificationManager.class).getActiveNotifications()) {
                CharSequence title = posted.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
                if (title != null && title.toString().equals("Stories 260K is ready on this phone")) return true;
            }
            return false;
        }));
    }

    /**
     * A download that stopped half-way is a gigabyte of the owner's storage with nothing to
     * show for it. Settings says it is there, and offers the two things worth doing.
     */
    @Test
    public void aPartFinishedDownloadIsVisible_andSurvivesLeavingTheApp() throws Exception {
        probe("delete", context, STORIES);
        mac.ondevice("{\"state\":{\"" + STORIES + "\":\"ready\"}}");
        pair();
        // As a cancelled download leaves it: bytes on disk and the record of how many.
        probe("partial", context, STORIES_SHA256, 500_000L);

        openPhoneModels();
        UiObject2 paused = device.wait(Until.findObject(By.descContains("of 1.19 MB is already here — paused")), WAIT);
        if (paused == null) evidence("partial");
        assertNotNull("Settings says what is half-here", paused);
        assertNotNull("and offers to carry on", buttonUnder("Stories 260K", "Resume…"));
        assertNotNull("or to get the space back", buttonUnder("Stories 260K", "Delete"));

        // It is read off the disk, so leaving the app and coming back changes nothing.
        device.pressHome();
        Thread.sleep(1_000);
        openPhoneModels();
        assertNotNull("still there after leaving the app",
            device.wait(Until.findObject(By.descContains("of 1.19 MB is already here — paused")), WAIT));

        buttonUnder("Stories 260K", "Delete").click();
        assertNotNull("and deleting it says so", device.wait(Until.findObject(By.desc("Stories 260K: Not on this phone")), WAIT));
    }

    /**
     * Settings → On this phone, scrolled to Stories 260K — from wherever the app happens to
     * be, including Settings itself, which is where it comes back to after a trip to Home.
     */
    private void openPhoneModels() throws Exception {
        bringToFront();
        if (!device.hasObject(By.text("Stories 260K"))) {
            UiObject2 settings = device.findObject(By.desc("Settings"));
            if (settings != null) {
                settings.click();
                device.waitForIdle();
            }
            UiObject2 list = device.wait(Until.findObject(By.scrollable(true)), WAIT);
            assertNotNull("the Settings screen", list);
            if (list.scrollUntil(Direction.DOWN, Until.findObject(By.text("Stories 260K"))) == null) {
                list.scrollUntil(Direction.UP, Until.findObject(By.text("Stories 260K")));
            }
        }
        assertNotNull("the phone models section", device.wait(Until.findObject(By.text("Stories 260K")), WAIT));
    }

    @Test
    public void aMacOutOfReachOffersThePhone_andItsAnswerSaysSo_andNeverReachesTheMac() throws Exception {
        // SmolLM2 alone on the phone, so it is the one offered whatever the stand-in's default.
        probe("delete", context, STORIES);
        probe("delete", context, QWEN);
        install(SMOL);
        pair();
        UiObject2 chat = device.wait(Until.findObject(By.text("Chat")), WAIT);
        assertNotNull(chat);
        chat.click();
        UiObject2 fresh = device.wait(Until.findObject(By.desc("New conversation")), WAIT);
        assertNotNull(fresh);
        fresh.click();
        UiObject2 field = device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT);
        assertNotNull(field);

        mac.unreachable(120);
        field.setText("What does a lighthouse keeper do?");
        UiObject2 send = device.wait(Until.findObject(By.desc("Send")), WAIT);
        assertNotNull(send);
        send.click();

        // Never silent, never automatic: the offer, and nothing answered until it is tapped.
        UiObject2 banner = device.wait(Until.findObject(By.text("Your Mac isn't answering.")), WAIT);
        assertNotNull(banner);
        assertEquals("Unloaded", state());
        // The banner's own button — below its text — rather than the failed reply's, which
        // sits in a list that may still be scrolling to it. A tap that lands on nothing is
        // tried once more.
        device.waitForIdle();
        for (int attempt = 0; attempt < 2 && device.hasObject(By.text("Your Mac isn't answering.")); attempt++) {
            UiObject2 answer = null;
            int top = device.findObject(By.text("Your Mac isn't answering.")).getVisibleBounds().top;
            for (UiObject2 candidate : device.findObjects(By.textContains("Answer on this phone"))) {
                if (candidate.getVisibleBounds().top > top) answer = candidate;
            }
            assertNotNull("the banner offers the phone", answer);
            answer.click();
            device.wait(Until.gone(By.text("Your Mac isn't answering.")), 3_000);
        }

        UiObject2 chip = device.wait(Until.findObject(By.desc("On this phone · SmolLM2 135M")), 90_000);
        if (chip == null) evidence("fallback");
        assertNotNull("the answer carries the chip", chip);
        // The phone writes at a word or two a second and nobody is touching the screen:
        // without this the display sleeps, the app leaves the foreground and its own rules
        // stop the answer it is in the middle of.
        assertTrue("the screen is kept awake while the phone writes", waitFor(WAIT, this::screenIsKeptOn));
        assertNotNull("the phone's model is writing it", device.wait(Until.findObject(By.desc("This phone's model replied")), WAIT));
        // Finished: the composer offers Send again rather than Stop.
        assertNotNull("and finishes", device.wait(Until.findObject(By.desc("Send")), 120_000));
        assertTrue(state().startsWith("Loaded:" + SMOL));

        mac.unreachable(0);
        for (String line : mac.requests()) {
            assertFalse("the Mac never heard of the phone's conversation: " + line, line.contains("phone-"));
        }
        assertFalse("and it is let go of as soon as the answer ends", screenIsKeptOn());
    }

    /**
     * The feature's own front door, from a phone that has already talked to its Mac.
     *
     * Once the app has learned the Mac keeps conversations, a new one is the Mac's to make —
     * and with the Mac gone that call fails, which for one round left "+" and the tile's
     * "Ask on this phone" opening nothing at all: the conversation list, no composer, and
     * so no offer either. This is that, on the screen.
     */
    @Test
    public void withTheMacGoneTheTileAndThePlusButtonStillOpenAComposerWithTheOffer() throws Exception {
        install(SMOL);
        probe("delete", context, STORIES);
        probe("delete", context, QWEN);
        pair();
        // The app asks what this Mac keeps as soon as it is paired; wait until it knows.
        assertTrue("the app read the Mac's conversations", waitFor(WAIT, () -> justSaw("GET /conversations")));

        mac.unreachable(120);
        context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("siliconbuddy://ask?offer=phone"))
            .setPackage(PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));

        UiObject2 banner = device.wait(Until.findObject(By.text("Your Mac isn't answering.")), WAIT);
        if (banner == null) evidence("tile-offer");
        assertNotNull("the tile's link lands on the offer", banner);
        assertNotNull("with a composer under it", device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT));
        assertEquals("and nothing answered by itself", "Unloaded", state());

        // The "+" button, from the same state.
        UiObject2 fresh = device.wait(Until.findObject(By.desc("New conversation")), WAIT);
        assertNotNull(fresh);
        fresh.click();
        UiObject2 again = device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT);
        if (again == null) evidence("plus-offer");
        assertNotNull("a new conversation opens rather than nothing", again);
        assertNotNull("and says the Mac isn't answering",
            device.wait(Until.findObject(By.text("Your Mac isn't answering.")), WAIT));
        assertEquals("still nothing answered", "Unloaded", state());
        mac.unreachable(0);
    }

    /** Whether this app's window is holding the screen awake, as the window manager sees it. */
    private boolean screenIsKeptOn() throws Exception {
        String windows = shell("dumpsys window windows");
        for (String block : windows.split("Window #")) {
            if (block.contains(PACKAGE + "/" + PACKAGE + ".MainActivity") && block.contains("KEEP_SCREEN_ON")) return true;
        }
        return false;
    }

}

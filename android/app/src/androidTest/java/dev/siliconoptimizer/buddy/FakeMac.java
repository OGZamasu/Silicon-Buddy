package dev.siliconoptimizer.buddy;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in Mac inside the test process, on 127.0.0.1.
 *
 * Java rather than Kotlin, and only java.* and android.*, on purpose. These tests run
 * against the minified release build, and the test APK is shrunk against the app's
 * already-shrunk output: a Kotlin standard-library call the app itself never made has been
 * removed from the app, so it exists in neither APK and the test dies with
 * NoSuchMethodError before it tests anything. Nothing here can go missing that way.
 *
 * It speaks the little of the control API the Agents tab needs: pairing, health, status,
 * the event stream with `agent` frames, the two sessions, and answering the approvals Codex
 * is holding — one, or two when a test needs to tell them apart. And the little the Models
 * tab needs: one model on disk, loading and unloading it, and `status` frames — each of
 * which a test can make fail, or make slow. And the little the Create tab's queue needs: a
 * `GET /video/queue` a test writes, and a `cancel` answered the way a test says. Everything
 * else is the Mac's 404, which the app already treats as a route this Mac does not have.
 */
final class FakeMac implements Closeable {

    static final String TOKEN = "device-test-token";
    static final String APPROVAL = "5D8B2F01-9A3C-4E67-8B21-0C4D5E6F7A81";
    /** The second approval, a file change, asked after the first when there are two. */
    static final String SECOND = "8C2E4A60-1B3D-4F58-9A7C-2D3E4F5A6B7C";
    static final String CODEX_EPOCH = "4B1D6C3E-2A9F-4E70-8D51-7C6B5A493827";
    static final String PI_EPOCH = "1A2B3C4D-5E6F-4071-8293-A4B5C6D7E8F9";

    private final ServerSocket socket;
    private final List<BlockingQueue<String>> streams = new CopyOnWriteArrayList<>();
    final List<String> received = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;

    /** How the approval was answered, and from where; null while it is still waiting. */
    volatile String decision;
    /** The same for the second, when there is one. */
    volatile String secondDecision;
    private final boolean second;
    private int seq = 41;

    // MARK: - The Models tab

    static final String DRIVE_GONE = "The model drive is disconnected.";
    static final String IDLE = "{\"state\":\"Not loaded\",\"expertStreaming\":false}";
    static final String LOADING = "{\"state\":\"Loading weights… 42%\",\"expertStreaming\":false}";
    static final String LOADED = "{\"state\":\"Ready\",\"loadedModelID\":\"test-model@Q4_K_M\","
        + "\"loadedModelName\":\"Test Model\",\"contextLength\":4096,\"expertStreaming\":false}";
    private static final String INSTALLED = "[{\"id\":\"test-model@Q4_K_M\",\"name\":\"Test Model\","
        + "\"quantization\":\"Q4_K_M\",\"sizeOnDiskBytes\":1300000000,\"isLoaded\":false,"
        + "\"supportsVision\":false}]";

    /** `GET /installed` answers 503 while this is set. */
    volatile boolean installedFails;
    /** `POST /load` answers 503 while this is set. */
    volatile boolean loadFails;
    /** `POST /load` answers "still loading" while this is set, as a slow load does. */
    volatile boolean loadIsSlow;
    /** What `GET /status` says; null for the Agents tests' plain "Ready". */
    volatile String status;

    // MARK: - The Create tab's queue

    /** What `GET /video/queue` answers; null for the 404 of a Mac without the route. */
    volatile String videoQueue;
    /** The queue after a `cancel`, which becomes the queue from then on. */
    volatile String cancelAnswer;
    /** What `POST /buddy/pair` grants: `full`, or `chat` for a phone lent to somebody. */
    volatile String scope = "full";

    static final String CHAT_ONLY = "This device is paired for chat only. Pair it again with full "
        + "control from Settings \u2192 Silicon Buddy on the Mac.";

    FakeMac() throws IOException {
        this(false);
    }

    FakeMac(boolean second) throws IOException {
        this.second = second;
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(this::acceptLoop, "fake-mac");
        accept.setDaemon(true);
        accept.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    /** True once something asked for {@code method path}. */
    boolean saw(String method, String path) {
        for (String line : received) {
            if (line.startsWith(method + " " + path)) return true;
        }
        return false;
    }

    String bodyOf(String method, String path) {
        for (String line : received) {
            if (line.startsWith(method + " " + path + " ")) {
                return line.substring((method + " " + path + " ").length());
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        running = false;
        socket.close();
    }

    // MARK: - What it answers

    /** What Codex is holding, oldest first. */
    private synchronized List<String> pending() {
        List<String> waiting = new java.util.ArrayList<>();
        if (decision == null) waiting.add(APPROVAL_JSON);
        if (second && secondDecision == null) waiting.add(SECOND_JSON);
        return waiting;
    }

    private synchronized String summary(String engine) {
        boolean codex = "codex".equals(engine);
        int held = codex ? pending().size() : 0;
        boolean waiting = held > 0;
        return "{\"engine\":\"" + engine + "\",\"state\":\"" + (codex ? "running" : "stopped") + "\","
            + (codex ? "\"threadID\":\"0199F2C1-4A7E-4C3B-9D15-6E2A8B0C1D3F\"," : "")
            + "\"epoch\":\"" + (codex ? CODEX_EPOCH : PI_EPOCH) + "\","
            + "\"model\":\"local/qwen3-coder-30b\","
            + "\"modelChoices\":[{\"id\":\"local/qwen3-coder-30b\",\"label\":\"Qwen3-Coder 30B A3B\",\"where\":\"This Mac\"}],"
            + "\"cwd\":\"~/Developer/lisbon\","
            + "\"approvals\":\"" + (codex ? "screened" : "unattended") + "\","
            + "\"sandbox\":\"" + (codex ? "workspace-write" : "none") + "\","
            + "\"turnActive\":" + waiting + ","
            + "\"pendingApprovals\":" + held + ","
            + "\"itemCount\":" + (codex ? (decision == null ? 3 : 4) : 0) + ","
            + "\"updatedAt\":\"2026-09-19T10:12:44Z\"}";
    }

    private static final String ITEMS =
        "{\"id\":\"U1\",\"kind\":\"user\",\"text\":\"Run the tests and fix whatever the first failure is.\",\"model\":\"local/qwen3-coder-30b\",\"at\":\"2026-09-19T10:12:31Z\"},"
            + "{\"id\":\"R1\",\"kind\":\"reasoning\",\"text\":\"Run the suite first, then read the first failure.\",\"at\":\"2026-09-19T10:12:33Z\"},"
            + "{\"id\":\"M1\",\"kind\":\"assistant\",\"text\":\"Running the suite now.\",\"at\":\"2026-09-19T10:12:35Z\"}";

    private static final String COMMAND =
        "{\"id\":\"C1\",\"kind\":\"command\",\"text\":\"swift test --filter Lisbon\",\"status\":\"completed\","
            + "\"output\":\"Executed 12 tests, with 0 failures.\",\"at\":\"2026-09-19T10:12:50Z\"}";

    private static final String APPROVAL_JSON =
        "{\"id\":\"" + APPROVAL + "\",\"kind\":\"command\",\"summary\":\"swift test --filter Lisbon\","
            + "\"reason\":\"Codex asks before running a command in this folder.\","
            + "\"screening\":{\"verdict\":\"confirm\",\"summary\":\"Jev: review\"},"
            + "\"requestedAt\":\"2026-09-19T10:12:36Z\"}";

    private static final String SECOND_JSON =
        "{\"id\":\"" + SECOND + "\",\"kind\":\"fileChange\",\"summary\":\"Sources/Lisbon/Itinerary.swift\","
            + "\"reason\":\"Codex asks before changing files in this folder.\","
            + "\"screening\":{\"verdict\":\"confirm\",\"summary\":\"Jev: review\"},"
            + "\"requestedAt\":\"2026-09-19T10:12:40Z\"}";

    private synchronized String detail(String engine) {
        boolean codex = "codex".equals(engine);
        String items = codex ? (decision == null ? ITEMS : ITEMS + "," + COMMAND) : "";
        String approvals = codex ? String.join(",", pending()) : "";
        return "{\"session\":" + summary(engine) + ",\"items\":[" + items + "],\"approvals\":["
            + approvals + "],\"seq\":" + seq + ",\"epoch\":\"" + (codex ? CODEX_EPOCH : PI_EPOCH)
            + "\",\"complete\":true,\"omitted\":0}";
    }

    private void publish(String frame) {
        for (BlockingQueue<String> stream : streams) stream.add(frame);
    }

    /** The Mac's status changed: said to `GET /status` and pushed to every stream. */
    void publishStatus(String json) {
        status = json;
        for (BlockingQueue<String> stream : streams) stream.add(STATUS_FRAME + json);
    }

    /** Marks a queued frame as a `status` one; everything else on the queue is `agent`. */
    private static final String STATUS_FRAME = "status:";

    /**
     * The phone answered: the card comes down on every screen, the first approval's command
     * runs, and once nothing is held the turn ends.
     */
    private synchronized void answer(String id, String how) {
        boolean first = APPROVAL.equals(id);
        if (first) decision = how;
        else secondDecision = how;
        String state = "accept".equals(how) ? "accepted" : "declined";
        String head = "{\"engine\":\"codex\",\"epoch\":\"" + CODEX_EPOCH + "\",";
        publish(head + "\"kind\":\"approval\",\"seq\":" + (++seq) + ",\"approval\":"
            + (first ? APPROVAL_JSON : SECOND_JSON) + ",\"state\":\"" + state + "\"}");
        if (first) publish(head + "\"kind\":\"item\",\"seq\":" + (++seq) + ",\"item\":" + COMMAND + "}");
        if (pending().isEmpty()) {
            publish(head + "\"kind\":\"turn\",\"seq\":" + (++seq) + ",\"turnActive\":false}");
        }
    }

    // MARK: - The little of HTTP it speaks

    private void acceptLoop() {
        while (running) {
            try {
                Socket connection = socket.accept();
                Thread serve = new Thread(() -> serve(connection));
                serve.setDaemon(true);
                serve.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket connection) {
        try (Socket open = connection) {
            InputStream input = open.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int previous = -1;
            int current;
            int newlines = 0;
            while ((current = input.read()) >= 0) {
                head.write(current);
                if (current == '\n') {
                    newlines = previous == '\r' || previous == '\n' ? newlines + 1 : 1;
                    if (newlines >= 2) break;
                } else if (current != '\r') {
                    newlines = 0;
                }
                previous = current;
            }
            String[] lines = head.toString("UTF-8").split("\r\n");
            String[] request = lines[0].split(" ");
            String method = request[0];
            String target = request.length > 1 ? request[1] : "/";
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            int length = 0;
            String authorization = "";
            for (String line : lines) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                if (lower.startsWith("authorization:")) authorization = line.substring(14).trim();
            }
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int count = input.read(body, read, length - read);
                if (count < 0) break;
                read += count;
            }
            String text = new String(body, StandardCharsets.UTF_8);
            received.add(method + " " + path + " " + text);
            OutputStream output = open.getOutputStream();

            if (path.equals("/health")) {
                reply(output, 200, "{\"status\":\"ok\",\"version\":\"0.1.0\"}");
                return;
            }
            if (method.equals("POST") && path.equals("/buddy/pair")) {
                reply(output, 200, "{\"deviceID\":\"D-TEST\",\"token\":\"" + TOKEN + "\",\"macName\":\"Test Mac\",\"port\":"
                    + port() + ",\"scope\":\"" + scope + "\"}");
                return;
            }
            if (!authorization.equals("Bearer " + TOKEN)) {
                reply(output, 401, "{\"error\":\"Invalid or missing control token.\"}");
                return;
            }
            if (path.equals("/status")) {
                String now = status;
                reply(output, 200, now != null ? now : "{\"state\":\"Ready\",\"expertStreaming\":false}");
            } else if (path.equals("/installed")) {
                if (installedFails) reply(output, 503, "{\"error\":\"" + DRIVE_GONE + "\"}");
                else reply(output, 200, INSTALLED);
            } else if (path.equals("/catalog")) {
                reply(output, 200, "[]");
            } else if (method.equals("POST") && path.equals("/load")) {
                if (loadFails) {
                    reply(output, 503, "{\"error\":\"" + DRIVE_GONE + "\"}");
                } else if (loadIsSlow) {
                    // The Mac's patience ran out: the live status, and the load carries on.
                    status = LOADING;
                    reply(output, 200, LOADING);
                } else {
                    status = LOADED;
                    reply(output, 200, LOADED);
                }
            } else if (method.equals("POST") && path.equals("/unload")) {
                status = IDLE;
                reply(output, 200, "{\"status\":\"unloaded\"}");
            } else if (method.equals("GET") && path.equals("/video/queue") && videoQueue != null) {
                reply(output, 200, videoQueue);
            } else if (method.equals("POST") && path.equals("/video/queue/control") && videoQueue != null) {
                if (!"full".equals(scope)) {
                    reply(output, 403, "{\"error\":\"" + CHAT_ONLY + "\"}");
                } else if (text.contains("\"cancel\"") && cancelAnswer != null) {
                    videoQueue = cancelAnswer;
                    reply(output, 200, cancelAnswer);
                } else {
                    reply(output, 200, videoQueue);
                }
            } else if (path.equals("/events")) {
                stream(output);
            } else if (path.equals("/agent/sessions")) {
                reply(output, 200, "{\"sessions\":[" + summary("codex") + "," + summary("pi") + "]}");
            } else if (path.equals("/agent/sessions/codex") || path.equals("/agent/sessions/pi")) {
                reply(output, 200, detail(path.substring("/agent/sessions/".length())));
            } else if (method.equals("POST") && (path.equals("/agent/sessions/codex/approvals/" + APPROVAL)
                || (second && path.equals("/agent/sessions/codex/approvals/" + SECOND)))) {
                String id = path.substring("/agent/sessions/codex/approvals/".length());
                if ((APPROVAL.equals(id) ? decision : secondDecision) != null) {
                    reply(output, 404, "{\"error\":\"No approval with id " + id + " is waiting. It was answered already, or never existed.\"}");
                    return;
                }
                String how = text.contains("\"accept\"") ? "accept" : "decline";
                answer(id, how);
                reply(output, 200, "{\"id\":\"" + id + "\",\"decision\":\""
                    + ("accept".equals(how) ? "accepted" : "declined") + "\",\"session\":" + summary("codex") + "}");
            } else {
                reply(output, 404, "{\"error\":\"Unknown endpoint " + method + " " + path + "\"}");
            }
        } catch (IOException | RuntimeException ignored) {
            // A phone that hung up.
        }
    }

    private static void reply(OutputStream output, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Error") + "\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: " + bytes.length + "\r\n"
            + "Connection: close\r\n\r\n";
        output.write(head.getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.flush();
    }

    /**
     * What a phone is sent first on connecting, as the Mac does: each engine's state and
     * turn, and every pending approval, all at the current sequence — and no rows.
     */
    private synchronized void opening(BlockingQueue<String> queue) {
        List<String> held = pending();
        String codex = "{\"engine\":\"codex\",\"epoch\":\"" + CODEX_EPOCH + "\",\"seq\":" + seq + ",";
        String pi = "{\"engine\":\"pi\",\"epoch\":\"" + PI_EPOCH + "\",\"seq\":" + seq + ",";
        queue.add(codex + "\"kind\":\"state\",\"state\":\"running\"}");
        queue.add(codex + "\"kind\":\"turn\",\"turnActive\":" + !held.isEmpty() + "}");
        for (String approval : held) {
            queue.add(codex + "\"kind\":\"approval\",\"approval\":" + approval + ",\"state\":\"pending\"}");
        }
        queue.add(pi + "\"kind\":\"state\",\"state\":\"stopped\"}");
        queue.add(pi + "\"kind\":\"turn\",\"turnActive\":false}");
    }

    /** `/events`: a heartbeat at once and every few seconds, and whatever is published. */
    private void stream(OutputStream output) throws IOException {
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        opening(queue);
        streams.add(queue);
        try {
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write("event: heartbeat\ndata: {\"at\":\"2026-09-19T10:12:30Z\"}\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            long lastBeat = System.currentTimeMillis();
            while (running) {
                String frame = queue.poll(500, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    String sse = frame.startsWith(STATUS_FRAME)
                        ? "event: status\ndata: " + frame.substring(STATUS_FRAME.length())
                        : "event: agent\ndata: " + frame;
                    output.write((sse + "\n\n").getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                if (System.currentTimeMillis() - lastBeat > 5000) {
                    output.write("event: heartbeat\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    lastBeat = System.currentTimeMillis();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            streams.remove(queue);
        }
    }
}

package dev.siliconoptimizer.buddy.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.ImageModel
import dev.siliconoptimizer.buddy.transport.ImagePlan
import dev.siliconoptimizer.buddy.transport.JobProgress
import dev.siliconoptimizer.buddy.transport.MeshModel
import dev.siliconoptimizer.buddy.transport.MeshPlan
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.transport.UploadResponse
import dev.siliconoptimizer.buddy.transport.VideoModel
import dev.siliconoptimizer.buddy.transport.VideoQueueControlRequest
import dev.siliconoptimizer.buddy.transport.VideoQueueView
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Create tab: three kinds of render, and the queue they end up in.
 *
 * The rules about what may be asked for live in [VideoRequest], [ImageRequestBuilder]
 * and [MeshRequestBuilder]; the shape of the queue lives in [QueueState]. What is left
 * here is the asking: which lists have been read, which draft the person is filling in,
 * and which long request is running where.
 *
 * Long renders are not run from here. `POST /image/generate` and its two siblings answer
 * when the work is finished, minutes later, so they are handed to [MediaJobService] —
 * this view model only builds the request.
 */
class MediaViewModel : ViewModel() {

    enum class Tab(val label: String) {
        Video("Video"), Image("Image"), Mesh("3D"), Queue("Queue")
    }

    var tab by mutableStateOf(Tab.Video)

    // MARK: - What the Mac can do

    var videoModels by mutableStateOf<List<VideoModel>>(emptyList())
        private set
    var imageModels by mutableStateOf<List<ImageModel>>(emptyList())
        private set
    var meshModels by mutableStateOf<List<MeshModel>>(emptyList())
        private set

    /** True only when `GET /jev` says this Mac's media router is on. */
    var routesMedia by mutableStateOf(false)
        private set

    /** Why auto is missing, when it is missing for a reason worth saying. */
    var autoNote by mutableStateOf<String?>(null)
        private set

    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    // MARK: - The video draft

    var videoPrompt by mutableStateOf("")
    var videoTitle by mutableStateOf("")
    var videoNegativePrompt by mutableStateOf("")
    var videoSeconds by mutableStateOf<Int?>(null)
    var videoResolution by mutableStateOf<String?>(null)
    var videoVariations by mutableStateOf(1)
    private var chosenLaneID by mutableStateOf<String?>(null)
    private var laneChosen by mutableStateOf(false)

    val lanes: List<VideoLane> get() = VideoRequest.lanes(videoModels, routesMedia)

    /** The lane in force: what was chosen, or the first one worth defaulting to. */
    val lane: VideoLane?
        get() {
            val all = lanes
            if (laneChosen) {
                return all.firstOrNull { it.id == chosenLaneID } ?: all.firstOrNull()
            }
            return all.firstOrNull { VideoRequest.isAvailable(it) } ?: all.firstOrNull()
        }

    fun choose(lane: VideoLane) {
        chosenLaneID = lane.id
        laneChosen = true
        val choices = VideoRequest.secondsChoices(lane)
        if (videoSeconds !in choices) videoSeconds = VideoRequest.seconds(lane, videoSeconds)
        videoResolution = VideoRequest.resolution(lane, videoResolution)
    }

    val secondsChoices: List<Int> get() = VideoRequest.secondsChoices(lane)
    val resolutionChoices: List<String> get() = VideoRequest.resolutions(lane)
    val takesNegativePrompt: Boolean get() = VideoRequest.takesNegativePrompt(lane)
    val takesAStill: Boolean
        get() = (lane as? VideoLane.On)?.model?.supportsImageInput == true

    // MARK: - The image draft

    var imagePrompt by mutableStateOf("")
    private var chosenImageModelID by mutableStateOf<String?>(null)

    // A plan is an answer about one size at one number of steps. Changing either makes
    // the numbers on screen belong to a render nobody asked for.
    private var sizeState by mutableStateOf(1024)
    var imageSize: Int
        get() = sizeState
        set(value) {
            if (value == sizeState) return
            sizeState = value
            imagePlan = null
        }

    private var stepsState by mutableStateOf(8)
    var imageSteps: Int
        get() = stepsState
        set(value) {
            if (value == stepsState) return
            stepsState = value
            imagePlan = null
        }
    var imagePlan by mutableStateOf<ImagePlan?>(null)
        private set
    var isPlanningImage by mutableStateOf(false)
        private set

    val imageModel: ImageModel?
        get() = imageModels.firstOrNull { it.id == chosenImageModelID } ?: imageModels.firstOrNull()

    fun choose(model: ImageModel) {
        chosenImageModelID = model.id
        stepsState = ImageRequestBuilder.defaultSteps(model)
        sizeState = ImageRequestBuilder.defaultSize(model)
        // The plan on screen belongs to the model that was there a moment ago.
        imagePlan = null
    }

    // MARK: - The mesh draft

    var meshTextureSize by mutableStateOf(2048)
    private var chosenMeshModelID by mutableStateOf<String?>(null)
    var meshPlan by mutableStateOf<MeshPlan?>(null)
        private set
    var isPlanningMesh by mutableStateOf(false)
        private set

    /** The picture this phone sent, and what the Mac calls it now. */
    var pickedPhotoName by mutableStateOf<String?>(null)
        private set
    var uploaded by mutableStateOf<UploadResponse?>(null)
        private set
    var isUploading by mutableStateOf(false)
        private set

    /** The same, for a still to animate on a lane that takes one. */
    var stillPhotoName by mutableStateOf<String?>(null)
        private set
    var stillUpload by mutableStateOf<UploadResponse?>(null)
        private set

    val meshModel: MeshModel?
        get() = meshModels.firstOrNull { it.id == chosenMeshModelID }
            ?: meshModels.firstOrNull { it.isInstalled }
            ?: meshModels.firstOrNull()

    fun choose(model: MeshModel) {
        chosenMeshModelID = model.id
        meshPlan = null
    }

    // MARK: - The queue

    var queue by mutableStateOf(QueueState.empty)
        private set

    private val announcer = JobAnnouncer()
    private var poller: Job? = null

    /**
     * The one door every queue update goes through.
     *
     * Both sources — the poll and the `job` events — land here, and the announcer
     * decides what is worth saying, so a notification never depends on which of the two
     * happened to notice a render had finished.
     */
    private fun update(next: QueueState, notifier: MediaNotifier?) {
        val previous = queue
        queue = next
        announcer.notices(previous, next, ::handledByTheService).forEach { notifier?.post(it) }
    }

    /**
     * Work this phone is holding a request open for. The Mac reports it on `/events`
     * as well, and the service that is waiting on it does the telling.
     */
    private fun handledByTheService(job: MediaJob): Boolean =
        !job.isQueued && MediaJobCenter.isRendering(job.kind)

    /**
     * The first `GET /video/queue` of a Mac, which is its history.
     *
     * Priming has to come from a queue rather than from whatever arrived first: a `job`
     * event can beat the first poll, and priming on that would make every finished clip
     * in the Mac's memory new a moment later — one notification each.
     */
    private fun prime(view: VideoQueueView) {
        queue = queue.applying(view)
        announcer.prime(queue)
    }

    /** One pass over every list the Create tab shows. */
    fun refresh(
        transport: ControlTransport?,
        notifier: MediaNotifier? = null,
        canControl: Boolean = true,
    ) {
        if (transport == null) return
        val mac = generation
        viewModelScope.launch {
            isLoading = true
            val video = async { runCatching { transport.videoModels() }.getOrNull() }
            val image = async { runCatching { transport.imageModels() }.getOrNull() }
            val mesh = async { runCatching { transport.meshModels() }.getOrNull() }
            val queued = async { runCatching { transport.videoQueue() }.getOrNull() }
            // `GET /jev` takes full control: a chat-scope phone is answered 403, so it
            // does not ask. An older Mac answers 404, which is the same answer in the
            // end — no auto lane — and a fact rather than an error to show.
            val jev = async {
                if (canControl) runCatching { transport.jev() }.getOrNull() else null
            }

            val newVideo = video.await()
            val newImage = image.await()
            val newMesh = mesh.await()
            val newQueue = queued.await()
            val newJev = jev.await()
            // Asked of the Mac this phone was paired with then; a re-pair has its own read.
            if (mac != generation) return@launch

            newVideo?.let { videoModels = it }
            newImage?.let { imageModels = it }
            newMesh?.let { meshModels = it }
            newQueue?.let { if (announcer.isPrimed) update(queue.applying(it), notifier) else prime(it) }
            routesMedia = newJev?.routesMedia == true
            autoNote = when {
                newJev == null -> null
                newJev.routesMedia -> "The Mac picks the model and settings for Auto."
                newJev.advertisesMediaRouting ->
                    "This Mac can route media itself, but that is switched off in its Jev settings."
                else -> null
            }
            if (imageModel != null && chosenImageModelID == null) {
                stepsState = ImageRequestBuilder.defaultSteps(imageModel)
                sizeState = ImageRequestBuilder.defaultSize(imageModel)
            }
            error = if (newVideo == null && newImage == null && newMesh == null) {
                "Couldn't read what this Mac can render."
            } else {
                null
            }
            isLoading = false
        }
    }

    /**
     * Reads the queue, and keeps reading it only when nothing is pushing.
     *
     * The `job` event carries the stage and the Mac's own reason for a failure now, so
     * a live stream is the whole story and a poll beside it would only be a second
     * source to disagree with. It still runs on a Mac without `/events` — that is what
     * [live] is for — and the first read always happens, because the queue holds what
     * was rendered before this phone was looking.
     */
    fun startFollowing(
        transport: ControlTransport?,
        notifier: MediaNotifier? = null,
        live: Boolean = true,
        seconds: Long = 6,
    ) {
        poller?.cancel()
        if (transport == null) return
        val mac = generation
        poller = viewModelScope.launch {
            while (isActive) {
                runCatching { transport.videoQueue() }.getOrNull()
                    ?.takeIf { mac == generation }
                    ?.let { if (announcer.isPrimed) update(queue.applying(it), notifier) else prime(it) }
                if (live) return@launch
                delay(seconds * 1000)
            }
        }
    }

    fun stopFollowing() {
        poller?.cancel()
        poller = null
    }

    /**
     * One `job` event from the shared stream.
     *
     * Also where a notification comes from: the phone says a render is done because it
     * saw it finish, whichever screen happened to be in front.
     */
    fun apply(
        event: JobProgress,
        notifier: MediaNotifier? = null,
        transport: ControlTransport? = null,
    ) {
        MediaJobCenter.note(event.kind, event.fraction)
        val state = JobState.of(event.status)
        if (state.isTerminal || state == JobState.Queued) {
            endingsSeen[event.id] = (endingsSeen[event.id] ?: 0) + 1
        }
        val before = queue.job(event.id)
        val unknown = event.kind == "video" && before?.isQueued != true
        update(queue.applying(event), notifier)
        val after = queue.job(event.id)
        // Rendering again after a failure is a reconnect — from the Mac's own window, say
        // — or a late word. The reducer will not take it from an event, so the queue is
        // asked, after it: a read that leaves after the event is newer than it, and may
        // speak for the clip if no ending reaches it meanwhile.
        val reconnect = before?.isQueued == true && before.state == JobState.Failed &&
            state.isRunning && after?.state == JobState.Failed
        if (reconnect) reopening += event.id
        // A clip queued from somewhere else — the Mac's own window, another phone —
        // is first heard of here, and an event carries no prompt and no settings. So
        // the queue is read once, for that clip's details; this is not the old poll
        // coming back, which asked every six seconds whether anything had happened.
        // The same goes for whether the clip can be cancelled, which an event never says.
        if (unknown || reconnect || cancelNeedsTheQueue(before, after)) {
            readQueueOnce(transport, notifier)
        }
    }

    /**
     * What this phone knew of one clip when a verb or a read about it went out: its state,
     * and how many endings (or re-queueings) the stream had said of it. The answer may
     * speak for the clip over an ending only if neither has changed since — the second
     * catches an ending that left the state as it was, a clip failing again, say.
     */
    private data class Asked(val answering: Answering, val endings: Int)

    private val endingsSeen = mutableMapOf<String, Int>()

    /** Clips a stream said were rendering again after failing, waiting for a read to confirm it. */
    private val reopening = mutableSetOf<String>()

    /**
     * Bumped by [reset]. Anything that was asking the last Mac checks it before it writes,
     * so an answer that arrives after a re-pair — a cancel waits up to 75 seconds — never
     * lands on the next Mac's queue.
     */
    private var generation = 0

    private fun asked(id: String) = Asked(Answering(id, queue.job(id)?.state), endingsSeen[id] ?: 0)

    private fun Asked.unmoved(): Boolean =
        (endingsSeen[answering.id] ?: 0) == endings && queue.job(answering.id)?.state == answering.sentFrom

    private var lastRead = 0L
    private var nextRead: Job? = null
    /** A read is on the wire now, and may have been answered before a newer reason. */
    private var readInFlight = false
    /** A reason to read arrived while one was on the wire: read once more after it. */
    private var readAgain = false

    /**
     * One read of the queue, at most every three seconds. A reason to read that arrives
     * sooner waits for the end of those three seconds rather than being dropped: it is a
     * different clip's, or a later moment of the same one. One that arrives while a read
     * is on the wire gets a read of its own after it, because that read may already have
     * been answered with the queue as it was before the reason.
     */
    private fun readQueueOnce(transport: ControlTransport?, notifier: MediaNotifier?) {
        if (transport == null) return
        if (nextRead?.isActive == true) {
            // Still waiting out the three seconds: that read comes after this reason.
            if (readInFlight) readAgain = true
            return
        }
        val mac = generation
        nextRead = viewModelScope.launch {
            do {
                readAgain = false
                val wait = lastRead + 3_000 - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastRead = System.currentTimeMillis()
                // Taken as the read leaves, so the read is newer than everything counted.
                val confirming = reopening.map { asked(it) }
                reopening.clear()
                readInFlight = true
                try {
                    val view = runCatching { transport.videoQueue() }.getOrNull()
                    if (mac != generation) return@launch
                    if (view == null) {
                        reopening += confirming.map { it.answering.id }
                    } else if (announcer.isPrimed) {
                        update(queue.applying(view, confirming.filter { it.unmoved() }.map { it.answering }), notifier)
                    } else {
                        prime(view)
                    }
                } finally {
                    readInFlight = false
                }
            } while (readAgain)
        }
    }

    // MARK: - Asking for work

    fun enqueueVideo(transport: ControlTransport?, notifier: MediaNotifier? = null) {
        val request = VideoRequest.enqueue(
            prompt = videoPrompt,
            lane = lane,
            title = videoTitle,
            seconds = videoSeconds,
            variations = videoVariations,
            resolution = videoResolution,
            negativePrompt = videoNegativePrompt,
        ) ?: run {
            error = "Type a prompt first, and pick a lane that has a machine behind it."
            return
        }
        if (transport == null) return
        val mac = generation
        viewModelScope.launch {
            try {
                val view = transport.enqueueVideos(request)
                if (mac != generation) return@launch
                update(queue.applying(view), notifier)
                message = "Added to the queue on the Mac."
                videoPrompt = ""
                tab = Tab.Queue
            } catch (failure: TransportError) {
                if (mac == generation) error = failure.message
            }
        }
    }

    fun control(
        action: String,
        id: String? = null,
        transport: ControlTransport?,
        notifier: MediaNotifier? = null,
    ) {
        if (transport == null) return
        val mac = generation
        val sentFrom = id?.let { asked(it) }
        viewModelScope.launch {
            try {
                val view = transport.controlVideoQueue(VideoQueueControlRequest(action, id))
                if (mac == generation) answered(view, sentFrom, transport, notifier)
            } catch (failure: TransportError) {
                if (mac == generation) error = failure.message
            }
        }
    }

    /**
     * The Mac's answer to something this phone asked of one clip.
     *
     * It is the newest word on that clip only if nothing moved the row while the request
     * was out ([Answering.overrides]). If something did, an event overtook the answer
     * and the two disagree about which came last, so the queue is read once more to
     * settle it. So is an answer that took a clip out of an ending: the node confirming a
     * cancel the moment it was asked can overtake it after it has been applied.
     */
    private fun answered(
        view: VideoQueueView,
        asked: Asked?,
        transport: ControlTransport,
        notifier: MediaNotifier?,
    ) {
        val before = asked?.let { queue.job(it.answering.id) }
        val unmoved = asked?.unmoved() == true
        update(queue.applying(view, asked?.answering?.takeIf { unmoved }), notifier)
        val after = asked?.let { queue.job(it.answering.id) }
        val moved = before != null && !unmoved
        val reopened = before != null && after != null &&
            before.state.isTerminal && !after.state.isTerminal
        if (moved || reopened) readQueueOnce(transport, notifier)
    }

    /** Clips with a cancel on its way to the Mac, which waits for the node's answer. */
    var cancelling by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * `cancel`: asks the clip's node to stop this one render.
     *
     * The screen offers it only where the Mac said `canCancel`, and only once the person
     * has confirmed it, because the GPU work so far is thrown away. The Mac answers after
     * the node does — up to a minute — with the queue, and the clip in it says how the
     * cancel went: requested, confirmed, too late, or not something that node can do.
     */
    fun cancelRender(id: String, transport: ControlTransport?, notifier: MediaNotifier? = null) {
        if (transport == null || id in cancelling) return
        cancelling = cancelling + id
        val mac = generation
        val sentFrom = asked(id)
        viewModelScope.launch {
            try {
                val view = transport.controlVideoQueue(
                    VideoQueueControlRequest(VideoQueueControlRequest.CANCEL, id),
                )
                if (mac == generation) answered(view, sentFrom, transport, notifier)
            } catch (failure: TransportError) {
                if (mac == generation) {
                    error = failure.message
                    // The Mac may have asked the node before the answer went missing,
                    // and it keeps what the node said on the clip.
                    readQueueOnce(transport, notifier)
                }
            } finally {
                if (mac == generation) cancelling = cancelling - id
            }
        }
    }

    /** `retry` on an item the Mac is unsure about needs to be meant. */
    fun retry(
        id: String,
        confirmNewRender: Boolean,
        transport: ControlTransport?,
        notifier: MediaNotifier? = null,
    ) {
        if (transport == null) return
        val mac = generation
        val sentFrom = asked(id)
        viewModelScope.launch {
            try {
                val view = transport.controlVideoQueue(
                    VideoQueueControlRequest(VideoQueueControlRequest.RETRY, id, confirmNewRender),
                )
                if (mac == generation) answered(view, sentFrom, transport, notifier)
            } catch (failure: TransportError) {
                if (mac == generation) error = failure.message
            }
        }
    }

    fun planImage(transport: ControlTransport?) {
        val request = ImageRequestBuilder.build(imagePrompt, imageModel, imageSize, imageSteps)
            ?: run {
                error = "Type a prompt and pick an image model first."
                return
            }
        if (transport == null) return
        viewModelScope.launch {
            isPlanningImage = true
            try {
                imagePlan = transport.planImage(request)
            } catch (failure: TransportError) {
                error = failure.message
            } finally {
                isPlanningImage = false
            }
        }
    }

    fun planMesh(transport: ControlTransport?) {
        val request = uploaded?.uploadID
            ?.let { MeshRequestBuilder.build(it, meshModel, meshTextureSize) }
            ?: run {
                error = "Pick a picture first: the Mac makes the mesh out of one."
                return
            }
        if (transport == null) return
        viewModelScope.launch {
            isPlanningMesh = true
            try {
                meshPlan = transport.planMesh(request)
            } catch (failure: TransportError) {
                error = failure.message
            } finally {
                isPlanningMesh = false
            }
        }
    }

    // MARK: - Work for the service

    fun imageWork(): MediaJobCenter.Work.Image? {
        val request = ImageRequestBuilder.build(imagePrompt, imageModel, imageSize, imageSteps)
            ?: return null
        return MediaJobCenter.Work.Image(
            request,
            summary = "${imageModel?.name ?: request.modelID.orEmpty()} · ${imageSize}px",
        )
    }

    fun videoWork(): MediaJobCenter.Work.Video? {
        val request = VideoRequest.generate(
            prompt = videoPrompt,
            lane = lane,
            seconds = videoSeconds,
            resolution = videoResolution,
            negativePrompt = videoNegativePrompt,
            uploadID = stillUpload?.uploadID,
        ) ?: return null
        return MediaJobCenter.Work.Video(
            request,
            summary = listOfNotNull(lane?.label, request.seconds?.let { "${it}s" })
                .joinToString(" · "),
        )
    }

    fun meshWork(): MediaJobCenter.Work.Mesh? {
        val request = uploaded?.uploadID
            ?.let { MeshRequestBuilder.build(it, meshModel, meshTextureSize) }
            ?: return null
        return MediaJobCenter.Work.Mesh(
            request,
            summary = "${meshModel?.name ?: "Mesh"} · ${meshTextureSize}px texture",
        )
    }

    /**
     * Sends a picture to the Mac so a render can start from it.
     *
     * The upload is the only way a device may name a picture — a path in a request from
     * a paired phone is refused — and it is full control only, because it spends the
     * owner's disk.
     */
    fun upload(picked: MediaLibrary.Picked, forStill: Boolean, transport: ControlTransport?) {
        if (transport == null) return
        viewModelScope.launch {
            isUploading = true
            try {
                val answer = transport.upload(picked.bytes, picked.contentType, picked.name)
                if (forStill) {
                    stillUpload = answer
                    stillPhotoName = picked.name
                } else {
                    uploaded = answer
                    pickedPhotoName = picked.name
                    meshPlan = null
                }
                message = "Sent ${picked.name ?: "the picture"} to the Mac."
            } catch (failure: TransportError) {
                error = failure.message
            } finally {
                isUploading = false
            }
        }
    }

    fun forgetPicture(forStill: Boolean) {
        if (forStill) {
            stillUpload = null
            stillPhotoName = null
        } else {
            uploaded = null
            pickedPhotoName = null
            meshPlan = null
        }
    }

    fun noteFailedPick(reason: String?) {
        error = reason ?: "That picture could not be read."
    }

    /** Which result is being copied into the phone's photo library right now. */
    var saving by mutableStateOf<String?>(null)
        private set

    /**
     * Copies a finished render onto this phone.
     *
     * Full control only, and that is the Mac's rule rather than this app's: a device
     * paired for chat may fetch the preview images and is answered 403 for the renders
     * themselves, because a phone lent to somebody is not a phone to pull the owner's
     * work onto.
     */
    fun save(
        context: android.content.Context,
        transport: ControlTransport?,
        mediaID: String,
        kind: String,
        title: String?,
        path: String?,
    ) {
        if (transport == null) return
        viewModelScope.launch {
            saving = mediaID
            val name = MediaLibrary.nameFor(kind, title, path)
            MediaLibrary.save(context, transport, mediaID, name, kind)
                .onSuccess { message = "Saved $name to this phone." }
                .onFailure { failure ->
                    error = (failure as? TransportError)?.message
                        ?: failure.message
                        ?: "That file could not be saved."
                }
            saving = null
        }
    }

    fun clearError() {
        error = null
    }

    fun clearMessage() {
        message = null
    }

    /** A different Mac renders different things. Nothing here survives a re-pair. */
    fun reset() {
        stopFollowing()
        videoModels = emptyList()
        imageModels = emptyList()
        meshModels = emptyList()
        generation++
        queue = QueueState.empty
        cancelling = emptySet()
        nextRead?.cancel()
        nextRead = null
        readInFlight = false
        readAgain = false
        endingsSeen.clear()
        reopening.clear()
        announcer.forget()
        routesMedia = false
        autoNote = null
        imagePlan = null
        meshPlan = null
        chosenLaneID = null
        laneChosen = false
        chosenImageModelID = null
        chosenMeshModelID = null
        videoPrompt = ""
        videoTitle = ""
        videoSeconds = null
        videoVariations = 1
        imagePrompt = ""
        sizeState = 1024
        stepsState = 8
        pickedPhotoName = null
        uploaded = null
        stillPhotoName = null
        stillUpload = null
        videoNegativePrompt = ""
        videoResolution = null
        error = null
        message = null
        MediaJobCenter.forget()
    }

    override fun onCleared() {
        stopFollowing()
        super.onCleared()
    }
}

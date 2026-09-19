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
import dev.siliconoptimizer.buddy.transport.VideoModel
import dev.siliconoptimizer.buddy.transport.VideoQueueControlRequest
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
    var videoSeconds by mutableStateOf<Int?>(null)
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
    }

    val secondsChoices: List<Int> get() = VideoRequest.secondsChoices(lane)

    // MARK: - The image draft

    var imagePrompt by mutableStateOf("")
    var imageSize by mutableStateOf(1024)
    var imageSteps by mutableStateOf(8)
    private var chosenImageModelID by mutableStateOf<String?>(null)
    var imagePlan by mutableStateOf<ImagePlan?>(null)
        private set
    var isPlanningImage by mutableStateOf(false)
        private set

    val imageModel: ImageModel?
        get() = imageModels.firstOrNull { it.id == chosenImageModelID } ?: imageModels.firstOrNull()

    fun choose(model: ImageModel) {
        chosenImageModelID = model.id
        imageSteps = ImageRequestBuilder.defaultSteps(model)
        imageSize = ImageRequestBuilder.defaultSize(model)
        // The plan on screen belongs to the model that was there a moment ago.
        imagePlan = null
    }

    // MARK: - The mesh draft

    var meshImagePath by mutableStateOf("")
    var meshTextureSize by mutableStateOf(2048)
    private var chosenMeshModelID by mutableStateOf<String?>(null)
    var meshPlan by mutableStateOf<MeshPlan?>(null)
        private set
    var isPlanningMesh by mutableStateOf(false)
        private set

    /** What the person picked on the phone, shown beside the path the Mac needs. */
    var pickedPhotoName by mutableStateOf<String?>(null)

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
        announcer.notices(previous, next).forEach { notifier?.post(it) }
    }

    /** One pass over every list the Create tab shows. */
    fun refresh(transport: ControlTransport?, notifier: MediaNotifier? = null) {
        if (transport == null) return
        viewModelScope.launch {
            isLoading = true
            val video = async { runCatching { transport.videoModels() }.getOrNull() }
            val image = async { runCatching { transport.imageModels() }.getOrNull() }
            val mesh = async { runCatching { transport.meshModels() }.getOrNull() }
            val queued = async { runCatching { transport.videoQueue() }.getOrNull() }
            // `GET /jev` is a full-control route: a chat-scope phone is answered 403,
            // and an older Mac 404. Either way there is no auto lane to offer, and
            // that is a fact rather than an error to show.
            val jev = async { runCatching { transport.jev() }.getOrNull() }

            val newVideo = video.await()
            val newImage = image.await()
            val newMesh = mesh.await()
            val newQueue = queued.await()
            val newJev = jev.await()

            newVideo?.let { videoModels = it }
            newImage?.let { imageModels = it }
            newMesh?.let { meshModels = it }
            newQueue?.let { update(queue.applying(it), notifier) }
            routesMedia = newJev?.routesMedia == true
            autoNote = when {
                newJev == null -> null
                newJev.routesMedia -> "The Mac picks the model and settings for Auto."
                newJev.advertisesMediaRouting ->
                    "This Mac can route media itself, but that is switched off in its Jev settings."
                else -> null
            }
            if (imageModel != null && chosenImageModelID == null) {
                imageSteps = ImageRequestBuilder.defaultSteps(imageModel)
                imageSize = ImageRequestBuilder.defaultSize(imageModel)
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
     * Follows the queue while the tab is on screen.
     *
     * The `job` events carry the fraction but never a reason for a failure, and only
     * `GET /video/queue` has the Mac's own sentence about that — so this keeps asking,
     * slowly, and the events fill in the movement in between.
     */
    fun startFollowing(
        transport: ControlTransport?,
        notifier: MediaNotifier? = null,
        seconds: Long = 6,
    ) {
        poller?.cancel()
        if (transport == null) return
        poller = viewModelScope.launch {
            while (isActive) {
                runCatching { transport.videoQueue() }.getOrNull()
                    ?.let { update(queue.applying(it), notifier) }
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
    fun apply(event: JobProgress, notifier: MediaNotifier? = null) {
        MediaJobCenter.note(event.kind, event.fraction)
        update(queue.applying(event), notifier)
    }

    // MARK: - Asking for work

    fun enqueueVideo(transport: ControlTransport?, notifier: MediaNotifier? = null) {
        val request = VideoRequest.enqueue(
            prompt = videoPrompt,
            lane = lane,
            title = videoTitle,
            seconds = videoSeconds,
            variations = videoVariations,
        ) ?: run {
            error = "Type a prompt first, and pick a lane that has a machine behind it."
            return
        }
        if (transport == null) return
        viewModelScope.launch {
            try {
                update(queue.applying(transport.enqueueVideos(request)), notifier)
                message = "Added to the queue on the Mac."
                videoPrompt = ""
                tab = Tab.Queue
            } catch (failure: TransportError) {
                error = failure.message
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
        viewModelScope.launch {
            try {
                update(
                    queue.applying(
                        transport.controlVideoQueue(VideoQueueControlRequest(action, id)),
                    ),
                    notifier,
                )
            } catch (failure: TransportError) {
                error = failure.message
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
        viewModelScope.launch {
            try {
                update(
                    queue.applying(
                        transport.controlVideoQueue(
                            VideoQueueControlRequest(
                                VideoQueueControlRequest.RETRY, id, confirmNewRender,
                            ),
                        ),
                    ),
                    notifier,
                )
            } catch (failure: TransportError) {
                error = failure.message
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
        val request = MeshRequestBuilder.build(meshImagePath, meshModel, meshTextureSize)
            ?: run {
                error = "The Mac needs the picture's path on its own disk, starting with /."
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
        val request = VideoRequest.generate(videoPrompt, lane, videoSeconds) ?: return null
        return MediaJobCenter.Work.Video(
            request,
            summary = listOfNotNull(lane?.label, request.seconds?.let { "${it}s" })
                .joinToString(" · "),
        )
    }

    fun meshWork(): MediaJobCenter.Work.Mesh? {
        val request = MeshRequestBuilder.build(meshImagePath, meshModel, meshTextureSize)
            ?: return null
        return MediaJobCenter.Work.Mesh(
            request,
            summary = "${meshModel?.name ?: "Mesh"} · ${meshTextureSize}px texture",
        )
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
        queue = QueueState.empty
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
        meshImagePath = ""
        pickedPhotoName = null
        error = null
        message = null
        MediaJobCenter.forget()
    }

    override fun onCleared() {
        stopFollowing()
        super.onCleared()
    }
}

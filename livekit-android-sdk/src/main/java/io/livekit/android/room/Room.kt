package io.livekit.android.room

import com.github.ajalt.timberkt.Timber
import com.vdurmont.semver4j.Semver
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.livekit.android.ConnectOptions
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.DataTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.util.unpackedTrackLabel
import livekit.LivekitModels
import livekit.LivekitRtc
import org.webrtc.*
import java.nio.ByteBuffer
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Room
@AssistedInject
constructor(
    @Assisted private val connectOptions: ConnectOptions,
    private val engine: RTCEngine,
    private val eglBase: EglBase,
) : RTCEngine.Listener, RemoteParticipant.Listener {
    init {
        engine.listener = this
    }

    enum class State {
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        RECONNECTING;
    }

    inline class Sid(val sid: String)

    var listener: Listener? = null

    var sid: Sid? = null
        private set
    var name: String? = null
        private set
    var state: State = State.DISCONNECTED
        private set
    lateinit var localParticipant: LocalParticipant
        private set
    private val mutableRemoteParticipants = mutableMapOf<String, RemoteParticipant>()
    val remoteParticipants: Map<String, RemoteParticipant>
        get() = mutableRemoteParticipants

    private val mutableActiveSpeakers = mutableListOf<Participant>()
    val activeSpeakers: List<Participant>
        get() = mutableActiveSpeakers

    private var connectContinuation: Continuation<Unit>? = null
    suspend fun connect(url: String, token: String) {
        engine.join(url, token)

        return suspendCoroutine { connectContinuation = it }
    }

    fun disconnect() {
        engine.close()
        state = State.DISCONNECTED
        listener?.onDisconnect(this, null)
    }

    private fun handleParticipantDisconnect(sid: String, participant: RemoteParticipant) {
        val removedParticipant = mutableRemoteParticipants.remove(sid) ?: return
        removedParticipant.tracks.values.forEach { publication ->
            removedParticipant.unpublishTrack(publication.sid)
        }

        listener?.onParticipantDisconnected(this, removedParticipant)
    }

    private fun getOrCreateRemoteParticipant(
        sid: String,
        info: LivekitModels.ParticipantInfo? = null
    ): RemoteParticipant {
        var participant = remoteParticipants[sid]
        if (participant != null) {
            return participant
        }

        participant = if (info != null) {
            RemoteParticipant(info)
        } else {
            RemoteParticipant(sid, null)
        }
        participant.listener = this
        mutableRemoteParticipants[sid] = participant
        return participant
    }

    private fun handleSpeakerUpdate(speakerInfos: List<LivekitRtc.SpeakerInfo>) {
        val speakers = mutableListOf<Participant>()
        val seenSids = mutableSetOf<String>()
        val localParticipant = localParticipant
        speakerInfos.forEach { speakerInfo ->
            val speakerSid = speakerInfo.sid!!
            seenSids.add(speakerSid)

            if (speakerSid == localParticipant?.sid) {
                localParticipant.audioLevel = speakerInfo.level
                speakers.add(localParticipant)
            } else {
                val participant = remoteParticipants[speakerSid]
                if (participant != null) {
                    participant.audioLevel = speakerInfo.level
                    speakers.add(participant)
                }
            }
        }

        if (!seenSids.contains(localParticipant.sid)) {
            localParticipant.audioLevel = 0.0f
        }
        remoteParticipants.values
            .filterNot { seenSids.contains(it.sid) }
            .forEach { it.audioLevel = 0.0f }

        mutableActiveSpeakers.clear()
        mutableActiveSpeakers.addAll(speakers)
        listener?.onActiveSpeakersChanged(speakers, this)
    }

    /**
     * @suppress
     */
    @AssistedFactory
    interface Factory {
        fun create(connectOptions: ConnectOptions): Room
    }

    //----------------------------------- RTCEngine.Listener ------------------------------------//
    /**
     * @suppress
     */
    override fun onJoin(response: LivekitRtc.JoinResponse) {
        Timber.v { "engine did join, version: ${response.serverVersion}" }

        state = State.CONNECTED
        sid = Sid(response.room.sid)
        name = response.room.name

        if (!response.hasParticipant()) {
            listener?.onFailedToConnect(this, RoomException.ConnectException("server didn't return any participants"))
            return
        }

        val lp = LocalParticipant(response.participant, engine)
        lp.listener = this
        localParticipant = lp
        if (response.otherParticipantsList.isNotEmpty()) {
            response.otherParticipantsList.forEach {
                getOrCreateRemoteParticipant(it.sid, it)
            }
        }

        connectContinuation?.resume(Unit)
        connectContinuation = null
    }

    /**
     * @suppress
     */
    override fun onAddTrack(track: MediaStreamTrack, streams: Array<out MediaStream>) {
        if (streams.count() < 0) {
            Timber.i { "add track with empty streams?" }
            return
        }

        val participantSid = streams.first().id
        val trackSid = track.id()
        val participant = getOrCreateRemoteParticipant(participantSid)
        participant.addSubscribedMediaTrack(track, trackSid)
    }

    /**
     * @suppress
     */
    override fun onAddDataChannel(channel: DataChannel) {
        val unpackedTrackLabel = channel.unpackedTrackLabel()
        val (participantSid, trackSid, name) = unpackedTrackLabel
        val participant = getOrCreateRemoteParticipant(participantSid)
        participant.addSubscribedDataTrack(channel, trackSid, name)
    }


    /**
     * @suppress
     */
    override fun onUpdateParticipants(updates: List<LivekitModels.ParticipantInfo>) {
        for (info in updates) {
            val participantSid = info.sid

            if(localParticipant.sid == participantSid) {
                localParticipant.updateFromInfo(info)
                continue
            }

            val isNewParticipant = remoteParticipants.contains(participantSid)
            val participant = getOrCreateRemoteParticipant(participantSid, info)

            if (info.state == LivekitModels.ParticipantInfo.State.DISCONNECTED) {
                handleParticipantDisconnect(participantSid, participant)
            } else if (isNewParticipant) {
                listener?.onParticipantConnected(this, participant)
            } else {
                participant.updateFromInfo(info)
            }
        }
    }

    /**
     * @suppress
     */
    override fun onUpdateSpeakers(speakers: List<LivekitRtc.SpeakerInfo>) {
        handleSpeakerUpdate(speakers)
    }

    /**
     * @suppress
     */
    override fun onDisconnect(reason: String) {
        Timber.v { "engine did disconnect: $reason" }
        listener?.onDisconnect(this, null)
    }

    /**
     * @suppress
     */
    override fun onFailToConnect(error: Exception) {
        listener?.onFailedToConnect(this, error)
    }

    //------------------------------- RemoteParticipant.Listener --------------------------------//
    /**
     * This is called for both Local and Remote participants
     * @suppress
     */
    override fun onMetadataChanged(participant: Participant, prevMetadata: String?) {
        listener?.onMetadataChanged(participant, prevMetadata, this)
    }

    override fun onTrackPublished(publication: TrackPublication, participant: RemoteParticipant) {
        listener?.onTrackPublished(publication,  participant, this)
    }

    override fun onTrackUnpublished(publication: TrackPublication, participant: RemoteParticipant) {
        listener?.onTrackUnpublished(publication,  participant, this)
    }

    override fun onTrackSubscribed(track: Track, publication: TrackPublication, participant: RemoteParticipant) {
        listener?.onTrackSubscribed(track, publication, participant, this)
    }

    override fun onTrackSubscriptionFailed(
        sid: String,
        exception: Exception,
        participant: RemoteParticipant
    ) {
        listener?.onTrackSubscriptionFailed(sid, exception, participant, this)
    }

    override fun onTrackUnsubscribed(
        track: Track,
        publication: TrackPublication,
        participant: RemoteParticipant
    ) {
        listener?.onTrackUnsubscribed(track, publication, participant, this)
    }

    override fun onDataReceived(
        data: ByteBuffer,
        dataTrack: DataTrack,
        participant: RemoteParticipant
    ) {
        listener?.onDataReceived(data, dataTrack, participant, this)
    }

    /**
     * @suppress
     * // TODO(@dl): can this be moved out of Room/SDK?
     */
    fun initVideoRenderer(viewRenderer: SurfaceViewRenderer) {
        viewRenderer.init(eglBase.eglBaseContext, null)
        viewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        viewRenderer.setEnableHardwareScaler(false /* enabled */);
    }

    /**
     * Room Listener, this class provides callbacks that clients should override.
     *
     */
    interface Listener {
        /**
         * Disconnected from room
         */
        fun onDisconnect(room: Room, error: Exception?) {}

        /**
         * When a [RemoteParticipant] joins after the local participant. It will not emit events
         * for participants that are already in the room
         */
        fun onParticipantConnected(room: Room, participant: RemoteParticipant) {}

        /**
         * When a [RemoteParticipant] leaves after the local participant has joined.
         */
        fun onParticipantDisconnected(room: Room, participant: RemoteParticipant) {}

        /**
         * Could not connect to the room
         */
        fun onFailedToConnect(room: Room, error: Exception) {}
//        fun onReconnecting(room: Room, error: Exception) {}
//        fun onReconnect(room: Room) {}

        /**
         * Active speakers changed. List of speakers are ordered by their audio level. loudest
         * speakers first. This will include the [LocalParticipant] too.
         */
        fun onActiveSpeakersChanged(speakers: List<Participant>, room: Room) {}

        // Participant callbacks
        /**
         * Participant metadata is a simple way for app-specific state to be pushed to all users.
         * When RoomService.UpdateParticipantMetadata is called to change a participant's state,
         * this event will be fired for all clients in the room.
         */
        fun onMetadataChanged(Participant: Participant, prevMetadata: String?, room: Room) {}

        /**
         * When a new track is published to room after the local participant has joined. It will
         * not fire for tracks that are already published
         */
        fun onTrackPublished(publication: TrackPublication, participant: RemoteParticipant, room: Room) {}

        /**
         * A [RemoteParticipant] has unpublished a track
         */
        fun onTrackUnpublished(publication: TrackPublication, participant: RemoteParticipant, room: Room) {}

        /**
         * The [LocalParticipant] has subscribed to a new track. This event will always fire as
         * long as new tracks are ready for use.
         */
        fun onTrackSubscribed(track: Track, publication: TrackPublication, participant: RemoteParticipant, room: Room) {}

        /**
         * Could not subscribe to a track
         */
        fun onTrackSubscriptionFailed(sid: String, exception: Exception, participant: RemoteParticipant, room: Room) {}

        /**
         * A subscribed track is no longer available. Clients should listen to this event and ensure
         * the track removes all renderers
         */
        fun onTrackUnsubscribed(track: Track, publications: TrackPublication, participant: RemoteParticipant, room: Room) {}

        /**
         * Message received over a [DataTrack]
         */
        fun onDataReceived(data: ByteBuffer, dataTrack: DataTrack, participant: RemoteParticipant, room: Room) {}
    }
}

sealed class RoomException(message: String? = null, cause: Throwable? = null) :
    Exception(message, cause) {
    class ConnectException(message: String? = null, cause: Throwable? = null) :
        RoomException(message, cause)
}
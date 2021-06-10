/*
 * (C) Copyright 2016 VTT (http://www.vtt.fi)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package fi.vtt.nubomedia.webrtcpeerandroid;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSink;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import fi.vtt.nubomedia.utilitiesandroid.LooperExecutor;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMMediaConfiguration.NBMCameraPosition;

/**
 * The class implements the management of media resources.
 *
 * The implementation is based on PeerConnectionClient.java of package org.appspot.apprtc
 * (please see the copyright notice below)
 */

/*
 * libjingle
 * Copyright 2014 Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
 * EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


final class MediaResourceManager implements NBMWebRTCPeer.Observer {

    private static final String TAG = "[VC][KURENTO][MRM]";
    private static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";

    private static final int HD_VIDEO_WIDTH = 1280;
    private static final int HD_VIDEO_HEIGHT = 720;
    private static final int MAX_VIDEO_WIDTH = 1280;
    private static final int MAX_VIDEO_HEIGHT = 1280;
    private static final int MAX_VIDEO_FPS = 30;

    private static final String MAX_VIDEO_WIDTH_CONSTRAINT = "maxWidth";
    private static final String MIN_VIDEO_WIDTH_CONSTRAINT = "minWidth";

    private static final String MAX_VIDEO_HEIGHT_CONSTRAINT = "maxHeight";
    private static final String MIN_VIDEO_HEIGHT_CONSTRAINT = "minHeight";

    private static final String MAX_VIDEO_FPS_CONSTRAINT = "maxFrameRate";
    private static final String MIN_VIDEO_FPS_CONSTRAINT = "minFrameRate";

    private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
    private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
    private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
    private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";

    private static final String VIDEO_TRACK_ID = "ARDAMSv0";
    private static final String AUDIO_TRACK_ID = "ARDAMSa0";

    private LooperExecutor executor;

    private PeerConnectionFactory factory;

    private MediaConstraints pcConstraints;
    private MediaConstraints videoConstraints;
    private MediaConstraints audioConstraints;
    private MediaConstraints sdpMediaConstraints;

    private boolean videoCallEnabled;
    private boolean renderVideo;
    private boolean videoCapturerStopped;

    private MediaStream localMediaStream;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private VideoSink localRenderer;

    private MediaStream screenshareMediaStream;
    private VideoTrack screenshareVideoTrack;
    private VideoSink screenshareRenderer;

    private NBMWebRTCPeer.NBMPeerConnectionParameters peerConnectionParameters;
    private VideoCapturer videoCapturerNew;
    private NBMCameraPosition currentCameraPosition;
    private CameraEnumerator cameraEnumerator;

    // this is a pretty awkward configuration, we should use a hashmap instead
    // String (clientId) -> VideoSink -> MediaStream -> VideoTrack
    private HashMap<String, VideoSink> remoteVideoSinks;
    private HashMap<VideoSink, MediaStream> remoteVideoMediaStreams;
    private HashMap<MediaStream, VideoTrack> remoteVideoTracks;

    // factor out self stream properties from videoCallEnabled
    // we dont want to send our video just because remote users are
    private boolean generateSelfStream;

    MediaResourceManager(NBMWebRTCPeer.NBMPeerConnectionParameters peerConnectionParameters, LooperExecutor executor, PeerConnectionFactory factory) {
        this.peerConnectionParameters = peerConnectionParameters;
        this.localMediaStream = null;
        this.executor = executor;
        this.factory = factory;
        renderVideo = true;

        remoteVideoSinks = new HashMap<>();
        remoteVideoMediaStreams = new HashMap<>();
        remoteVideoTracks = new HashMap<>();

        videoCallEnabled = peerConnectionParameters.videoCallEnabled;

        generateSelfStream = false;
    }

    /**
     * PeerConnectionFactory.createPeerConnection() w/ MediaConstraints is deprecated
     *
     * We likely do not need this anymore since we can set the "constraints" in the RTCConfiguration object
     *
     * References:
     *  - https://bugs.chromium.org/p/webrtc/issues/detail?id=9239
     *  - https://groups.google.com/g/discuss-webrtc/c/6j-bK_iyHkA
     */
    void createPeerConnectionConstraints() {
        if (pcConstraints != null) {
            Log.i(TAG, "createPeerConnectionConstraints() - pcConstraints already created - do nothing");
            return;
        }

        pcConstraints = new MediaConstraints();

        // enable DTLS for normal calls and disable for loopback calls
        if (peerConnectionParameters.loopback) {
            pcConstraints.optional.add(new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "false"));
        } else {
            pcConstraints.optional.add(new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));
        }

        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("internalSctpDataChannels", "true"));
    }

    void createSDPMediaConstraints() {
        if (sdpMediaConstraints != null) {
            Log.i(TAG, "createSDPMediaConstraints() - sdpMediaConstraints already created - do nothing");
        }

        // Create SDP constraints.
        sdpMediaConstraints = new MediaConstraints();
        sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        if (videoCallEnabled || peerConnectionParameters.loopback) {
            sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        } else {
            sdpMediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));
        }

        sdpMediaConstraints.optional.add(new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));
        sdpMediaConstraints.optional.add(new MediaConstraints.KeyValuePair("internalSctpDataChannels", "true"));
    }

    void createVideoConstraints(boolean selfVideoEnabled, Context appContext) {

        // check if there is a camera on device and disable self stream if not
        if (getNumberOfCameras(appContext) == 0) {
            Log.w(TAG, "No camera on device. Switch to audio only call.");
            generateSelfStream = false;
        } else {
            generateSelfStream = selfVideoEnabled;
        }

        // create video constraints if self stream is enabled
        if (generateSelfStream) {

            videoConstraints = new MediaConstraints();
            int videoWidth = peerConnectionParameters.videoWidth;
            int videoHeight = peerConnectionParameters.videoHeight;

            // video resolution constraints
            if (videoWidth > 0 && videoHeight > 0) {
                videoWidth = Math.min(videoWidth, MAX_VIDEO_WIDTH);
                videoHeight = Math.min(videoHeight, MAX_VIDEO_HEIGHT);
                videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(MIN_VIDEO_WIDTH_CONSTRAINT, Integer.toString(videoWidth)));
                videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(MAX_VIDEO_WIDTH_CONSTRAINT, Integer.toString(videoWidth)));
                videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(MIN_VIDEO_HEIGHT_CONSTRAINT, Integer.toString(videoHeight)));
                videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(MAX_VIDEO_HEIGHT_CONSTRAINT, Integer.toString(videoHeight)));
            }

            // fps constraints
            int videoFps = peerConnectionParameters.videoFps;
            if (videoFps > 0) {
                videoFps = Math.min(videoFps, MAX_VIDEO_FPS);
                videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(MIN_VIDEO_FPS_CONSTRAINT, "0"));
                videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(MAX_VIDEO_FPS_CONSTRAINT, Integer.toString(videoFps)));
            }
        } else {
            videoConstraints = null;
        }
    }

    private int getNumberOfCameras(Context appContext) {
        initializeCameraEnumeratorIfNotBuiltYet(appContext);
        if (cameraEnumerator == null) {
            Log.e(TAG, "could not build cameraEnumerator");
            return 0;
        }

        String[] deviceNames = cameraEnumerator.getDeviceNames();
        return deviceNames.length;
    }

    void createAudioConstraints() {
        audioConstraints = new MediaConstraints();

        // added for audio performance measurements
        if (peerConnectionParameters.noAudioProcessing) {
            Log.d(TAG, "Disabling audio processing");
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"));
        }
    }

    void createMediaConstraints(boolean selfAudioEnabled, boolean selfVideoEnabled, Context appContext) {
        createPeerConnectionConstraints();
        createVideoConstraints(selfVideoEnabled, appContext);
        createAudioConstraints();
        createSDPMediaConstraints();
    }

    MediaConstraints getPcConstraints(){
        return pcConstraints;
    }

    MediaConstraints getSdpMediaConstraints(){
        return sdpMediaConstraints;
    }

    MediaStream getLocalMediaStream() {
        return localMediaStream;
    }

    void stopVideoCapturer() {
        executor.execute(() -> {
            if (videoCapturerNew != null && !videoCapturerStopped) {
                Log.d(TAG, "attempt to stop video capturer");
                try {
                    videoCapturerNew.stopCapture();
                } catch (InterruptedException e) {
                    Log.e(TAG, "stop capture error: " + e.toString());
                }
                videoCapturerStopped = true;
            }
        });
    }

    void disposeVideoSource(boolean skipExecutor) {
        if (skipExecutor) {
            disposeVideoSourceInternal();
        } else {
            executor.execute(this::disposeVideoSourceInternal);
        }
    }

    private void disposeVideoSourceInternal() {
        if (videoSource != null) {
            Log.d(TAG, "disposing video source");
            videoSource.dispose();
            videoSource = null;
        }
    }

    void startVideoCapturer() {
        executor.execute(() -> {
            if (videoCapturerNew != null && videoCapturerStopped) {
                Log.d(TAG, "restarting video capturer");
                startCapturer();
                videoCapturerStopped = false;
            }
        });
    }

    void setLocalAudioTrackEnabled(final boolean enabled) {
        executor.execute(() -> {
            if (localAudioTrack != null) {
                Log.i(TAG, "setting localAudioTrack enabled: " + enabled);
                localAudioTrack.setEnabled(enabled);
            } else {
                Log.e(TAG, "localAudioTrack null - cannot set enabled: " + enabled);
            }
        });
    }

    /**
     * References:
     *  - https://stackoverflow.com/questions/53148497/use-webrtc-videocapturer-without-peerconnection
     */
    private VideoTrack createCapturerVideoTrack(EglBase.Context eglBaseContext, Context appContext) {

        Log.i(TAG, "creating video source + local video track");

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext);
        videoSource = factory.createVideoSource(videoCapturerNew.isScreencast()); // looks like video MediaConstraints are no longer used
        videoCapturerNew.initialize(surfaceTextureHelper, appContext, videoSource.getCapturerObserver());

        startCapturer();

        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        localVideoTrack.setEnabled(renderVideo);

        if (localRenderer != null) {
            localVideoTrack.addSink(localRenderer);
        } else {
            Log.e(TAG, "local renderer null - skip attach");
        }

        return localVideoTrack;
    }

    private void startCapturer() {
        if (videoCapturerNew == null) {
            Log.e(TAG, "capturer null - cannot start");
            return;
        }

        int width = peerConnectionParameters.videoWidth;
        int height = peerConnectionParameters.videoHeight;
        int fps = peerConnectionParameters.videoFps;
        Log.i(TAG, "starting capture with: { width: " + width + ", height: " + height + ", fps: " + fps + " }");
        videoCapturerNew.startCapture(width, height, fps);
    }

    private class AttachRendererTask implements Runnable {

        private VideoSink remoteRenderer;
        private MediaStream remoteStream;
        private boolean isScreenshare;
        private String connectionId;

        private AttachRendererTask(VideoSink remoteRenderer, MediaStream remoteStream, boolean isScreenshare, String connectionId) {
            this.remoteRenderer = remoteRenderer;
            this.remoteStream = remoteStream;
            this.isScreenshare = isScreenshare;
            this.connectionId = connectionId;
        }

        @Override
        public void run() {
            try {
                Log.d(TAG, "attaching renderer to remote stream for " + connectionId);

                if (isScreenshare) {
                    attachScreenshareVideoRenderer();
                } else {
                    attachVideoRenderer();
                }
            } catch (Exception e) {
                Log.e(TAG, "AttachRendererTask error: " + e.toString());
            }
        }

        private void attachScreenshareVideoRenderer() {

            Log.d(TAG, "attachScreenshareVideoRenderer()");

            screenshareMediaStream = remoteStream;

            // check if the remote stream has a video track
            if (screenshareMediaStream.videoTracks.size() != 1) {
                Log.e(TAG, "screenshare videoTracks size != 1");
                return;
            }

            // remove existing renderer if exists
            if (screenshareVideoTrack != null && screenshareRenderer != null) {
                Log.i(TAG, "removing existing screenshare renderer");
                screenshareVideoTrack.removeSink(screenshareRenderer);
            }

            // set new renderer
            screenshareRenderer = remoteRenderer;

            screenshareVideoTrack = screenshareMediaStream.videoTracks.get(0);
            screenshareVideoTrack.setEnabled(renderVideo);
            screenshareVideoTrack.addSink(screenshareRenderer);
        }

        private void attachVideoRenderer() {

            Log.d(TAG, "attachVideoRenderer()");

            // check if the remote stream has a video track
            if (remoteStream.videoTracks.size() != 1) {
                Log.e(TAG, "screenshare videoTracks size != 1");
                return;
            }

            VideoTrack remoteVideoTrack = remoteStream.videoTracks.get(0);
            remoteVideoTrack.setEnabled(renderVideo);

            // check if existing sink is present
            VideoSink existingVideoSink = remoteVideoSinks.get(connectionId);
            if (existingVideoSink != null) {
                MediaStream mediaStream = remoteVideoMediaStreams.get(existingVideoSink);
                if (mediaStream != null) {
                    VideoTrack videoTrack = remoteVideoTracks.get(mediaStream);
                    if (videoTrack != null) {

                        Log.i(TAG, "removing existing video sink");
                        videoTrack.removeSink(existingVideoSink);
                    }
                }
            }

            remoteVideoTrack.addSink(remoteRenderer);

            if (connectionId != null) {
                // add new sink to map
                remoteVideoSinks.put(connectionId, remoteRenderer);
            } else {
                Log.e(TAG, "connectionId null");
            }

            remoteVideoMediaStreams.put(remoteRenderer, remoteStream);
            remoteVideoTracks.put(remoteStream, remoteVideoTrack);
        }
    }

    void attachRendererToRemoteStream(VideoSink remoteRender, MediaStream remoteStream, boolean isScreenshare, String connectionId) {
        Log.d(TAG, "attaching renderer to remote stream for " + connectionId);
        executor.execute(new AttachRendererTask(remoteRender, remoteStream, isScreenshare, connectionId));
    }

    void removeScreenshareVideoRenderer() {
        Log.d(TAG, "removing screenshare video renderer");
        executor.execute(new RemoveScreenshareVideoRendererTask());
    }

    private class RemoveScreenshareVideoRendererTask implements Runnable {

        private RemoveScreenshareVideoRendererTask() {}

        @Override
        public void run() {
            try {
                if (screenshareVideoTrack != null) {
                    screenshareVideoTrack.setEnabled(false);
                } else {
                    Log.e(TAG, "screenshareVideoTrack null");
                    return;
                }

                if (screenshareRenderer != null) {
                    screenshareVideoTrack.removeSink(screenshareRenderer);
                    screenshareRenderer = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "RemoveScreenshareVideoRendererTask error: " + e.toString());
            }
        }
    }

    void removeVideoRenderer(String connectionId) {
        Log.d(TAG, "removing video renderer for: " + connectionId);
        executor.execute(new RemoveVideoRendererTask(connectionId));
    }

    private class RemoveVideoRendererTask implements Runnable {

        private final String connectionId;

        RemoveVideoRendererTask(String connectionId) {
            this.connectionId = connectionId;
        }

        @Override
        public void run() {
            try {
                // detach renderer
                detachRenderer();

                // remove from maps
                onRemoteVideoConnectionClosed(connectionId);
            } catch (Exception e) {
                Log.e(TAG, "RemoveVideoRendererTask error: " + e.toString());
            }
        }

        private void detachRenderer() {

            Log.i(TAG, "detaching renderer for " + connectionId);

            VideoSink videoSink = remoteVideoSinks.get(connectionId);
            if (videoSink == null) {
                Log.e(TAG, "cannot find videoSink for: " + connectionId);
                return;
            }

            MediaStream videoStream = remoteVideoMediaStreams.get(videoSink);
            if (videoStream == null) {
                Log.e(TAG, "cannot find MediaStream for: " + connectionId);
                return;
            }

            VideoTrack videoTrack = remoteVideoTracks.get(videoStream);
            if (videoTrack == null) {
                Log.e(TAG, "cannot find VideoTrack for: " + connectionId);
                return;
            }

            videoTrack.removeSink(videoSink);
        }
    }

    void removeAllVideoRenderers() {
        Log.d(TAG, "removing all video renderers");
        executor.execute(new RemoveAllVideoRenderersTask());
    }

    private class RemoveAllVideoRenderersTask implements Runnable {

        RemoveAllVideoRenderersTask() {}

        @Override
        public void run() {
            try {
                removeSelfVideoRenderer();
                removeRemoteVideoRenderers();
            } catch (Exception e) {
                Log.e(TAG, "RemoveAllVideoRenderersTask error: " + e.toString());
            }
        }

        private void removeRemoteVideoRenderers() {
            // disable all video tracks
            List<VideoTrack> videoTracks = new ArrayList<>(remoteVideoTracks.values());
            for (VideoTrack videoTrack: videoTracks) {
                if (videoTrack != null) {
                    videoTrack.setEnabled(false);
                }
            }

            // remove all renderers
            List<VideoSink> videoSinks = new ArrayList<>(remoteVideoSinks.values());
            for (VideoSink videoSink: videoSinks) {

                // get MediaStream from renderer
                MediaStream mediaStream = remoteVideoMediaStreams.get(videoSink);
                if (mediaStream == null) {
                    Log.e(TAG, "RemoveAllRenderersTask - mediaStream null");
                    continue;
                }

                // get VideoTrack from MediaStream
                VideoTrack videoTrack = remoteVideoTracks.get(mediaStream);
                if (videoTrack == null) {
                    Log.e(TAG, "RemoveAllRenderersTask - videoTrack null");
                    continue;
                }

                videoTrack.removeSink(videoSink);
            }

            remoteVideoMediaStreams.clear();
            remoteVideoTracks.clear();
        }

        private void removeSelfVideoRenderer() {

            if (localVideoTrack == null) {
                Log.d(TAG, "localVideoTrack null, perhaps uncreated");
                return;
            }

            if (localRenderer == null) {
                Log.e(TAG, "localRenderer already null - do nothing");
                return;
            }

            localVideoTrack.removeSink(localRenderer);
            localRenderer = null;
        }
    }

    void reattachSelfVideoRenderer(VideoSink renderer) {
        Log.d(TAG, "re-attaching self video renderer");
        executor.execute(new ReattachSelfVideoRendererTask(renderer));
    }

    private class ReattachSelfVideoRendererTask implements Runnable {

        private final VideoSink renderer;

        ReattachSelfVideoRendererTask(VideoSink renderer) {
            this.renderer = renderer;
        }

        @Override
        public void run() {
            try {

                Log.d(TAG, "ReattachSelfVideoRendererTask start");

                if (localVideoTrack == null) {
                    Log.d(TAG, "localVideoTrack null, perhaps uncreated");
                    return;
                }

                if (localRenderer != null) {
                    Log.e(TAG, "localVideoRenderer not disposed before reattach");
                    localVideoTrack.removeSink(localRenderer);
                }

                // attach new renderer
                localRenderer = renderer;
                localVideoTrack.addSink(localRenderer);
            } catch (Exception e) {
                Log.e(TAG, "ReattachSelfVideoRendererTask error: " + e.toString());
            }
        }
    }

    void reattachAllRemoteVideoRenderers(List<NBMWebRTCPeer.RendererAndStream> renderersAndStreams) {
        Log.d(TAG, "re-attaching all remote video renderers");
        executor.execute(new ReattachAllRemoteVideoRenderersTask(renderersAndStreams));
    }

    private class ReattachAllRemoteVideoRenderersTask implements Runnable {

        private List<NBMWebRTCPeer.RendererAndStream> renderersAndStreams;

        public ReattachAllRemoteVideoRenderersTask(List<NBMWebRTCPeer.RendererAndStream> renderersAndStreams) {
            this.renderersAndStreams = renderersAndStreams;
        }

        @Override
        public void run() {
            try {

                Log.d(TAG, "ReattachAllRemoteVideoRenderersTask start");

                if (renderersAndStreams == null || renderersAndStreams.isEmpty()) {
                    Log.e(TAG, "renderersAndStreams empty - do nothing");
                    return;
                }

                for (NBMWebRTCPeer.RendererAndStream rendererAndStream: renderersAndStreams) {
                    MediaStream stream = rendererAndStream.getStream();
                    VideoSink renderer = rendererAndStream.getRenderer();
                    String connectionId = rendererAndStream.getConnectionId();

                    // check if the remote stream has a video track
                    if (stream.videoTracks.size() != 1) {
                        Log.e(TAG, "stream videoTracks size != 1, skip");
                        continue;
                    }

                    VideoTrack remoteVideoTrack = stream.videoTracks.get(0);
                    remoteVideoTrack.setEnabled(renderVideo);
                    remoteVideoTrack.addSink(renderer);

                    remoteVideoSinks.put(connectionId, renderer);
                    remoteVideoMediaStreams.put(renderer, stream);
                    remoteVideoTracks.put(stream, remoteVideoTrack);
                }
            } catch (Exception e) {
                Log.e(TAG, "ReattachAllRemoteVideoRenderersTask error: " + e.toString());
            }
        }
    }

    void reattachScreenshareVideoRenderer(VideoSink renderer) {
        Log.d(TAG, "re-attaching screenshare renderer");
        executor.execute(new ReattachScreenshareVideoRenderer(renderer));
    }

    private class ReattachScreenshareVideoRenderer implements Runnable {

        private VideoSink renderer;

        public ReattachScreenshareVideoRenderer(VideoSink renderer) {
            this.renderer = renderer;
        }

        @Override
        public void run() {
            try {

                Log.d(TAG, "ReattachScreenshareVideoRenderer start");

                if (screenshareMediaStream == null) {
                    Log.e(TAG, "screenshareMediaStream null - CANNOT reattach renderer");
                    return;
                }

                // check if the remote stream has a video track
                if (screenshareMediaStream.videoTracks.size() != 1) {
                    Log.e(TAG, "screenshare videoTracks size != 1");
                    return;
                }

                if (screenshareVideoTrack != null && screenshareRenderer != null) {
                    Log.e(TAG, "screenshare renderer not disposed before re-attach");
                    screenshareVideoTrack.removeSink(screenshareRenderer);
                }

                // set to new renderer
                screenshareRenderer = renderer;

                screenshareVideoTrack = screenshareMediaStream.videoTracks.get(0);
                screenshareVideoTrack.setEnabled(renderVideo);
                screenshareVideoTrack.addSink(screenshareRenderer);
            } catch (Exception e) {
                Log.e(TAG, "ReattachScreenshareVideoRenderer error: " + e.toString());
            }
        }
    }

    void onRemoteVideoConnectionClosed(String connectionId) {

        Log.i(TAG, "remote video connection closed, remove from maps");

        // remove from sinks
        VideoSink remoteVideoSink = remoteVideoSinks.get(connectionId);
        if (remoteVideoSink == null) {
            Log.e(TAG, "cannot find remoteVideoCallback for: " + connectionId);
            return;
        }

        remoteVideoSinks.remove(connectionId);

        // remove from mediastreams
        MediaStream remoteVideoStream = remoteVideoMediaStreams.get(remoteVideoSink);
        if (remoteVideoStream == null) {
            Log.e(TAG, "cannot find remoteVideoStream for: " + connectionId);
            return;
        }

        remoteVideoMediaStreams.remove(remoteVideoSink);

        // remove from videotracks
        VideoTrack remoteVideoTrack = remoteVideoTracks.get(remoteVideoStream);
        if (remoteVideoTrack == null) {
            Log.e(TAG, "cannot find remoteVideoTrack for: " + connectionId);
            return;
        }

        remoteVideoTracks.remove(remoteVideoStream);
    }

    void createLocalMediaStream(EglBase.Context renderEGLContext, final VideoSink localRenderer, boolean selfAudioEnabled, boolean selfVideoEnabled, Context appContext) {

        Log.i(TAG, "attempt to create local media stream");

        if (factory == null) {
            Log.e(TAG, "peer connection factory is not created - cannot create local media stream");
            return;
        }

        this.localRenderer = localRenderer;

        // Set INFO libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO);

        localMediaStream = factory.createLocalMediaStream("ARDAMS");

        // video call enabled and the device has camera(s)
        if (generateSelfStream) {
            Log.i(TAG, "trying to create video capturer");
            videoCapturerNew = createVideoCapturerNew(appContext, currentCameraPosition);
            if (videoCapturerNew == null) {
                Log.e(TAG, "could not open camera");
                return;
            }

            localMediaStream.addTrack(createCapturerVideoTrack(renderEGLContext, appContext));
        }

        // create audio track
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, factory.createAudioSource(audioConstraints));

        Log.i(TAG, "setting localAudioTrack enabled: " + selfAudioEnabled);
        localAudioTrack.setEnabled(selfAudioEnabled);
        localMediaStream.addTrack(localAudioTrack);
    }

    /**
     * New library apis support local screen captures and "file videos";
     *  We can look to support these feature in the future
     */
    private VideoCapturer createVideoCapturerNew(Context appContext, NBMCameraPosition position) {
        try {
            initializeCameraEnumeratorIfNotBuiltYet(appContext);
            return createCameraCapturer(position);
        } catch (Exception e) {
            Log.e(TAG, "could not create camera capturer, error: " + e.toString());
            return null;
        }
    }

    private void initializeCameraEnumeratorIfNotBuiltYet(Context appContext) {
        try {
            // assume we can use camera2
            if (cameraEnumerator == null) {
                Log.i(TAG, "initializing camera enumerator");
                cameraEnumerator = new Camera2Enumerator(appContext);
            }
        } catch (Exception e) {
            Log.e(TAG, "could not initialize camera enumerator");
        }
    }

    private VideoCapturer createCameraCapturer(NBMCameraPosition position) {

        VideoCapturer capturer = null;
        if (position == NBMCameraPosition.FRONT) {
            capturer = tryToCreateFrontFacingVideoCapturer();
        } else if (position == NBMCameraPosition.BACK) {
            capturer = tryToCreateBackFacingVideoCapturer();
        }

        if (capturer == null) {
            Log.e(TAG, "could not create capturer facing: " + position);
            capturer = tryToCreateAnyFacingVideoCapturer();
        }

        return capturer;
    }

    private VideoCapturer tryToCreateFrontFacingVideoCapturer() {

        if (cameraEnumerator == null) {
            Log.e(TAG, "camera enumerator null");
            return null;
        }

        String[] deviceNames = cameraEnumerator.getDeviceNames();
        Log.i(TAG, "looking for a front facing camera");

        for (String deviceName: deviceNames) {
            if (cameraEnumerator.isFrontFacing(deviceName)) {

                VideoCapturer capturer = cameraEnumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    Log.i(TAG, "created capturer from front facing camera: " + deviceName);
                    return capturer;
                }
            }
        }

        return null;
    }

    private VideoCapturer tryToCreateBackFacingVideoCapturer() {

        if (cameraEnumerator == null) {
            Log.e(TAG, "camera enumerator null");
            return null;
        }

        String[] deviceNames = cameraEnumerator.getDeviceNames();
        Log.i(TAG, "looking for a back facing camera");

        for (String deviceName: deviceNames) {
            if (cameraEnumerator.isBackFacing(deviceName)) {

                VideoCapturer capturer = cameraEnumerator.createCapturer(deviceName, null);
                if (capturer != null) {
                    Log.i(TAG, "created capturer from back facing camera: " + deviceName);
                    return capturer;
                }
            }
        }

        return null;
    }

    private VideoCapturer tryToCreateAnyFacingVideoCapturer() {

        if (cameraEnumerator == null) {
            Log.e(TAG, "camera enumerator null");
            return null;
        }

        String[] deviceNames = cameraEnumerator.getDeviceNames();
        Log.i(TAG, "looking for any facing camera");

        for (String deviceName: deviceNames) {
            VideoCapturer capturer = cameraEnumerator.createCapturer(deviceName, null);
            if (capturer != null) {
                Log.i(TAG, "created capturer from back facing camera: " + deviceName);
                return capturer;
            }
        }

        return null;
    }

    void selectCameraPosition(final NBMCameraPosition position, Context appContext){
        if (!generateSelfStream || videoCapturerNew == null || !hasCameraPosition(position, appContext)) {
            Log.e(TAG, "failed to switch camera, number of cameras: " + getNumberOfCameras(appContext));
            return;
        }

        if (position != currentCameraPosition) {
            executor.execute(() -> {
                Log.d(TAG, "switching camera to position: " + position);
                CameraVideoCapturer cameraVideoCapturer = (CameraVideoCapturer) videoCapturerNew;

                String deviceName = getFirstCameraDeviceName(position);
                if (deviceName != null) {
                    cameraVideoCapturer.switchCamera(null, deviceName);
                } else {
                    Log.e(TAG, "could not get camera device name, cycling through cameras instead");
                    cameraVideoCapturer.switchCamera(null);
                }

                currentCameraPosition = position;
            });
        }
    }

    private String getFirstCameraDeviceName(NBMCameraPosition position) {
        if (cameraEnumerator == null) {
            Log.e(TAG, "camera enumerator null");
            return null;
        }

        String[] deviceNames = cameraEnumerator.getDeviceNames();
        Log.i(TAG, "looking for a front facing camera");

        for (String deviceName: deviceNames) {
            if (position == NBMCameraPosition.ANY) {
                return deviceName;
            } else if (position == NBMCameraPosition.FRONT && cameraEnumerator.isFrontFacing(deviceName)) {
                return deviceName;
            } else if (position == NBMCameraPosition.BACK && cameraEnumerator.isBackFacing(deviceName)) {
                return deviceName;
            }
        }

        return null;
    }

    boolean hasCameraPosition(NBMMediaConfiguration.NBMCameraPosition position, Context appContext) {

        initializeCameraEnumeratorIfNotBuiltYet(appContext);
        if (cameraEnumerator == null) {
            Log.e(TAG, "could not build camera enumerator");
            return false;
        }

        String[] deviceNames = cameraEnumerator.getDeviceNames();

        switch (position) {
            case ANY:
                return deviceNames.length > 0;
            case FRONT:
                for (String deviceName: deviceNames) {
                    if (cameraEnumerator.isFrontFacing(deviceName)) {
                        return true;
                    }
                }
                return false;
            case BACK:
                for (String deviceName: deviceNames) {
                    if (cameraEnumerator.isBackFacing(deviceName)) {
                        return true;
                    }
                }
                return false;
            default:
                return false;
        }
    }

    void close() {
        if (localMediaStream != null) {
            localMediaStream.dispose();
            localMediaStream = null;
        }

        if (videoCapturerNew != null) {
            try {
                videoCapturerNew.stopCapture();
            } catch (InterruptedException e) {
                Log.e(TAG, "could not stop capturer");
            }
            videoCapturerNew.dispose();
            videoCapturerNew = null;
        }

        if (localVideoTrack != null) {
            localVideoTrack = null;
        }

        if (localAudioTrack != null) {
            localAudioTrack = null;
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }
    }

    void clearMaps() {
        remoteVideoTracks.clear();
        remoteVideoMediaStreams.clear();
        remoteVideoSinks.clear();
    }

    void clearScreenshare() {
        screenshareVideoTrack = null;
        screenshareMediaStream = null;
        screenshareRenderer = null;
    }

    // interface methods

    @Override public void onInitialize() {}

    @Override public void onLocalSdpOfferGenerated(SessionDescription localSdpOffer, NBMPeerConnection connection) {}
    @Override public void onLocalSdpAnswerGenerated(SessionDescription localSdpAnswer, NBMPeerConnection connection) {}

    @Override public void onIceCandidate(IceCandidate localIceCandidate, NBMPeerConnection connection) {}
    @Override public void onIceStatusChanged(PeerConnection.IceConnectionState state, NBMPeerConnection connection) {}

    @Override public void onRemoteStreamAdded(MediaStream stream, NBMPeerConnection connection) {}

    @Override
    public void onRemoteStreamRemoved(MediaStream stream, NBMPeerConnection connection) {
        remoteVideoTracks.remove(stream);
    }

    @Override public void onPeerConnectionError(String error) {}

    @Override public void onDataChannel(DataChannel dataChannel, NBMPeerConnection connection) {}
    @Override public void onBufferedAmountChange(long l, NBMPeerConnection connection, DataChannel channel) {}
    @Override public void onStateChange(NBMPeerConnection connection, DataChannel channel) {}
    @Override public void onMessage(DataChannel.Buffer buffer, NBMPeerConnection connection, DataChannel channel) {}
}

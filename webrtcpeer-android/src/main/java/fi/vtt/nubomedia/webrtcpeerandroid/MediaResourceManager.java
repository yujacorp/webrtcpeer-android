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

import android.util.Log;
import org.webrtc.CameraEnumerationAndroid;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.Logging;
import org.webrtc.MediaCodecVideoEncoder;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturerAndroid;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.EnumSet;
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

    private static final int numberOfCameras = CameraEnumerationAndroid.getDeviceCount();

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
    private boolean videoSourceStopped;

    private MediaStream localMediaStream;
    private VideoSource videoSource;
    private VideoTrack localVideoTrack;
    private VideoRenderer.Callbacks localRender;
    private VideoRenderer localVideoRenderer;

    private MediaStream screenshareMediaStream;
    private VideoTrack screenshareVideoTrack;
    private VideoRenderer.Callbacks screenshareRenderer;
    private VideoRenderer screenshareVideoRenderer;

    private NBMWebRTCPeer.NBMPeerConnectionParameters peerConnectionParameters;
    private VideoCapturerAndroid videoCapturer;
    private NBMCameraPosition currentCameraPosition;

    // Callbacks -> VideoRenderer -> MediaStream -> VideoTrack
    private HashMap<String, VideoRenderer.Callbacks> remoteVideoCallbacks;
    private HashMap<VideoRenderer.Callbacks, VideoRenderer> remoteVideoRenderers;
    private HashMap<VideoRenderer, MediaStream> remoteVideoMediaStreams;
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

        remoteVideoCallbacks = new HashMap<>();
        remoteVideoRenderers = new HashMap<>();
        remoteVideoMediaStreams = new HashMap<>();
        remoteVideoTracks = new HashMap<>();

        videoCallEnabled = peerConnectionParameters.videoCallEnabled;

        generateSelfStream = false;
    }

    void createPeerConnectionConstraints() {
        if (pcConstraints != null) {
            Log.i(TAG, "createPeerConnectionConstraints() - pcConstraints already created - do nothing");
            return;
        }

        // Create peer connection constraints.
        pcConstraints = new MediaConstraints();

        // Enable DTLS for normal calls and disable for loopback calls.
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

    void createVideoConstraints(boolean selfVideoEnabled) {

        // check if there is a camera on device and disable self stream if not
        if (numberOfCameras == 0) {
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

            // If VP8 HW video encoder is supported and video resolution is not
            // specified force it to HD.
            if ((videoWidth == 0 || videoHeight == 0) && peerConnectionParameters.videoCodecHwAcceleration && MediaCodecVideoEncoder.isVp8HwSupported()) {
                videoWidth = HD_VIDEO_WIDTH;
                videoHeight = HD_VIDEO_HEIGHT;
            }

            // Add video resolution constraints.
            if (videoWidth > 0 && videoHeight > 0) {
                videoWidth = Math.min(videoWidth, MAX_VIDEO_WIDTH);
                videoHeight = Math.min(videoHeight, MAX_VIDEO_HEIGHT);
                videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(MIN_VIDEO_WIDTH_CONSTRAINT, Integer.toString(videoWidth)));
                videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(MAX_VIDEO_WIDTH_CONSTRAINT, Integer.toString(videoWidth)));
                videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(MIN_VIDEO_HEIGHT_CONSTRAINT, Integer.toString(videoHeight)));
                videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair(MAX_VIDEO_HEIGHT_CONSTRAINT, Integer.toString(videoHeight)));
            }

            // Add fps constraints.
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

    void createMediaConstraints(boolean selfAudioEnabled, boolean selfVideoEnabled) {
        createPeerConnectionConstraints();
        createVideoConstraints(selfVideoEnabled);
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

    void stopVideoSource() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (videoSource != null && !videoSourceStopped) {
                    Log.d(TAG, "Stop video source.");
                    videoSource.stop();
                    videoSourceStopped = true;
                }
            }
        });
    }

    void disposeVideoSource() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (videoSource != null) {
                    Log.d(TAG, "Dispose video source.");
                    videoSource.dispose();
                    videoSource = null;
                }
            }
        });
    }

    void startVideoSource() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                if (videoSource != null && videoSourceStopped) {
                    Log.d(TAG, "Restart video source.");
                    videoSource.restart();
                    videoSourceStopped = false;
                }
            }
        });
    }

    private VideoTrack createCapturerVideoTrack(VideoCapturerAndroid capturer) {

        Log.i(TAG, "creating local VideoSource & VideoRenderer");

        videoSource = factory.createVideoSource(capturer, videoConstraints);
        localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        localVideoTrack.setEnabled(renderVideo);

        localVideoRenderer = new VideoRenderer(localRender);
        localVideoTrack.addRenderer(localVideoRenderer);
        return localVideoTrack;
    }

    private class AttachRendererTask implements Runnable {

        private VideoRenderer.Callbacks remoteRender;
        private MediaStream remoteStream;
        private boolean isSharescreen;
        private String connectionId;

        private AttachRendererTask(VideoRenderer.Callbacks remoteRender, MediaStream remoteStream) {
            this.remoteRender = remoteRender;
            this.remoteStream = remoteStream;
            isSharescreen = false;
            connectionId = null;
        }

        private AttachRendererTask(VideoRenderer.Callbacks remoteRender, MediaStream remoteStream, boolean isSharescreen, String connectionId) {
            this.remoteRender = remoteRender;
            this.remoteStream = remoteStream;
            this.isSharescreen = isSharescreen;
            this.connectionId = connectionId;
        }

        @Override
        public void run() {
            Log.d(TAG, "Attaching VideoRenderer to remote stream (" + remoteStream + ")");

            if (isSharescreen) {
                attachScreenshareVideoRenderer();
            } else {
                attachVideoRenderer();
            }
        }

        private void attachScreenshareVideoRenderer() {

            Log.d(TAG, "attachScreenshareVideoRenderer()");

            screenshareRenderer = remoteRender;
            screenshareMediaStream = remoteStream;

            // check if the remote stream has a video track
            if (screenshareMediaStream.videoTracks.size() != 1) {
                Log.e(TAG, "screenshare videoTracks size != 1");
                return;
            }

            if (screenshareVideoTrack != null && screenshareVideoRenderer != null) {
                screenshareVideoTrack.removeRenderer(screenshareVideoRenderer);
            }

            screenshareVideoTrack = screenshareMediaStream.videoTracks.get(0);
            screenshareVideoTrack.setEnabled(renderVideo);

            screenshareVideoRenderer = new VideoRenderer(screenshareRenderer);
            screenshareVideoTrack.addRenderer(screenshareVideoRenderer);

            Log.d(TAG, "attached");
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

            VideoRenderer videoRenderer = remoteVideoRenderers.get(remoteRender);
            if (videoRenderer != null) {
                MediaStream mediaStream = remoteVideoMediaStreams.get(videoRenderer);
                if (mediaStream != null) {
                    VideoTrack videoTrack = remoteVideoTracks.get(mediaStream);
                    if (videoTrack != null) {
                        videoTrack.removeRenderer(videoRenderer);
                    }
                }
            }

            VideoRenderer newVideoRenderer = new VideoRenderer(remoteRender);
            remoteVideoTrack.addRenderer(newVideoRenderer);

            if (connectionId != null) {
                remoteVideoCallbacks.put(connectionId, remoteRender);
            } else {
                Log.e(TAG, "connectionId null");
            }

            remoteVideoRenderers.put(remoteRender, newVideoRenderer);
            remoteVideoMediaStreams.put(newVideoRenderer, remoteStream);
            remoteVideoTracks.put(remoteStream, remoteVideoTrack);

            Log.d(TAG, "attached");
        }
    }

    // unused
    void attachRendererToRemoteStream(VideoRenderer.Callbacks remoteRender, MediaStream remoteStream) {
        Log.d(TAG, "Schedule attaching VideoRenderer to remote stream (" + remoteStream + ")");
        executor.execute(new AttachRendererTask(remoteRender, remoteStream));
    }

    void attachRendererToRemoteStream(VideoRenderer.Callbacks remoteRender, MediaStream remoteStream, boolean isScreenshare, String connectionId) {
        Log.d(TAG, "Schedule attaching VideoRenderer to remote stream (" + remoteStream + ")");
        executor.execute(new AttachRendererTask(remoteRender, remoteStream, isScreenshare, connectionId));
    }

    void removeScreenshareVideoRenderer() {
        Log.d(TAG, "Removing screenshare video renderer");
        executor.execute(new RemoveScreenshareVideoRendererTask());
    }

    private class RemoveScreenshareVideoRendererTask implements Runnable {

        private RemoveScreenshareVideoRendererTask() {}

        @Override
        public void run() {

            if (screenshareVideoTrack != null) {
                screenshareVideoTrack.setEnabled(false);
            } else {
                Log.e(TAG, "screenshareVideoTrack null");
                return;
            }

            if (screenshareVideoRenderer != null) {
                screenshareVideoTrack.removeRenderer(screenshareVideoRenderer);
                screenshareVideoRenderer.dispose();
                screenshareVideoRenderer = null;
            }
        }
    }

    void removeVideoRenderer(String connectionId) {
        Log.d(TAG, "Removing video renderer for: " + connectionId);
        executor.execute(new RemoveVideoRendererTask(connectionId));
    }

    private class RemoveVideoRendererTask implements Runnable {

        private String connectionId;

        RemoveVideoRendererTask(String connectionId) {
            this.connectionId = connectionId;
        }

        @Override
        public void run() {

            // detach renderer
            detachRenderer();

            // remove from maps
            onRemoteVideoConnectionClosed(connectionId);
        }

        private void detachRenderer() {

            Log.i(TAG, "Detaching renderer");

            VideoRenderer.Callbacks callbacks = remoteVideoCallbacks.get(connectionId);
            if (callbacks == null) {
                Log.e(TAG, "cannot find Callbacks for: " + connectionId);
                return;
            }

            VideoRenderer videoRenderer = remoteVideoRenderers.get(callbacks);
            if (videoRenderer == null) {
                Log.e(TAG, "cannot find VideoRenderer for: " + connectionId);
                return;
            }

            MediaStream videoStream = remoteVideoMediaStreams.get(videoRenderer);
            if (videoStream == null) {
                Log.e(TAG, "cannot find MediaStream for: " + connectionId);
                return;
            }

            VideoTrack videoTrack = remoteVideoTracks.get(videoStream);
            if (videoTrack == null) {
                Log.e(TAG, "cannot find VideoTrack for: " + connectionId);
                return;
            }

            videoTrack.removeRenderer(videoRenderer);
            videoRenderer.dispose();

            Log.i(TAG, "Finish detach, renderer disposed");
        }
    }

    void removeAllVideoRenderers() {
        Log.d(TAG, "Removing all video renderers");
        executor.execute(new RemoveAllVideoRenderersTask());
    }

    private class RemoveAllVideoRenderersTask implements Runnable {

        RemoveAllVideoRenderersTask() {}

        @Override
        public void run() {
            removeSelfVideoRenderer();
            removeRemoteVideoRenderers();
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
            List<VideoRenderer> videoRenderers = new ArrayList<>(remoteVideoRenderers.values());
            for (VideoRenderer videoRenderer: videoRenderers) {

                // get MediaStream from renderer
                MediaStream mediaStream = remoteVideoMediaStreams.get(videoRenderer);
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

                videoTrack.removeRenderer(videoRenderer);
                videoRenderer.dispose();
            }

            remoteVideoRenderers.clear();
            remoteVideoMediaStreams.clear();
            remoteVideoTracks.clear();
        }

        private void removeSelfVideoRenderer() {

            if (localVideoTrack == null) {
                Log.d(TAG, "localVideoTrack null, perhaps uncreated");
                return;
            }

            if (localVideoRenderer == null) {
                Log.e(TAG, "localVideoRenderer already null - do nothing");
                return;
            }

            localVideoTrack.removeRenderer(localVideoRenderer);
            localVideoRenderer.dispose();
            localVideoRenderer = null;
            localRender = null;
        }
    }

    void reattachSelfVideoRenderer(VideoRenderer.Callbacks renderer) {
        Log.d(TAG, "re-attaching self video renderer");
        executor.execute(new ReattachSelfVideoRendererTask(renderer));
    }

    private class ReattachSelfVideoRendererTask implements Runnable {

        private VideoRenderer.Callbacks renderer;

        ReattachSelfVideoRendererTask(VideoRenderer.Callbacks renderer) {
            this.renderer = renderer;
        }

        @Override
        public void run() {
            Log.d(TAG, "ReattachSelfVideoRendererTask start");

            localRender = renderer;

            if (localVideoTrack == null) {
                Log.d(TAG, "localVideoTrack null, perhaps uncreated");
                return;
            }

            if (localVideoRenderer != null) {
                Log.e(TAG, "localVideoRenderer not disposed before reattach");
                localVideoTrack.removeRenderer(localVideoRenderer);
                localVideoRenderer.dispose();
            }

            localVideoRenderer = new VideoRenderer(localRender);
            localVideoTrack.addRenderer(localVideoRenderer);

            Log.d(TAG, "self re-attached");
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

            Log.d(TAG, "ReattachAllRemoteVideoRenderersTask start");

            if (renderersAndStreams == null || renderersAndStreams.isEmpty()) {
                Log.e(TAG, "renderersAndStreams empty - do nothing");
                return;
            }

            for (NBMWebRTCPeer.RendererAndStream rendererAndStream: renderersAndStreams) {
                MediaStream stream = rendererAndStream.getStream();
                VideoRenderer.Callbacks renderer = rendererAndStream.getRenderer();
                String connectionId = rendererAndStream.getConnectionId();

                // check if the remote stream has a video track
                if (stream.videoTracks.size() != 1) {
                    Log.e(TAG, "stream videoTracks size != 1, skip");
                    continue;
                }

                VideoTrack remoteVideoTrack = stream.videoTracks.get(0);
                remoteVideoTrack.setEnabled(renderVideo);

                VideoRenderer newVideoRenderer = new VideoRenderer(renderer);
                remoteVideoTrack.addRenderer(newVideoRenderer);

                remoteVideoCallbacks.put(connectionId, renderer);
                remoteVideoRenderers.put(renderer, newVideoRenderer);
                remoteVideoMediaStreams.put(newVideoRenderer, stream);
                remoteVideoTracks.put(stream, remoteVideoTrack);
            }

            Log.d(TAG, "streams re-attached");
        }
    }

    void reattachScreenshareVideoRenderer(VideoRenderer.Callbacks renderer) {
        Log.d(TAG, "re-attaching screenshare renderer");
        executor.execute(new ReattachScreenshareVideoRenderer(renderer));
    }

    private class ReattachScreenshareVideoRenderer implements Runnable {

        private VideoRenderer.Callbacks renderer;

        public ReattachScreenshareVideoRenderer(VideoRenderer.Callbacks renderer) {
            this.renderer = renderer;
        }

        @Override
        public void run() {
            Log.d(TAG, "ReattachScreenshareVideoRenderer start");

            screenshareRenderer = renderer;

            if (screenshareMediaStream == null) {
                Log.e(TAG, "screenshareMediaStream null - CANNOT reattach renderer");
                return;
            }

            // check if the remote stream has a video track
            if (screenshareMediaStream.videoTracks.size() != 1) {
                Log.e(TAG, "screenshare videoTracks size != 1");
                return;
            }

            if (screenshareVideoTrack != null && screenshareVideoRenderer != null) {
                Log.e(TAG, "screenshare renderer not disposed before re-attach");
                screenshareVideoTrack.removeRenderer(screenshareVideoRenderer);
                screenshareVideoRenderer.dispose();
                screenshareVideoRenderer = null;
            }

            screenshareVideoTrack = screenshareMediaStream.videoTracks.get(0);
            screenshareVideoTrack.setEnabled(renderVideo);

            screenshareVideoRenderer = new VideoRenderer(screenshareRenderer);
            screenshareVideoTrack.addRenderer(screenshareVideoRenderer);

            Log.d(TAG, "screenshare re-attached");
        }
    }

    void onRemoteVideoConnectionClosed(String connectionId) {

        Log.i(TAG, "remote video connection closed, remove from maps");

        // remove from callbacks
        VideoRenderer.Callbacks remoteVideoCallback = remoteVideoCallbacks.get(connectionId);
        if (remoteVideoCallback == null) {
            Log.e(TAG, "cannot find remoteVideoCallback for: " + connectionId);
            return;
        }

        remoteVideoCallbacks.remove(connectionId);

        // remove from renderers
        VideoRenderer remoteVideoRenderer = remoteVideoRenderers.get(remoteVideoCallback);
        if (remoteVideoRenderer == null) {
            Log.e(TAG, "cannot find remoteVideoRenderer for: " + connectionId);
            return;
        }

        remoteVideoRenderers.remove(remoteVideoCallback);

        // remove from mediastreams
        MediaStream remoteVideoStream = remoteVideoMediaStreams.get(remoteVideoRenderer);
        if (remoteVideoStream == null) {
            Log.e(TAG, "cannot find remoteVideoStream for: " + connectionId);
            return;
        }

        remoteVideoMediaStreams.remove(remoteVideoRenderer);

        // remove from videotracks
        VideoTrack remoteVideoTrack = remoteVideoTracks.get(remoteVideoStream);
        if (remoteVideoTrack == null) {
            Log.e(TAG, "cannot find remoteVideoTrack for: " + connectionId);
            return;
        }

        remoteVideoTracks.remove(remoteVideoStream);

        Log.i(TAG, "removal finished");
    }

    void createLocalMediaStream(Object renderEGLContext, final VideoRenderer.Callbacks localRender) {
        if (factory == null) {
            Log.e(TAG, "Peerconnection factory is not created");
            return;
        }
        this.localRender = localRender;
        if (generateSelfStream) {
            factory.setVideoHwAccelerationOptions(renderEGLContext, renderEGLContext);
        }

        // Set default WebRTC tracing and INFO libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
        Logging.enableTracing("logcat:", EnumSet.of(Logging.TraceLevel.TRACE_DEFAULT), Logging.Severity.LS_INFO);

        localMediaStream = factory.createLocalMediaStream("ARDAMS");

        // If video call is enabled and the device has camera(s)
        if (generateSelfStream) {
            String cameraDeviceName; // = CameraEnumerationAndroid.getDeviceName(0);
            String frontCameraDeviceName = CameraEnumerationAndroid.getNameOfFrontFacingDevice();
            String backCameraDeviceName = CameraEnumerationAndroid.getNameOfBackFacingDevice();

            // If current camera is set to front and the device has one
            if (currentCameraPosition==NBMCameraPosition.FRONT && frontCameraDeviceName!=null) {
                cameraDeviceName = frontCameraDeviceName;
            }
            // If current camera is set to back and the device has one
            else if (currentCameraPosition==NBMCameraPosition.BACK && backCameraDeviceName!=null) {
                cameraDeviceName = backCameraDeviceName;
            }
            // If current camera is set to any then we pick the first camera of the device, which
            // should be a back-facing camera according to libjingle API
            else {
                cameraDeviceName = CameraEnumerationAndroid.getDeviceName(0);
                currentCameraPosition = NBMCameraPosition.BACK;
            }

            Log.d(TAG, "Opening camera: " + cameraDeviceName);
            videoCapturer = VideoCapturerAndroid.create(cameraDeviceName, null);
            if (videoCapturer == null) {
                Log.d(TAG, "Error while opening camera");
                return;
            }
            localMediaStream.addTrack(createCapturerVideoTrack(videoCapturer));
        }

        // Create audio track
        localMediaStream.addTrack(factory.createAudioTrack(AUDIO_TRACK_ID, factory.createAudioSource(audioConstraints)));

        Log.d(TAG, "Local media stream created.");
    }

    void selectCameraPosition(final NBMCameraPosition position){
        if (!generateSelfStream || videoCapturer == null || !hasCameraPosition(position)) {
            Log.e(TAG, "Failed to switch camera. Video: " + generateSelfStream + ". . Number of cameras: " + numberOfCameras);
            return;
        }
        if (position != currentCameraPosition) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "Switch camera");
                    videoCapturer.switchCamera(null);
                    currentCameraPosition = position;
                }
            });
        }
    }

    void switchCamera(){
        if (!generateSelfStream || videoCapturer == null) {
            Log.e(TAG, "Failed to switch camera. Video: " + generateSelfStream + ". . Number of cameras: " + numberOfCameras);
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Switch camera");
                videoCapturer.switchCamera(null);
                if (currentCameraPosition==NBMCameraPosition.BACK) {
                    currentCameraPosition = NBMCameraPosition.FRONT;
                } else {
                    currentCameraPosition = NBMCameraPosition.BACK;
                }
            }
        });
    }

    void setVideoEnabled(final boolean enable) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                renderVideo = enable;
                if (localVideoTrack != null) {
                    localVideoTrack.setEnabled(renderVideo);
                }
                for (VideoTrack tv: remoteVideoTracks.values()) {
                    tv.setEnabled(renderVideo);
                }
            }
        });
    }

    boolean getVideoEnabled() {
        return renderVideo;
    }

    boolean hasCameraPosition(NBMMediaConfiguration.NBMCameraPosition position) {
        boolean retMe = false;

        String backName = CameraEnumerationAndroid.getNameOfBackFacingDevice();
        String frontName = CameraEnumerationAndroid.getNameOfFrontFacingDevice();

        if (position == NBMMediaConfiguration.NBMCameraPosition.ANY &&
                (backName != null || frontName != null)){
            retMe = true;
        } else if (position == NBMMediaConfiguration.NBMCameraPosition.BACK &&
                backName != null){
            retMe = true;

        } else if (position == NBMMediaConfiguration.NBMCameraPosition.FRONT &&
                frontName != null){
            retMe = true;
        }

        return retMe;
    }

    void close() {
        if (localMediaStream != null) {
            localMediaStream.dispose();
            localMediaStream = null;
        }

        if (videoCapturer != null) {
            videoCapturer.dispose();
            videoCapturer = null;
        }

        if (localVideoTrack != null) {
            localVideoTrack = null;
        }
    }

    @Override
    public void onInitialize() {}

    @Override
    public void onLocalSdpOfferGenerated(SessionDescription localSdpOffer, NBMPeerConnection connection) {
    }

    @Override
    public void onLocalSdpAnswerGenerated(SessionDescription localSdpAnswer, NBMPeerConnection connection) {
    }

    @Override
    public void onIceCandidate(IceCandidate localIceCandidate, NBMPeerConnection connection) {
    }

    @Override
    public void onIceStatusChanged(PeerConnection.IceConnectionState state, NBMPeerConnection connection) {
    }

    @Override
    public void onRemoteStreamAdded(MediaStream stream, NBMPeerConnection connection) {
    }

    @Override
    public void onRemoteStreamRemoved(MediaStream stream, NBMPeerConnection connection) {
        remoteVideoTracks.remove(stream);
    }

    @Override
    public void onPeerConnectionError(String error) {
    }

    @Override
    public void onDataChannel(DataChannel dataChannel, NBMPeerConnection connection) {

    }

    @Override
    public void onBufferedAmountChange(long l, NBMPeerConnection connection, DataChannel channel) {

    }

    @Override
    public void onStateChange(NBMPeerConnection connection, DataChannel channel) {

    }

    @Override
    public void onMessage(DataChannel.Buffer buffer, NBMPeerConnection connection, DataChannel channel) {

    }
}

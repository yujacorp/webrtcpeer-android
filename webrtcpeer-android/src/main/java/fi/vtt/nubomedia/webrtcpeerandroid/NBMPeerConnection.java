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

import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.vtt.nubomedia.utilitiesandroid.LooperExecutor;

/**
 * Wrapper for PeerConnection
 */
public class NBMPeerConnection implements PeerConnection.Observer, SdpObserver {

    private static final String TAG = "[VC][Kurento][PC]";
    private static final String VIDEO_CODEC_PARAM_START_BITRATE = "x-google-start-bitrate";
    private static final String AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate";

    private PeerConnection pc;
    private String connectionId;
    private LooperExecutor executor;
    private SessionDescription localSdp; // either offer or answer SDP
    private boolean preferIsac;
    private boolean videoCallEnabled;
    private boolean preferH264;
    private boolean isInitiator;
    private boolean isScreenshareConnection;
    private HashMap<String, ObservedDataChannel> observedDataChannels;
    private LinkedList<IceCandidate> queuedRemoteCandidates;
    MediaConstraints sdpMediaConstraints = null;
    Vector<NBMWebRTCPeer.Observer> observers;
    NBMWebRTCPeer.NBMPeerConnectionParameters peerConnectionParameters;

    // UNUSED
    private class ObservedDataChannel implements DataChannel.Observer {
        private DataChannel channel;

        public ObservedDataChannel(String label, DataChannel.Init init) {
            channel = pc.createDataChannel(label, init);
            if (channel != null) {
                channel.registerObserver(this);
                Log.i(TAG, "Created data channel with Id: " + label);
            }
            else {
                Log.e(TAG, "Failed to create data channel with Id: " + label);
            }
        }

        public DataChannel getChannel() {
            return channel;
        }

        @Override
        public void onBufferedAmountChange(long l) {
            Log.i(TAG, "[ObservedDataChannel] NBMPeerConnection onBufferedAmountChange");
            for (NBMWebRTCPeer.Observer observer: observers) {
                observer.onBufferedAmountChange(l, NBMPeerConnection.this, channel);
            }
        }

        @Override
        public void onStateChange(){
            for (NBMWebRTCPeer.Observer observer: observers) {
                observer.onStateChange(NBMPeerConnection.this, channel);
            }
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            Log.i(TAG, "[ObservedDataChannel] NBMPeerConnection onMessage");
            for (NBMWebRTCPeer.Observer observer: observers) {
                observer.onMessage(buffer, NBMPeerConnection.this, channel);
            }
        }
    }

    public NBMPeerConnection(String connectionId, boolean preferIsac, boolean videoCallEnabled, boolean preferH264,
                             LooperExecutor executor, NBMWebRTCPeer.NBMPeerConnectionParameters params) {

        this.connectionId = connectionId;
        observers = new Vector<>();
        this.preferIsac = preferIsac;
        this.videoCallEnabled = videoCallEnabled;
        this.preferH264 = preferH264;
        this.executor = executor;
        isInitiator = false;
        peerConnectionParameters = params;
        queuedRemoteCandidates = new LinkedList<>();
        observedDataChannels = new HashMap<>();
    }

    public NBMPeerConnection(String connectionId, boolean preferIsac, boolean videoCallEnabled, boolean preferH264,
                             LooperExecutor executor, NBMWebRTCPeer.NBMPeerConnectionParameters params, boolean isScreenshareConnection) {
        this(connectionId, preferIsac, videoCallEnabled, preferH264, executor, params);
        this.isScreenshareConnection = isScreenshareConnection;
    }

    public DataChannel createDataChannel(String label, DataChannel.Init init) {
        ObservedDataChannel dataChannel = new ObservedDataChannel(label, init);
        observedDataChannels.put(label, dataChannel);
        return dataChannel.getChannel();
    }

    public HashMap<String, DataChannel> getDataChannels() {
        HashMap<String, DataChannel> channels = new HashMap<>();
        for (HashMap.Entry<String, ObservedDataChannel> entry : observedDataChannels.entrySet()) {
            String key = entry.getKey();
            ObservedDataChannel value = entry.getValue();
            channels.put(key, value.getChannel());
        }
        return channels;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public DataChannel getDataChannel(String dataChannelId){
        ObservedDataChannel channel = this.observedDataChannels.get(dataChannelId);
        if (channel == null) {
            return null;
        } else {
            return channel.getChannel();
        }
    }

    public void setPc(PeerConnection pc) {
        this.pc = pc;
    }

    public PeerConnection getPc() {
        return pc;
    }

    public void addObserver(NBMWebRTCPeer.Observer observer) {
        observers.add(observer);
    }

    @Override
    public void onDataChannel(DataChannel dataChannel) {
        Log.i(TAG, "[datachannel] Peer opened data channel");
        for (NBMWebRTCPeer.Observer observer: observers) {
            observer.onDataChannel(dataChannel, NBMPeerConnection.this);
        }
    }

    @Override
    public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        Log.d(TAG, "signaling state change : " + signalingState + " for " + connectionId);
    }

    @Override
    public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
        Log.d(TAG, "ice connection state change: " + newState + " for " + connectionId);

        for (NBMWebRTCPeer.Observer o : observers) {
            o.onIceStatusChanged(newState, this);
        }
    }

    @Override
    public void onIceConnectionReceivingChange(boolean receiving) {
        Log.d(TAG, "IceConnectionReceiving changed to " + receiving);
    }

    @Override
    public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        Log.d(TAG, "IceGatheringState: " + iceGatheringState);
    }

    @Override
    public void onIceCandidate(final IceCandidate iceCandidate) {
        executor.execute(() -> {
            for (NBMWebRTCPeer.Observer observer : observers) {
                observer.onIceCandidate(iceCandidate, NBMPeerConnection.this);
            }
        });
    }

    @Override
    public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
        // new from upgraded webrtc dependency, currently unused
        Log.i(TAG, "onIceCandidatesRemoved() - currently unused");
    }

    @Override
    public void onAddStream(final MediaStream mediaStream) {
        executor.execute(() -> {
            if (pc == null) {
                return;
            }
            if (mediaStream.audioTracks.size() > 1 || mediaStream.videoTracks.size() > 1) {
                for (NBMWebRTCPeer.Observer observer : observers) {
                    observer.onPeerConnectionError("Weird-looking stream: " + mediaStream);
                }
                return;
            }
            for (NBMWebRTCPeer.Observer observer : observers) {
                observer.onRemoteStreamAdded(mediaStream, NBMPeerConnection.this);
            }
        });
    }

    @Override
    public void onRemoveStream(final MediaStream mediaStream) {
        executor.execute(() -> {
            if (pc == null) {
                return;
            }
            if (mediaStream.audioTracks.size() > 1 || mediaStream.videoTracks.size() > 1) {
                for (NBMWebRTCPeer.Observer observer : observers) {
                    observer.onPeerConnectionError("Weird-looking stream: " + mediaStream);
                }
                return;
            }
            for (NBMWebRTCPeer.Observer observer: observers) {
                observer.onRemoteStreamRemoved(mediaStream, NBMPeerConnection.this);
            }
        });
    }

    @Override
    public void onRenegotiationNeeded() {
        // can use to create another offer
        Log.d(TAG, "onRenegotiationNeeded() - currently unused");
    }

    @Override
    public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
        // new from upgraded webrtc dependency, currently unused
        Log.i(TAG, "onAddTrack() - currently unused");
    }

    @Override
    public void onCreateSuccess(SessionDescription sessionDescription) {
        if (localSdp != null) {
            Log.e(TAG, "multiple sdp creates");
            return;
        }

        String sdpDescription = sessionDescription.description;

        // not a big fan of manually modifying the sdp
        final SessionDescription sdp = new SessionDescription(sessionDescription.type, sdpDescription);
        localSdp = sdp;

        executor.execute(() -> {
            if (pc == null) {
                Log.e(TAG, "cannot set local desc - peer connection null for " + connectionId);
                return;
            }

            Log.d(TAG, "setting local SDP for " + connectionId);
            pc.setLocalDescription(NBMPeerConnection.this, sdp);
        });
    }

    @Override
    public void onSetSuccess() {
        executor.execute(() -> {

            if (pc == null) {
                Log.e(TAG, "onSetSuccess() - peer connection null for " + connectionId);
                return;
            }

            if (isInitiator) {
                // offer created -> local sdp was just set
                if (pc.getRemoteDescription() == null) {
                    // remote desc unset -> delegate to observers, they will eventually get a sdp answer
                    Log.d(TAG, "local SDP was set successfully - notifying observers");
                    for (NBMWebRTCPeer.Observer observer: observers) {
                        observer.onLocalSdpOfferGenerated(localSdp, NBMPeerConnection.this);
                    }
                } else {
                    // remote desc was just set -> start adding ice candidates to the peer connection
                    Log.d(TAG, "remote SDP set successfully - draining ice candidates");
                    drainCandidates();
                }
            } else {

                // UNUSED - if we are not the initiator, we created the sdp answer
                // which currently doesnt happen

                if (pc.getLocalDescription() != null) {
                    Log.d(TAG, "local SDP set successfully");
                    for (NBMWebRTCPeer.Observer observer: observers) {
                        observer.onLocalSdpAnswerGenerated(localSdp, NBMPeerConnection.this);
                    }
                    drainCandidates();
                } else {
                    Log.d(TAG, "remote SDP set successfully");
                }
            }
        });
    }

    @Override
    public void onCreateFailure(String s) {
        for (NBMWebRTCPeer.Observer observer: observers) {
            observer.onPeerConnectionError(s);
        }
    }

    @Override
    public void onSetFailure(String s) {
        for (NBMWebRTCPeer.Observer observer: observers) {
            observer.onPeerConnectionError(s);
        }
    }

    private void drainCandidates() {
        if (queuedRemoteCandidates != null) {
            Log.d(TAG, "Add " + queuedRemoteCandidates.size() + " remote candidates");
            for (IceCandidate candidate : queuedRemoteCandidates) {
                pc.addIceCandidate(candidate);
            }
            queuedRemoteCandidates = null;
        }
    }

    public void createOffer(MediaConstraints sdpMediaConstraints) {
        this.sdpMediaConstraints = sdpMediaConstraints;
        if (pc != null) {
            Log.d(TAG, "creating sdp offer");
            isInitiator = true;
            pc.createOffer(this, this.sdpMediaConstraints);
        }
    }

    protected void setRemoteDescriptionSync(SessionDescription sdp) {
        if (pc == null) {
            Log.e(TAG, "cannot set remote desc - peer connection null for " + connectionId);
            return;
        }

        // not a big fan of manually modifying the sdp
        String sdpDescription = sdp.description;

        Log.d(TAG, "setting remote sdp");
        SessionDescription sdpRemote = new SessionDescription(sdp.type, sdpDescription);
        pc.setRemoteDescription(NBMPeerConnection.this, sdpRemote);
    }

    protected void setRemoteDescription(final SessionDescription sdp) {
        executor.execute(() -> setRemoteDescriptionSync(sdp));
    }

    public void addRemoteIceCandidate(final IceCandidate candidate) {
        executor.execute(() -> {
            if (pc != null) {
                if (queuedRemoteCandidates != null) {
                    queuedRemoteCandidates.add(candidate);
                } else {
                    pc.addIceCandidate(candidate);
                }
            }
        });
    }

    public void close() {
        Log.d(TAG, "closing peer connection for " + connectionId);
        if (pc != null) {
            pc.dispose();
            pc = null;
        }
    }

    // currently unused since we do not set a custom bitrate
    private static String setStartBitrate(String codec, boolean isVideoCodec, String sdpDescription, int bitrateKbps) {
        String[] lines = sdpDescription.split("\r\n");
        int rtpmapLineIndex = -1;
        boolean sdpFormatUpdated = false;
        String codecRtpMap = null;
        // Search for codec rtpmap in format
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
        Pattern codecPattern = Pattern.compile(regex);
        for (int i = 0; i < lines.length; i++) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1);
                rtpmapLineIndex = i;
                break;
            }
        }
        if (codecRtpMap == null) {
            Log.w(TAG, "No rtpmap for " + codec + " codec");
            return sdpDescription;
        }
        Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + " at " + lines[rtpmapLineIndex]);
        // Check if a=fmtp string already exist in remote SDP for this codec and
        // update it with new bitrate parameter.
        regex = "^a=fmtp:" + codecRtpMap + " \\w+=\\d+.*[\r]?$";
        codecPattern = Pattern.compile(regex);
        for (int i = 0; i < lines.length; i++) {
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                Log.d(TAG, "Found " + codec + " " + lines[i]);
                if (isVideoCodec) {
                    lines[i] += "; " + VIDEO_CODEC_PARAM_START_BITRATE + "=" + bitrateKbps;
                } else {
                    lines[i] += "; " + AUDIO_CODEC_PARAM_BITRATE + "=" + (bitrateKbps * 1000);
                }
                Log.d(TAG, "Update remote SDP line: " + lines[i]);
                sdpFormatUpdated = true;
                break;
            }
        }
        StringBuilder newSdpDescription = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            newSdpDescription.append(lines[i]).append("\r\n");
            // Append new a=fmtp line if no such line exist for a codec.
            if (!sdpFormatUpdated && i == rtpmapLineIndex) {
                String bitrateSet;
                if (isVideoCodec) {
                    bitrateSet = "a=fmtp:" + codecRtpMap + " " + VIDEO_CODEC_PARAM_START_BITRATE + "=" + bitrateKbps;
                } else {
                    bitrateSet = "a=fmtp:" + codecRtpMap + " " + AUDIO_CODEC_PARAM_BITRATE + "=" + (bitrateKbps * 1000);
                }
                Log.d(TAG, "Add remote SDP line: " + bitrateSet);
                newSdpDescription.append(bitrateSet).append("\r\n");
            }
        }
        return newSdpDescription.toString();
    }

    // currently unused since we use the default video / audio codecs
    private static String preferCodec(String sdpDescription, String codec, boolean isAudio) {
        String[] lines = sdpDescription.split("\r\n");
        int mLineIndex = -1;
        String codecRtpMap = null;
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
        Pattern codecPattern = Pattern.compile(regex);
        String mediaDescription = "m=video ";
        if (isAudio) {
            mediaDescription = "m=audio ";
        }
        for (int i = 0; (i < lines.length) && (mLineIndex == -1 || codecRtpMap == null); i++) {
            if (lines[i].startsWith(mediaDescription)) {
                mLineIndex = i;
                continue;
            }
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1);
                continue;
            }
        }
        if (mLineIndex == -1) {
            Log.w(TAG, "No " + mediaDescription + " line, so can't prefer " + codec);
            return sdpDescription;
        }
        if (codecRtpMap == null) {
            Log.w(TAG, "No rtpmap for " + codec);
            return sdpDescription;
        }
        Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + ", prefer at " + lines[mLineIndex]);
        String[] origMLineParts = lines[mLineIndex].split(" ");
        if (origMLineParts.length > 3) {
            StringBuilder newMLine = new StringBuilder();
            int origPartIndex = 0;
            // Format is: m=<media> <port> <proto> <fmt> ...
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(codecRtpMap);
            for (; origPartIndex < origMLineParts.length; origPartIndex++) {
                if (!origMLineParts[origPartIndex].equals(codecRtpMap)) {
                    newMLine.append(" ").append(origMLineParts[origPartIndex]);
                }
            }
            lines[mLineIndex] = newMLine.toString();
            Log.d(TAG, "Change media description: " + lines[mLineIndex]);
        } else {
            Log.e(TAG, "Wrong SDP media description format: " + lines[mLineIndex]);
        }
        StringBuilder newSdpDescription = new StringBuilder();
        for (String line : lines) {
            newSdpDescription.append(line).append("\r\n");
        }
        return newSdpDescription.toString();
    }
}

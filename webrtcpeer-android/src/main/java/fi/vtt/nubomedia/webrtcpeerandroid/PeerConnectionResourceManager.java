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
import org.webrtc.Logging;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import java.util.Collection;
import java.util.HashMap;
import fi.vtt.nubomedia.utilitiesandroid.LooperExecutor;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMWebRTCPeer.NBMPeerConnectionParameters;
import fi.vtt.nubomedia.webrtcpeerandroid.NBMWebRTCPeer.SignalingParameters;

/**
 * The class implements the management of PeerConnection instances.
 *
 * The implementation is based on PeerConnectionClient.java of package org.appspot.apprtc
 * (please see the copyright notice below)
 */
final class PeerConnectionResourceManager {

    private static final String TAG = "[VC][KURENTO][PCRM]";

    private boolean preferIsac;
    private boolean preferH264;
    private boolean videoCallEnabled;
    private LooperExecutor executor;
    private PeerConnectionFactory factory;
    private HashMap<String,NBMPeerConnection> connections;
    private NBMPeerConnectionParameters peerConnectionParameters;

    PeerConnectionResourceManager(NBMPeerConnectionParameters peerConnectionParameters,
                                         LooperExecutor executor, PeerConnectionFactory factory) {

        this.peerConnectionParameters = peerConnectionParameters;
        this.executor = executor;
        this.factory = factory;
        videoCallEnabled = peerConnectionParameters.videoCallEnabled;

        preferH264 = false;
        preferIsac = false;

        connections = new HashMap<>();
    }

    NBMPeerConnection createPeerConnection( SignalingParameters signalingParameters,
                                            MediaConstraints pcConstraints,
                                            String connectionId,
                                            boolean isScreenshareOffer) {

        Log.d(TAG, "creating peer connection for " + connectionId);

        // TCP candidates are only useful when connecting to a server that supports ICE-TCP
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(signalingParameters.iceServers);
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;

        // PeerConnectionFactory.createPeerConnection() w/ MediaConstraints deprecated
        // can set "constraints" in RTCConfiguration object
        rtcConfig.enableDtlsSrtp = !peerConnectionParameters.loopback;

        NBMPeerConnection connectionWrapper = new NBMPeerConnection(connectionId, preferIsac, videoCallEnabled, preferH264, executor, peerConnectionParameters, isScreenshareOffer);
        PeerConnection peerConnection = factory.createPeerConnection(rtcConfig, connectionWrapper);

        connectionWrapper.setPc(peerConnection);
        connections.put(connectionId, connectionWrapper);

        // Set INFO libjingle logging.
        // NOTE: this _must_ happen while |factory| is alive!
        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO);

        return connectionWrapper;
    }

    NBMPeerConnection getConnection(String connectionId){
        return connections.get(connectionId);
    }

    Collection<NBMPeerConnection> getConnections(){
        return connections.values();
    }

    void closeConnection(String connectionId) {
        NBMPeerConnection connection = connections.remove(connectionId);
        if (connection != null) {
            connection.close();
        }
    }

    void closeAllConnections() {
        for (NBMPeerConnection connection : connections.values()) {
            connection.close();
        }
        connections.clear();
    }
}

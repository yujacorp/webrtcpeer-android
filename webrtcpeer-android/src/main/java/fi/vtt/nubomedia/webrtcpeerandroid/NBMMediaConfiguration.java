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

/**
 * Media configuration object used in construction of NBMWebRTCPeer
 */
public class NBMMediaConfiguration {

    /**
     * Renderer type
     */
    public enum NBMRendererType {
        NATIVE, OPENGLES
    }

    public enum NBMAudioCodec {
        OPUS, ISAC
    }

    public enum NBMVideoCodec {
        VP8, VP9, H264
    }

    public enum NBMCameraPosition {
        ANY, BACK, FRONT
    }

    public static class NBMVideoFormat {

        // in pixels
        public final int height;
        public final int width;

        public final double frameRate;

        public NBMVideoFormat(int width, int height, double frameRate) {
            this.width = width;
            this.height = height;

            this.frameRate = frameRate;
        }
    }

    private NBMRendererType rendererType;
    private NBMAudioCodec audioCodec;
    private int audioBandwidth;
    private NBMVideoCodec videoCodec;
    private int videoBandwidth;
    private NBMCameraPosition cameraPosition;
    private NBMVideoFormat receiverVideoFormat;
    private boolean useHardwareAcceleration;

    public NBMCameraPosition getCameraPosition() { return cameraPosition; }
    public NBMRendererType getRendererType() { return rendererType; }
    public NBMAudioCodec getAudioCodec() { return audioCodec; }
    public int getAudioBandwidth() { return audioBandwidth; }
    public NBMVideoCodec getVideoCodec() { return videoCodec; }
    public int getVideoBandwidth() { return videoBandwidth; }
    public NBMVideoFormat getReceiverVideoFormat() { return receiverVideoFormat; }
    public boolean isUseHardwareAcceleration() { return useHardwareAcceleration; }

    public NBMMediaConfiguration() {
        rendererType = NBMRendererType.NATIVE;
        audioCodec = NBMAudioCodec.OPUS;
        audioBandwidth = 0;

        videoCodec = NBMVideoCodec.VP8;
        videoBandwidth = 0;

        receiverVideoFormat = new NBMVideoFormat(640, 480, 30);
        cameraPosition = NBMCameraPosition.FRONT;

        useHardwareAcceleration = true;
    }

    public NBMMediaConfiguration(NBMRendererType rendererType, NBMAudioCodec audioCodec,
                                 int audioBandwidth, NBMVideoCodec videoCodec,
                                 int videoBandwidth, NBMVideoFormat receiverVideoFormat,
                                 NBMCameraPosition cameraPosition,
                                 boolean useHardwareAcceleration) {
        this.rendererType = rendererType;
        this.audioCodec = audioCodec;
        this.audioBandwidth = audioBandwidth;
        this.videoCodec = videoCodec;
        this.videoBandwidth = videoBandwidth;
        this.receiverVideoFormat = receiverVideoFormat;
        this.cameraPosition = cameraPosition;
        this.useHardwareAcceleration = useHardwareAcceleration;
    }
}

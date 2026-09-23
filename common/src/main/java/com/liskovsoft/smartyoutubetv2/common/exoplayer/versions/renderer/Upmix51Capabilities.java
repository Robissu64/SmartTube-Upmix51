package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioProfile;
import android.media.AudioTrack;
import android.os.Build;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.audio.UpmixAudioProcessor;
import com.google.android.exoplayer2.util.Log;
import java.util.Arrays;

/**
 * Experimental PCM 5.1 gate for the V0.1b probe.
 *
 * <p>V0.1 required the HDMI route to explicitly advertise PCM16/5.1 and direct playback. Some TV
 * firmwares omit those capabilities even when a six-channel AudioTrack can be initialized. V0.1b
 * therefore treats advertised capabilities as diagnostic evidence, not as a hard requirement. The
 * platform AudioTrack initialization in DefaultAudioSink is the final probe and safely falls back to
 * stereo if the six-channel track is rejected.
 */
final class Upmix51Capabilities implements UpmixAudioProcessor.SupportChecker {
    private static final boolean ALLOW_UNCONFIRMED_PCM51_PROBE = true;

    private final AudioManager manager;

    Upmix51Capabilities(Context context) {
        manager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public boolean supports(int rate, AudioAttributes attributes) {
        if (Build.VERSION.SDK_INT < 21 || rate <= 0) {
            return unsupported("requires API 21+ and a valid sample rate");
        }

        int minBufferSize = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_5POINT1,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize <= 0) {
            return unsupported("AudioTrack.getMinBufferSize rejected PCM16 5.1 at " + rate
                    + "Hz: " + minBufferSize);
        }

        if (Build.VERSION.SDK_INT < 29 || manager == null) {
            return allowUnconfirmed(rate, minBufferSize,
                    Build.VERSION.SDK_INT < 29
                            ? "API <29 has no reliable direct-playback capability query"
                            : "AudioManager unavailable");
        }

        try {
            return supportsV29(rate, attributes, minBufferSize);
        } catch (RuntimeException e) {
            return allowUnconfirmed(rate, minBufferSize, "capability query failed: " + e);
        }
    }

    @TargetApi(29)
    private boolean supportsV29(int rate, AudioAttributes attributes, int minBufferSize) {
        AudioFormat format = new AudioFormat.Builder().setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build();

        boolean hdmiAdvertisesPcm51 = false;
        AudioDeviceInfo[] devices = Build.VERSION.SDK_INT >= 33
                ? manager.getAudioDevicesForAttributes(attributes.getAudioAttributesV21())
                    .toArray(new AudioDeviceInfo[0])
                : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (AudioDeviceInfo device : devices) {
            int type = device.getType();
            if (type != AudioDeviceInfo.TYPE_HDMI && type != AudioDeviceInfo.TYPE_HDMI_ARC
                    && type != 29 /* TYPE_HDMI_EARC, introduced API 31 */) continue;
            Log.i(UpmixAudioProcessor.TAG, "HDMI candidate id=" + device.getId() + " type=" + type
                    + " encodings=" + Arrays.toString(device.getEncodings())
                    + " masks=" + Arrays.toString(device.getChannelMasks())
                    + " rates=" + Arrays.toString(device.getSampleRates()));
            hdmiAdvertisesPcm51 |= (Build.VERSION.SDK_INT >= 31 ? hasPcmProfile(device, rate)
                    : contains(device.getEncodings(), AudioFormat.ENCODING_PCM_16BIT)
                    && contains(device.getChannelMasks(), AudioFormat.CHANNEL_OUT_5POINT1)
                    && (device.getSampleRates().length == 0 || contains(device.getSampleRates(), rate)));
        }

        boolean direct = Build.VERSION.SDK_INT >= 33
                ? manager.getDirectPlaybackSupport(format, attributes.getAudioAttributesV21())
                    != AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED
                : AudioTrack.isDirectPlaybackSupported(format, attributes.getAudioAttributesV21());

        if (hdmiAdvertisesPcm51 && direct) {
            Log.i(UpmixAudioProcessor.TAG, "PCM 5.1 capability explicitly advertised; minBuffer="
                    + minBufferSize + " bytes (actual eARC route still requires verification)");
            return true;
        }

        String reason;
        if (!hdmiAdvertisesPcm51 && !direct) {
            reason = "HDMI PCM16/5.1 and direct playback are not explicitly advertised";
        } else if (!hdmiAdvertisesPcm51) {
            reason = "HDMI device does not explicitly advertise PCM16 and mask 0xfc";
        } else {
            reason = "direct PCM 5.1 is not explicitly advertised for " + rate + "Hz";
        }
        return allowUnconfirmed(rate, minBufferSize, reason);
    }

    @TargetApi(31)
    private static boolean hasPcmProfile(AudioDeviceInfo device, int rate) {
        for (AudioProfile profile : device.getAudioProfiles()) {
            if (profile.getFormat() == AudioFormat.ENCODING_PCM_16BIT
                    && contains(profile.getChannelMasks(), AudioFormat.CHANNEL_OUT_5POINT1)
                    && (profile.getSampleRates().length == 0 || contains(profile.getSampleRates(), rate))) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(int[] values, int target) {
        for (int value : values) if (value == target) return true;
        return false;
    }

    private static boolean allowUnconfirmed(int rate, int minBufferSize, String reason) {
        if (!ALLOW_UNCONFIRMED_PCM51_PROBE) return unsupported(reason);
        Log.i(UpmixAudioProcessor.TAG,
                "PCM 5.1 capability unconfirmed: " + reason
                        + "; minBuffer=" + minBufferSize
                        + "; attempting six-channel AudioTrack probe at " + rate
                        + "Hz. Initialization failure will fall back to stereo.");
        return true;
    }

    private static boolean unsupported(String reason) {
        Log.i(UpmixAudioProcessor.TAG,
                "Multichannel unsupported - falling back to stereo: " + reason);
        return false;
    }
}

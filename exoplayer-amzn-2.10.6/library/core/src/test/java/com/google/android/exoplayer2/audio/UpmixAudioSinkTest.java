package com.google.android.exoplayer2.audio;

import static org.junit.Assert.*;
import android.media.AudioFormat;
import android.media.AudioTrack;
import com.google.android.exoplayer2.C;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowAudioTrack;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class UpmixAudioSinkTest {
  private static DefaultAudioSink sink(boolean supported) {
    return new DefaultAudioSink(new AudioCapabilities(new int[] {C.ENCODING_PCM_16BIT,C.ENCODING_AC3},8),
        new AudioProcessor[] {new UpmixAudioProcessor((rate, attributes) -> supported)});
  }

  private static int value(DefaultAudioSink sink, String name) throws Exception {
    Field configField = DefaultAudioSink.class.getDeclaredField("configuration");
    configField.setAccessible(true);
    Object config = configField.get(sink);
    Field field = config.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.getInt(config);
  }

  @Test public void stereoCreatesSixChannelConfigurationWithCorrectFrameAccounting() throws Exception {
    DefaultAudioSink sink = sink(true);
    sink.configure(C.ENCODING_PCM_16BIT,2,48000,4096,null,0,0);
    assertEquals(AudioFormat.CHANNEL_OUT_5POINT1,value(sink,"outputChannelConfig"));
    assertEquals(4,value(sink,"inputPcmFrameSize"));
    assertEquals(12,value(sink,"outputPcmFrameSize"));
    assertEquals(48000,value(sink,"outputSampleRate"));
  }

  @Test public void unsupportedDeviceStaysStereo() throws Exception {
    DefaultAudioSink sink = sink(false);
    sink.configure(C.ENCODING_PCM_16BIT,2,44100,4096,null,0,0);
    assertEquals(AudioFormat.CHANNEL_OUT_STEREO,value(sink,"outputChannelConfig"));
    assertEquals(4,value(sink,"outputPcmFrameSize"));
  }

  @Test public void nativeMultichannelAndPassthroughPreserved() throws Exception {
    DefaultAudioSink sink = sink(true);
    sink.configure(C.ENCODING_PCM_16BIT,6,48000,4096,null,0,0);
    assertEquals(12,value(sink,"inputPcmFrameSize"));
    assertEquals(12,value(sink,"outputPcmFrameSize"));
    assertTrue(sink.supportsOutput(6,C.ENCODING_AC3));
    sink.configure(C.ENCODING_AC3,6,48000,4096,null,0,0);
    assertEquals(C.ENCODING_AC3,value(sink,"outputEncoding"));
  }

  @Test public void mappedNativeMultichannelMustNotBecomeUpmix() throws Exception {
    DefaultAudioSink sink = sink(true);
    sink.configure(C.ENCODING_PCM_16BIT,6,48000,4096,new int[] {0,1},0,0);
    assertEquals(AudioFormat.CHANNEL_OUT_STEREO,value(sink,"outputChannelConfig"));
  }

  @Test public void tunnelingBypassesUpmix() throws Exception {
    DefaultAudioSink sink = sink(true);
    sink.enableTunnelingV21(1234);
    sink.configure(C.ENCODING_PCM_16BIT,2,48000,4096,null,0,0);
    assertEquals(AudioFormat.CHANNEL_OUT_STEREO,value(sink,"outputChannelConfig"));
  }

  @Test
  @Config(shadows = RejectSurroundTrack.class)
  public void failedSixChannelTrackRetriesStereoBeforeConsumingPcm() throws Exception {
    DefaultAudioSink sink = sink(true);
    sink.configure(C.ENCODING_PCM_16BIT,2,48000,4096,null,0,0);
    assertEquals(AudioFormat.CHANNEL_OUT_5POINT1,value(sink,"outputChannelConfig"));
    assertTrue(sink.handleBuffer(ByteBuffer.allocateDirect(0),0));
    assertEquals(AudioFormat.CHANNEL_OUT_STEREO,value(sink,"outputChannelConfig"));
    assertEquals(4,value(sink,"outputPcmFrameSize"));
    sink.reset();
  }

  @Implements(AudioTrack.class)
  public static class RejectSurroundTrack extends ShadowAudioTrack {
    @RealObject private AudioTrack track;
    @Implementation protected int getState() {
      return track.getChannelCount() == 6
          ? AudioTrack.STATE_UNINITIALIZED : AudioTrack.STATE_INITIALIZED;
    }
  }
}

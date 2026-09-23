package com.google.android.exoplayer2.audio;

import static org.junit.Assert.*;
import android.media.AudioFormat;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.Util;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public final class UpmixAudioProcessorTest {
  private static UpmixAudioProcessor processor(boolean supported) {
    UpmixAudioProcessor p = new UpmixAudioProcessor((rate, attributes) -> supported);
    p.setSourceAllowed(true, AudioAttributes.DEFAULT);
    p.configure(48000, 2, C.ENCODING_PCM_16BIT);
    p.flush();
    return p;
  }

  private static ByteBuffer pcm(int... samples) {
    ByteBuffer b = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
    for (int sample : samples) b.putShort((short) sample);
    b.flip();
    return b;
  }

  private static void frame(ByteBuffer b, int left, int right, int center) {
    b.order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(left, b.getShort());
    assertEquals(right, b.getShort());
    assertEquals(center, b.getShort());
    for (int i = 0; i < 3; i++) assertEquals(0, b.getShort());
  }

  @Test public void channelsAndFullScaleDoNotOverflow() {
    UpmixAudioProcessor p = processor(true);
    p.queueInput(pcm(32767,32767, -32768,-32768, 30000,-30000, 12000,0));
    ByteBuffer out = p.getOutput();
    assertEquals(48, out.remaining());
    frame(out,32767,32767,32767);
    frame(out,-32768,-32768,-32768);
    frame(out,30000,-30000,0);
    frame(out,12000,0,6000);
    assertEquals(48000,p.getOutputSampleRateHz());
    assertEquals(C.ENCODING_PCM_16BIT,p.getOutputEncoding());
    assertEquals(6,p.getOutputChannelCount());
  }

  @Test public void channelMaskIsBackFivePointOneInAndroidOrder() {
    assertEquals(AudioFormat.CHANNEL_OUT_5POINT1, Util.getAudioTrackChannelConfig(6));
    assertEquals(0xfc, AudioFormat.CHANNEL_OUT_5POINT1);
    assertTrue(AudioFormat.CHANNEL_OUT_FRONT_LEFT < AudioFormat.CHANNEL_OUT_FRONT_RIGHT);
    assertTrue(AudioFormat.CHANNEL_OUT_FRONT_RIGHT < AudioFormat.CHANNEL_OUT_FRONT_CENTER);
    assertTrue(AudioFormat.CHANNEL_OUT_FRONT_CENTER < AudioFormat.CHANNEL_OUT_LOW_FREQUENCY);
    assertTrue(AudioFormat.CHANNEL_OUT_LOW_FREQUENCY < AudioFormat.CHANNEL_OUT_BACK_LEFT);
    assertTrue(AudioFormat.CHANNEL_OUT_BACK_LEFT < AudioFormat.CHANNEL_OUT_BACK_RIGHT);
  }

  @Test public void fragmentedFramesAndEos() {
    UpmixAudioProcessor p = processor(true);
    p.queueInput(ByteBuffer.wrap(new byte[] {0x34}));
    assertEquals(0,p.getOutput().remaining());
    p.queueInput(ByteBuffer.wrap(new byte[] {0x12,0x78,0x56}));
    p.queueEndOfStream();
    assertFalse(p.isEnded());
    frame(p.getOutput(),0x1234,0x5678,(0x1234+0x5678)/2);
    assertTrue(p.isEnded());
  }

  @Test public void largeBuffersPreserveFrameCountAndBoundAllocation() {
    UpmixAudioProcessor p = processor(true);
    ByteBuffer input = ByteBuffer.allocate(20000 * 4);
    int bytes = 0;
    while (input.hasRemaining()) {
      p.queueInput(input);
      ByteBuffer output = p.getOutput();
      assertTrue(output.capacity() <= 4096 * 12);
      bytes += output.remaining();
    }
    assertEquals(20000*12,bytes);
  }

  @Test public void unsupportedAndNativeFormatsBypass() {
    assertFalse(processor(false).isActive());
    UpmixAudioProcessor p = processor(true);
    for (int channels : new int[] {1,6,8}) {
      p.configure(44100,channels,C.ENCODING_PCM_16BIT);
      assertFalse(p.isActive());
      assertEquals(channels,p.getOutputChannelCount());
    }
    p.configure(48000,2,C.ENCODING_PCM_FLOAT);
    assertFalse(p.isActive());
    p.setSourceAllowed(false,AudioAttributes.DEFAULT);
    p.configure(48000,2,C.ENCODING_PCM_16BIT);
    assertFalse(p.isActive());
  }

  @Test public void pendingFormatDoesNotCorruptDrainingOldAudio() {
    UpmixAudioProcessor p = processor(true);
    p.configure(44100,6,C.ENCODING_PCM_16BIT);
    p.queueInput(pcm(1000,3000)); // Old stereo chain still draining.
    frame(p.getOutput(),1000,3000,2000);
    p.flush();
    assertFalse(p.isActive());
    p.configure(44100,2,C.ENCODING_PCM_16BIT);
    p.flush();
    p.queueInput(pcm(-1000,-3000));
    frame(p.getOutput(),-1000,-3000,-2000);
  }

  @Test public void flushDropsPartialAndFallbackSurvivesReset() {
    UpmixAudioProcessor p = processor(true);
    p.queueInput(ByteBuffer.wrap(new byte[] {1,2,3}));
    p.flush();
    p.queueInput(pcm(100,200));
    frame(p.getOutput(),100,200,150);
    p.disableForSinkLifetime();
    p.reset();
    p.setSourceAllowed(true,AudioAttributes.DEFAULT);
    p.configure(48000,2,C.ENCODING_PCM_16BIT);
    assertFalse(p.isActive());
  }
}

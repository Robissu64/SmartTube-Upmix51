package com.google.android.exoplayer2.audio;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Experimental, frame-preserving PCM16 stereo to 5.1 proof of concept. */
public final class UpmixAudioProcessor extends BaseAudioProcessor {
  public static final boolean ENABLE_STEREO_TO_51_UPMIX = true;
  public static final String TAG = "UPMIX51";

  public interface SupportChecker {
    boolean supports(int sampleRateHz, AudioAttributes attributes);
  }

  private final SupportChecker supportChecker;
  private boolean sourceAllowed;
  private AudioAttributes attributes = AudioAttributes.DEFAULT;
  private boolean disabledForSink;
  private boolean pendingActive;
  private boolean active;
  private boolean loggedBuffer;
  private final byte[] partialFrame = new byte[4];
  private int partialBytes;

  public UpmixAudioProcessor(SupportChecker supportChecker) {
    this.supportChecker = supportChecker;
  }

  // Called by the sink before channel mapping. Never upmix a downmapped native 5.1 source.
  public void setSourceAllowed(boolean allowed, AudioAttributes attributes) {
    sourceAllowed = allowed;
    this.attributes = attributes;
  }

  public void disableForSinkLifetime() {
    disabledForSink = true;
  }

  @Override
  public boolean configure(int rate, int channels, int pcmEncoding) {
    boolean changed = setInputFormat(rate, channels, pcmEncoding);
    boolean enable = ENABLE_STEREO_TO_51_UPMIX && !disabledForSink && sourceAllowed
        && channels == 2 && pcmEncoding == C.ENCODING_PCM_16BIT && supportChecker.supports(rate, attributes);
    changed |= pendingActive != enable;
    pendingActive = enable;
    Log.i(TAG, "Input format: PCM " + channels + "ch " + rate + "Hz encoding=" + pcmEncoding);
    Log.i(TAG, enable ? "Stereo source detected; output PCM16 6ch; Upmix enabled"
        : "Upmix bypassed (source, mode, encoding or capabilities); original output preserved");
    return changed;
  }

  @Override public boolean isActive() { return pendingActive; }
  @Override public int getOutputChannelCount() { return pendingActive ? 6 : channelCount; }

  @Override
  public void queueInput(ByteBuffer input) {
    // Configure may run while the previous format drains. Commit DSP state only in flush().
    if (!active) {
      ByteBuffer output = replaceOutputBuffer(input.remaining());
      output.put(input).flip();
      return;
    }
    // Bounded reusable buffer. Partial consumption is allowed by AudioProcessor.
    int frames = (int) Math.min(4096L, ((long) partialBytes + input.remaining()) / 4);
    ByteBuffer output = replaceOutputBuffer(frames * 12).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < frames; i++) {
      while (partialBytes < 4) partialFrame[partialBytes++] = input.get();
      int left = (short) ((partialFrame[0] & 255) | (partialFrame[1] << 8));
      int right = (short) ((partialFrame[2] & 255) | (partialFrame[3] << 8));
      partialBytes = 0;
      // Android CHANNEL_OUT_5POINT1: ascending position bits FL FR FC LFE BL BR.
      // Sum in int before division: no signed 16-bit overflow, even at full scale.
      output.putShort((short) left).putShort((short) right)
          .putShort((short) ((left + right) / 2))
          .putShort((short) 0).putShort((short) 0).putShort((short) 0);
    }
    if (input.remaining() < 4) {
      while (input.hasRemaining()) partialFrame[partialBytes++] = input.get();
    }
    output.flip();
    if (frames > 0 && !loggedBuffer) {
      Log.i(TAG, "Processing PCM: first buffer converted; " + frames
          + " frames, FL=L FR=R C=(L+R)/2 LFE=BL=BR=0");
      loggedBuffer = true;
    }
  }

  @Override protected void onFlush() {
    active = pendingActive;
    partialBytes = 0;
    loggedBuffer = false;
  }

  @Override protected void onQueueEndOfStream() {
    if (partialBytes != 0) Log.w(TAG, "Discarding incomplete PCM frame at EOS: " + partialBytes + " bytes");
    partialBytes = 0;
  }

  @Override protected void onReset() {
    active = pendingActive = sourceAllowed = false;
    // A failed AudioTrack remains disabled for this sink's lifetime, including reset().
  }
}

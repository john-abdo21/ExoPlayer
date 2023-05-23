/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.audio;

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import androidx.annotation.DoNotInline;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Provides the {@link AudioOffloadSupport} capabilities for a {@link Format} and {@link
 * AudioAttributes}.
 */
public final class DefaultAudioOffloadSupportProvider
    implements DefaultAudioSink.AudioOffloadSupportProvider {

  /** AudioManager parameters key for retrieving support of variable speeds during offload. */
  private static final String OFFLOAD_VARIABLE_RATE_SUPPORTED_KEY = "offloadVariableRateSupported";

  @Nullable private final Context context;

  /**
   * Whether variable speeds are supported during offload. If {@code null} then it has not been
   * attempted to retrieve value from {@link AudioManager}.
   */
  private @MonotonicNonNull Boolean isOffloadVariableRateSupported;

  /** Creates an instance. */
  public DefaultAudioOffloadSupportProvider() {
    this(/* context= */ null);
  }

  /**
   * Creates an instance.
   *
   * @param context The context used to retrieve the {@link AudioManager} parameters for checking
   *     offload variable rate support.
   */
  public DefaultAudioOffloadSupportProvider(@Nullable Context context) {
    this.context = context;
  }

  @Override
  public AudioOffloadSupport getAudioOffloadSupport(
      Format format, AudioAttributes audioAttributes) {
    checkNotNull(format);
    checkNotNull(audioAttributes);

    if (Util.SDK_INT < 29) {
      return AudioOffloadSupport.DEFAULT_UNSUPPORTED;
    }

    // isOffloadVariableRateSupported is lazily-loaded instead of being initialized in
    // the constructor so that the platform will be queried from the playback thread.
    boolean isOffloadVariableRateSupported = isOffloadVariableRateSupported(context);

    @C.Encoding
    int encoding = MimeTypes.getEncoding(checkNotNull(format.sampleMimeType), format.codecs);
    if (encoding == C.ENCODING_INVALID) {
      return AudioOffloadSupport.DEFAULT_UNSUPPORTED;
    }
    int channelConfig = Util.getAudioTrackChannelConfig(format.channelCount);
    if (channelConfig == AudioFormat.CHANNEL_INVALID) {
      return AudioOffloadSupport.DEFAULT_UNSUPPORTED;
    }

    AudioFormat audioFormat = Util.getAudioFormat(format.sampleRate, channelConfig, encoding);
    if (Util.SDK_INT >= 31) {
      return Api31.getOffloadedPlaybackSupport(
          audioFormat,
          audioAttributes.getAudioAttributesV21().audioAttributes,
          isOffloadVariableRateSupported);
    }
    return Api29.getOffloadedPlaybackSupport(
        audioFormat,
        audioAttributes.getAudioAttributesV21().audioAttributes,
        isOffloadVariableRateSupported);
  }

  private boolean isOffloadVariableRateSupported(@Nullable Context context) {
    if (isOffloadVariableRateSupported != null) {
      return isOffloadVariableRateSupported;
    }

    if (context != null) {
      AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      if (audioManager != null) {
        String offloadVariableRateSupportedKeyValue =
            audioManager.getParameters(/* keys= */ OFFLOAD_VARIABLE_RATE_SUPPORTED_KEY);
        isOffloadVariableRateSupported =
            offloadVariableRateSupportedKeyValue != null
                && offloadVariableRateSupportedKeyValue.equals(
                    OFFLOAD_VARIABLE_RATE_SUPPORTED_KEY + "=1");
      } else {
        isOffloadVariableRateSupported = false;
      }
    } else {
      isOffloadVariableRateSupported = false;
    }
    return isOffloadVariableRateSupported;
  }

  @RequiresApi(29)
  private static final class Api29 {
    private Api29() {}

    @DoNotInline
    public static AudioOffloadSupport getOffloadedPlaybackSupport(
        AudioFormat audioFormat,
        android.media.AudioAttributes audioAttributes,
        boolean isOffloadVariableRateSupported) {
      if (!AudioManager.isOffloadedPlaybackSupported(audioFormat, audioAttributes)) {
        return AudioOffloadSupport.DEFAULT_UNSUPPORTED;
      }
      return new AudioOffloadSupport.Builder()
          .setIsFormatSupported(true)
          .setIsSpeedChangeSupported(isOffloadVariableRateSupported)
          // Manual testing has shown that Pixels on Android 11 support gapless offload.
          .setIsGaplessSupported(Util.SDK_INT == 30 && Util.MODEL.startsWith("Pixel"))
          .build();
    }
  }

  @RequiresApi(31)
  private static final class Api31 {
    private Api31() {}

    @DoNotInline
    public static AudioOffloadSupport getOffloadedPlaybackSupport(
        AudioFormat audioFormat,
        android.media.AudioAttributes audioAttributes,
        boolean isOffloadVariableRateSupported) {
      int playbackOffloadSupport =
          AudioManager.getPlaybackOffloadSupport(audioFormat, audioAttributes);
      if (playbackOffloadSupport == AudioManager.PLAYBACK_OFFLOAD_NOT_SUPPORTED) {
        return AudioOffloadSupport.DEFAULT_UNSUPPORTED;
      }
      AudioOffloadSupport.Builder audioOffloadSupport = new AudioOffloadSupport.Builder();
      return audioOffloadSupport
          .setIsFormatSupported(true)
          .setIsGaplessSupported(
              playbackOffloadSupport == AudioManager.PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED)
          .setIsSpeedChangeSupported(isOffloadVariableRateSupported)
          .build();
    }
  }
}

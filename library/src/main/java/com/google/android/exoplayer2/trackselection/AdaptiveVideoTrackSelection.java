/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.google.android.exoplayer2.trackselection;

import android.os.SystemClock;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.chunk.MediaChunk;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import java.util.List;

/**
 * 基于带宽的自适应算法
 * A bandwidth based adaptive {@link TrackSelection} for video, whose selected track is updated to
 * be the one of highest quality given the current network conditions and the state of the buffer.
 */
public class AdaptiveVideoTrackSelection extends BaseTrackSelection {

  /**
   * Factory for {@link AdaptiveVideoTrackSelection} instances.
   */
  public static final class Factory implements TrackSelection.Factory {

    private final BandwidthMeter bandwidthMeter;
    private final int maxInitialBitrate;
    private final int minDurationForQualityIncreaseMs;
    private final int maxDurationForQualityDecreaseMs;
    private final int minDurationToRetainAfterDiscardMs;
    private final float bandwidthFraction;

    /**
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
     */
    public Factory(BandwidthMeter bandwidthMeter) {
      this (bandwidthMeter, DEFAULT_MAX_INITIAL_BITRATE,
          DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
          DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
          DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, DEFAULT_BANDWIDTH_FRACTION);
    }

    /**
     * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
     * @param maxInitialBitrate The maximum bitrate in bits per second that should be assumed
     *     when a bandwidth estimate is unavailable.
     * @param minDurationForQualityIncreaseMs The minimum duration of buffered data required for
     *     the selected track to switch to one of higher quality.
     * @param maxDurationForQualityDecreaseMs The maximum duration of buffered data required for
     *     the selected track to switch to one of lower quality.
     * @param minDurationToRetainAfterDiscardMs When switching to a track of significantly higher
     *     quality, the selection may indicate that media already buffered at the lower quality can
     *     be discarded to speed up the switch. This is the minimum duration of media that must be
     *     retained at the lower quality.
     * @param bandwidthFraction The fraction of the available bandwidth that the selection should
     *     consider available for use. Setting to a value less than 1 is recommended to account
     *     for inaccuracies in the bandwidth estimator.
     */
    public Factory(BandwidthMeter bandwidthMeter, int maxInitialBitrate,
        int minDurationForQualityIncreaseMs, int maxDurationForQualityDecreaseMs,
        int minDurationToRetainAfterDiscardMs, float bandwidthFraction) {
      this.bandwidthMeter = bandwidthMeter;
      this.maxInitialBitrate = maxInitialBitrate;
      this.minDurationForQualityIncreaseMs = minDurationForQualityIncreaseMs;
      this.maxDurationForQualityDecreaseMs = maxDurationForQualityDecreaseMs;
      this.minDurationToRetainAfterDiscardMs = minDurationToRetainAfterDiscardMs;
      this.bandwidthFraction = bandwidthFraction;
    }

    @Override
    public AdaptiveVideoTrackSelection createTrackSelection(TrackGroup group, int... tracks) {
      // 如何选择一个合适的VideoTrack呢?
      return new AdaptiveVideoTrackSelection(group, tracks, bandwidthMeter, maxInitialBitrate,
          minDurationForQualityIncreaseMs, maxDurationForQualityDecreaseMs,
          minDurationToRetainAfterDiscardMs, bandwidthFraction);
    }

  }

  // 至少下载了: 2000ms，也即是2s；或者512k数据才能有一个稳定的带宽估计，这个2s时间也不短；
  // 因此一个默认的variant的选择也很重要

  // 默认是: 800K，这个就是为什么刚开始视频会比较模糊
  // TODO: 提高编码效率, 保证低码率的视频也足够清晰
  //
  public static final int DEFAULT_MAX_INITIAL_BITRATE = 800000;

  // 切换高质量的视频：必须至少10s,
  public static final int DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS = 10000;
  public static final int DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS = 25000;

  public static final int DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS = 25000;

  public static final float DEFAULT_BANDWIDTH_FRACTION = 0.75f; // 0.75的估算

  private final BandwidthMeter bandwidthMeter;
  private final int maxInitialBitrate;
  private final long minDurationForQualityIncreaseUs;
  private final long maxDurationForQualityDecreaseUs;
  private final long minDurationToRetainAfterDiscardUs;
  private final float bandwidthFraction; // 默认: 0.75

  private int selectedIndex;
  private int reason;

  /**
   * @param group The {@link TrackGroup}. Must not be null.
   * @param tracks The indices of the selected tracks within the {@link TrackGroup}. Must not be
   *     null or empty. May be in any order.
   * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
   */
  public AdaptiveVideoTrackSelection(TrackGroup group, int[] tracks,
      BandwidthMeter bandwidthMeter) {
    this (group, tracks, bandwidthMeter, DEFAULT_MAX_INITIAL_BITRATE,
        DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
        DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
        DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS, DEFAULT_BANDWIDTH_FRACTION);
  }

  /**
   * @param group The {@link TrackGroup}. Must not be null.
   * @param tracks The indices of the selected tracks within the {@link TrackGroup}. Must not be
   *     null or empty. May be in any order.
   * @param bandwidthMeter Provides an estimate of the currently available bandwidth.
   * @param maxInitialBitrate The maximum bitrate in bits per second that should be assumed when a
   *     bandwidth estimate is unavailable.
   * @param minDurationForQualityIncreaseMs The minimum duration of buffered data required for the
   *     selected track to switch to one of higher quality.
   * @param maxDurationForQualityDecreaseMs The maximum duration of buffered data required for the
   *     selected track to switch to one of lower quality.
   * @param minDurationToRetainAfterDiscardMs When switching to a track of significantly higher
   *     quality, the selection may indicate that media already buffered at the lower quality can
   *     be discarded to speed up the switch. This is the minimum duration of media that must be
   *     retained at the lower quality.
   * @param bandwidthFraction The fraction of the available bandwidth that the selection should
   *     consider available for use. Setting to a value less than 1 is recommended to account
   *     for inaccuracies in the bandwidth estimator.
   */
  public AdaptiveVideoTrackSelection(TrackGroup group, int[] tracks, BandwidthMeter bandwidthMeter,
      int maxInitialBitrate, long minDurationForQualityIncreaseMs,
      long maxDurationForQualityDecreaseMs, long minDurationToRetainAfterDiscardMs,
      float bandwidthFraction) {
    super(group, tracks);

    this.bandwidthMeter = bandwidthMeter;
    this.maxInitialBitrate = maxInitialBitrate;
    this.minDurationForQualityIncreaseUs = minDurationForQualityIncreaseMs * 1000L;
    this.maxDurationForQualityDecreaseUs = maxDurationForQualityDecreaseMs * 1000L;
    this.minDurationToRetainAfterDiscardUs = minDurationToRetainAfterDiscardMs * 1000L;
    this.bandwidthFraction = bandwidthFraction;
    selectedIndex = determineIdealSelectedIndex(Long.MIN_VALUE);
    reason = C.SELECTION_REASON_INITIAL;
  }

  @Override
  public void updateSelectedTrack(long bufferedDurationUs) {
    // 如何更新选中的额Track呢?
    long nowMs = SystemClock.elapsedRealtime();

    // Get the current and ideal selections.
    int currentSelectedIndex = selectedIndex;
    Format currentFormat = getSelectedFormat();

    // 理想的Format
    int idealSelectedIndex = determineIdealSelectedIndex(nowMs);
    Format idealFormat = getFormat(idealSelectedIndex);

    // Assume we can switch to the ideal selection.
    selectedIndex = idealSelectedIndex;

    // Revert back to the current selection if conditions are not suitable for switching.
    if (currentFormat != null && !isBlacklisted(selectedIndex, nowMs)) {

      // 如果birate升高了
      if (idealFormat.bitrate > currentFormat.bitrate
          && bufferedDurationUs < minDurationForQualityIncreaseUs) {
        // The ideal track is a higher quality, but we have insufficient buffer to safely switch
        // up. Defer switching up for now.
        // 要切换到高码率，则需要有足够的buffer时间
        selectedIndex = currentSelectedIndex;

      } else if (idealFormat.bitrate < currentFormat.bitrate
          && bufferedDurationUs >= maxDurationForQualityDecreaseUs) {
        // The ideal track is a lower quality, but we have sufficient buffer to defer switching
        // down for now.
        // 如果我们的高清码率的视频buffer足够多，那么我们也可以不着急切换variant(format)
        selectedIndex = currentSelectedIndex;
      }
    }

    // 如果有变化，则标志位自适应变化
    // If we adapted, update the trigger.
    if (selectedIndex != currentSelectedIndex) {
      reason = C.SELECTION_REASON_ADAPTIVE;
    }
  }

  @Override
  public int getSelectedIndex() {
    return selectedIndex;
  }

  @Override
  public int getSelectionReason() {
    return reason;
  }

  @Override
  public Object getSelectionData() {
    return null;
  }

  @Override
  public int evaluateQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
    if (queue.isEmpty()) {
      return 0;
    }

    // 评估需要保留的queue的size
    int queueSize = queue.size();
    long bufferedDurationUs = queue.get(queueSize - 1).endTimeUs - playbackPositionUs;
    // 本来就不够，不能扔
    if (bufferedDurationUs < minDurationToRetainAfterDiscardUs) {
      return queueSize;
    }


    int idealSelectedIndex = determineIdealSelectedIndex(SystemClock.elapsedRealtime());
    Format idealFormat = getFormat(idealSelectedIndex);
    // Discard from the first SD chunk beyond minDurationToRetainAfterDiscardUs whose resolution and
    // bitrate are both lower than the ideal track.
    for (int i = 0; i < queueSize; i++) {
      MediaChunk chunk = queue.get(i);
      long durationBeforeThisChunkUs = chunk.startTimeUs - playbackPositionUs;

      // 保证有足够的视频，并且chunk中的事情质量不是很高，则可以扔掉部分
      if (durationBeforeThisChunkUs >= minDurationToRetainAfterDiscardUs
          && chunk.trackFormat.bitrate < idealFormat.bitrate
          && chunk.trackFormat.height < idealFormat.height
          && chunk.trackFormat.height < 720 && chunk.trackFormat.width < 1280) {
        return i;
      }
    }
    return queueSize;
  }

  /**
   * Computes the ideal selected index ignoring buffer health.
   *
   * @param nowMs The current time in the timebase of {@link SystemClock#elapsedRealtime()}, or
   *     {@link Long#MIN_VALUE} to ignore blacklisting.
   */
  private int determineIdealSelectedIndex(long nowMs) {

    // 确定理想的Index
    // 1. 估计的bitrate
    long bitrateEstimate = bandwidthMeter.getBitrateEstimate();
    // 有效的Bitrate
    // 最大的初始bitrate或者估算的: 0.75
    long effectiveBitrate = bitrateEstimate == BandwidthMeter.NO_ESTIMATE ? maxInitialBitrate : (long) (bitrateEstimate * bandwidthFraction);

    int lowestBitrateNonBlacklistedIndex = 0;
    for (int i = 0; i < length; i++) {
      if (nowMs == Long.MIN_VALUE || !isBlacklisted(i, nowMs)) {
        // formats的码率从高到低变化
        Format format = getFormat(i);

        // 直到找到一个满足带宽需要的Format
        if (format.bitrate <= effectiveBitrate) {
          return i;
        } else {
          // 不满足条件，但是带宽最小的Format
          lowestBitrateNonBlacklistedIndex = i;
        }
      }
    }
    return lowestBitrateNonBlacklistedIndex;
  }

}

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
package com.google.android.exoplayer2.upstream;

import android.os.Handler;
import android.os.SystemClock;

import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.SlidingPercentile;

/**
 * Estimates bandwidth by listening to data transfers. The bandwidth estimate is calculated using
 * a {@link SlidingPercentile} and is updated each time a transfer ends.
 * <p>
 * 如何评估带宽呢?
 */
public final class DefaultBandwidthMeter implements BandwidthMeter, TransferListener<Object> {

  /**
   * The default maximum weight for the sliding window.
   */
  public static final int DEFAULT_MAX_WEIGHT = 2000;

  // 至少下载了: 2000ms，也即是2s；或者512k数据才能有一个稳定的带宽估计，这个2s时间也不短；
  // 因此一个默认的variant的选择也很重要
  private static final int ELAPSED_MILLIS_FOR_ESTIMATE = 2000;
  private static final int BYTES_TRANSFERRED_FOR_ESTIMATE = 512 * 1024;

  private final Handler eventHandler;
  private final EventListener eventListener;
  private final SlidingPercentile slidingPercentile;

  private int streamCount;
  private long sampleStartTimeMs;
  private long sampleBytesTransferred;

  private long totalElapsedTimeMs;
  private long totalBytesTransferred;
  private long bitrateEstimate;

  public DefaultBandwidthMeter() {
    this(null, null);
  }

  public DefaultBandwidthMeter(Handler eventHandler, EventListener eventListener) {
    this(eventHandler, eventListener, DEFAULT_MAX_WEIGHT);
  }

  public DefaultBandwidthMeter(Handler eventHandler, EventListener eventListener, int maxWeight) {
    this.eventHandler = eventHandler;
    this.eventListener = eventListener;
    this.slidingPercentile = new SlidingPercentile(maxWeight);

    // 如何做"带宽"估计呢?
    bitrateEstimate = NO_ESTIMATE;
  }

  @Override
  public synchronized long getBitrateEstimate() {
    return bitrateEstimate;
  }

  @Override
  public synchronized void onTransferStart(Object source, DataSpec dataSpec) {
    if (streamCount == 0) {
      sampleStartTimeMs = SystemClock.elapsedRealtime();
    }
    streamCount++;
  }

  @Override
  public synchronized void onBytesTransferred(Object source, int bytes) {
    sampleBytesTransferred += bytes;
  }

  @Override
  public synchronized void onTransferEnd(Object source) {
    Assertions.checkState(streamCount > 0);

    // 统计传输时间 + 传输数据流（字节)
    long nowMs = SystemClock.elapsedRealtime();
    int sampleElapsedTimeMs = (int) (nowMs - sampleStartTimeMs);
    totalElapsedTimeMs += sampleElapsedTimeMs;
    totalBytesTransferred += sampleBytesTransferred;

    // onTransferStart
    // onBytesTransferred
    // onTransferEnd
    // 再怎么招，都不能再一个ts下载过程中切换variants
    //
    if (sampleElapsedTimeMs > 0) {
      // 带宽单位: bit/s
      float bitsPerSecond = (sampleBytesTransferred * 8000) / sampleElapsedTimeMs;

      // 添加sample?
      slidingPercentile.addSample((int) Math.sqrt(sampleBytesTransferred), bitsPerSecond);

      if (totalElapsedTimeMs >= ELAPSED_MILLIS_FOR_ESTIMATE || totalBytesTransferred >= BYTES_TRANSFERRED_FOR_ESTIMATE) {
        float bitrateEstimateFloat = slidingPercentile.getPercentile(0.5f);

        bitrateEstimate = Float.isNaN(bitrateEstimateFloat) ? NO_ESTIMATE : (long) bitrateEstimateFloat;
      }
    }

    // 通知"带宽"变化
    // 可能我们也需要有一个Debug信息
    notifyBandwidthSample(sampleElapsedTimeMs, sampleBytesTransferred, bitrateEstimate);

    // 开始一个新的阶段，例如: 以前有N个stream, 现在只有N-1个Stream
    if (--streamCount > 0) {
      sampleStartTimeMs = nowMs;
    }
    sampleBytesTransferred = 0;
  }

  private void notifyBandwidthSample(final int elapsedMs, final long bytes, final long bitrate) {
    if (eventHandler != null && eventListener != null) {
      eventHandler.post(new Runnable() {
        @Override
        public void run() {
          // 带宽变化了，如何处理呢?
          eventListener.onBandwidthSample(elapsedMs, bytes, bitrate);
        }
      });
    }
  }

}

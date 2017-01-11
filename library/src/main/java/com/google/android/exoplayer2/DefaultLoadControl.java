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
package com.google.android.exoplayer2;

import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.util.Util;

/**
 * The default {@link LoadControl} implementation.
 * 注意这个策略，也许服务器的ts做了很多优化，但是最终不符合Cache管理策略，最终失败
 */
public final class DefaultLoadControl implements LoadControl {

  /**
   * The default minimum duration of media that the player will attempt to ensure is buffered at all
   * times, in milliseconds.
   */
  public static final int DEFAULT_MIN_BUFFER_MS = 15000;

  /**
   * The default maximum duration of media that the player will attempt to buffer, in milliseconds.
   */
  public static final int DEFAULT_MAX_BUFFER_MS = 30000;

  /**
   * The default duration of media that must be buffered for playback to start or resume following a
   * user action such as a seek, in milliseconds.
   *
   * 至少需要Buffer 2.5s, 如果ts文件太小，则不符合这个要求
   */
  public static final int DEFAULT_BUFFER_FOR_PLAYBACK_MS = 2500;

  /**
   * The default duration of media that must be buffered for playback to resume after a rebuffer,
   * in milliseconds. A rebuffer is defined to be caused by buffer depletion rather than a user
   * action.
   */
  public static final int DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS  = 5000;

  // 当前Buffer的情况
  private static final int ABOVE_HIGH_WATERMARK = 0;
  private static final int BETWEEN_WATERMARKS = 1;
  private static final int BELOW_LOW_WATERMARK = 2;

  private final DefaultAllocator allocator;

  private final long minBufferUs;
  private final long maxBufferUs;
  private final long bufferForPlaybackUs;
  private final long bufferForPlaybackAfterRebufferUs;

  private int targetBufferSize;
  private boolean isBuffering;

  /**
   * Constructs a new instance, using the {@code DEFAULT_*} constants defined in this class.
   */
  public DefaultLoadControl() {
    this(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE));
  }

  /**
   * Constructs a new instance, using the {@code DEFAULT_*} constants defined in this class.
   *
   * @param allocator The {@link DefaultAllocator} used by the loader.
   */
  public DefaultLoadControl(DefaultAllocator allocator) {
    this(allocator, DEFAULT_MIN_BUFFER_MS, DEFAULT_MAX_BUFFER_MS, DEFAULT_BUFFER_FOR_PLAYBACK_MS,
        DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS);
  }

  /**
   * Constructs a new instance.
   *
   * @param allocator The {@link DefaultAllocator} used by the loader.
   * @param minBufferMs The minimum duration of media that the player will attempt to ensure is
   *     buffered at all times, in milliseconds.
   * @param maxBufferMs The maximum duration of media that the player will attempt buffer, in
   *     milliseconds.
   * @param bufferForPlaybackMs The duration of media that must be buffered for playback to start or
   *     resume following a user action such as a seek, in milliseconds.
   * @param bufferForPlaybackAfterRebufferMs The default duration of media that must be buffered for
   *     playback to resume after a rebuffer, in milliseconds. A rebuffer is defined to be caused by
   *     buffer depletion rather than a user action.
   */
  public DefaultLoadControl(DefaultAllocator allocator, int minBufferMs, int maxBufferMs,
      long bufferForPlaybackMs, long bufferForPlaybackAfterRebufferMs) {
    this.allocator = allocator;

    // 15s
    minBufferUs = minBufferMs * 1000L;
    // 30s
    maxBufferUs = maxBufferMs * 1000L;

    // 2.5s
    bufferForPlaybackUs = bufferForPlaybackMs * 1000L;

    // 几个常数之间的关系:
    // bufferForPlaybackUs 达到了就可以播放; 但是如果cache时间小于 minBufferUs, 则认为是 BELOW_LOW_WATERMARK 需要不管下载数据
    // 如果 >= maxBufferUs, 则认为比较安全，可以暂停下载；如果在两者之间BETWEEN_WATERMARKS，则遵循"惯性"原则


    // rebuffering 是什么概念呢?
    // 5s
    bufferForPlaybackAfterRebufferUs = bufferForPlaybackAfterRebufferMs * 1000L;
  }

  @Override
  public void onPrepared() {
    reset(false);
  }

  @Override
  public void onTracksSelected(Renderer[] renderers, TrackGroupArray trackGroups,
      TrackSelectionArray trackSelections) {

    // 确保: allocator中有足够的内存
    targetBufferSize = 0;
    for (int i = 0; i < renderers.length; i++) {
      if (trackSelections.get(i) != null) {
        targetBufferSize += Util.getDefaultBufferSize(renderers[i].getTrackType());
      }
    }
    allocator.setTargetBufferSize(targetBufferSize);
  }

  @Override
  public void onStopped() {
    reset(true);
  }

  @Override
  public void onReleased() {
    reset(true);
  }

  @Override
  public Allocator getAllocator() {
    return allocator;
  }

  @Override
  public boolean shouldStartPlayback(long bufferedDurationUs, boolean rebuffering) {
    // 是否开始Playback?
    long minBufferDurationUs = rebuffering ? bufferForPlaybackAfterRebufferUs : bufferForPlaybackUs;

    // 如果不要求Buffer, 或者达到Buffer的要求，则开始播放
    return minBufferDurationUs <= 0 || bufferedDurationUs >= minBufferDurationUs;
  }

  @Override
  public boolean shouldContinueLoading(long bufferedDurationUs) {
    // 当前的数据必须至少Cache 2.5s还开始播放
    // 1. 当前Buffer的数据的时长: bufferedDurationUs
    int bufferTimeState = getBufferTimeState(bufferedDurationUs);

    boolean targetBufferSizeReached = allocator.getTotalBytesAllocated() >= targetBufferSize;

    // 如果数据缓存: BELOW_LOW_WATERMARK
    //             BETWEEN_WATERMARKS，并还有足够的空间，并且之前在Buffering, 则就继续
    isBuffering = bufferTimeState == BELOW_LOW_WATERMARK
        || (bufferTimeState == BETWEEN_WATERMARKS && isBuffering && !targetBufferSizeReached);
    return isBuffering;
  }

  private int getBufferTimeState(long bufferedDurationUs) {
    return bufferedDurationUs > maxBufferUs ? ABOVE_HIGH_WATERMARK
        : (bufferedDurationUs < minBufferUs ? BELOW_LOW_WATERMARK : BETWEEN_WATERMARKS);
  }

  private void reset(boolean resetAllocator) {
    targetBufferSize = 0;
    isBuffering = false;
    if (resetAllocator) {
      allocator.reset();
    }
  }

}

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

import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Util;
import java.util.Arrays;

/**
 * Default implementation of {@link Allocator}.
 */
public final class DefaultAllocator implements Allocator {

  private static final int AVAILABLE_EXTRA_CAPACITY = 100;

  private final boolean trimOnReset;
  private final int individualAllocationSize;
  private final byte[] initialAllocationBlock;
  private final Allocation[] singleAllocationReleaseHolder;

  private int targetBufferSize;
  private int allocatedCount;
  private int availableCount;
  private Allocation[] availableAllocations;

  /**
   * Constructs an instance without creating any {@link Allocation}s up front.
   *
   * @param trimOnReset Whether memory is freed when the allocator is reset. Should be true unless
   *     the allocator will be re-used by multiple player instances.
   * @param individualAllocationSize The length of each individual {@link Allocation}.
   */
  public DefaultAllocator(boolean trimOnReset, int individualAllocationSize) {
    this(trimOnReset, individualAllocationSize, 0);
  }

  /**
   * Constructs an instance with some {@link Allocation}s created up front.
   * <p>
   * Note: {@link Allocation}s created up front will never be discarded by {@link #trim()}.
   *
   * @param trimOnReset Whether memory is freed when the allocator is reset. Should be true unless
   *     the allocator will be re-used by multiple player instances.
   * @param individualAllocationSize The length of each individual {@link Allocation}.
   * @param initialAllocationCount The number of allocations to create up front.
   */
  public DefaultAllocator(boolean trimOnReset, int individualAllocationSize,
      int initialAllocationCount) {

    // 注意开发的各种Utils
    Assertions.checkArgument(individualAllocationSize > 0);
    Assertions.checkArgument(initialAllocationCount >= 0);

    this.trimOnReset = trimOnReset;
    // 单个的Allocation的大小
    this.individualAllocationSize = individualAllocationSize;
    this.availableCount = initialAllocationCount;

    // 内部管理一些: Allocation
    // 这些Allocation没有分配内存
    this.availableAllocations = new Allocation[initialAllocationCount + AVAILABLE_EXTRA_CAPACITY];


    if (initialAllocationCount > 0) {
      // 预先给一些Allocation分配内存
      initialAllocationBlock = new byte[initialAllocationCount * individualAllocationSize];
      for (int i = 0; i < initialAllocationCount; i++) {
        int allocationOffset = i * individualAllocationSize;
        availableAllocations[i] = new Allocation(initialAllocationBlock, allocationOffset);
      }
    } else {
      initialAllocationBlock = null;
    }

    // 用于做接口的Adapter, 单个元素到数组的转换
    singleAllocationReleaseHolder = new Allocation[1];
  }

  public synchronized void reset() {
    if (trimOnReset) {
      setTargetBufferSize(0);
    }
  }

  public synchronized void setTargetBufferSize(int targetBufferSize) {
    boolean targetBufferSizeReduced = targetBufferSize < this.targetBufferSize;
    this.targetBufferSize = targetBufferSize;

    // BufferSize是什么概念？
    if (targetBufferSizeReduced) {
      trim();
    }
  }

  @Override
  public synchronized Allocation allocate() {
    allocatedCount++;
    Allocation allocation;

    if (availableCount > 0) {
      // 直接从内存中拿出一个Allocation
      allocation = availableAllocations[--availableCount];

      // 注意内存的管理
      availableAllocations[availableCount] = null;
    } else {
      // 实在没有就删除
      allocation = new Allocation(new byte[individualAllocationSize], 0);
    }
    return allocation;
  }

  @Override
  public synchronized void release(Allocation allocation) {
    singleAllocationReleaseHolder[0] = allocation;
    // 为了保持对release(Allocation[])的接口兼容
    release(singleAllocationReleaseHolder);
  }

  @Override
  public synchronized void release(Allocation[] allocations) {
    // 1. 扩大 availableAllocations
    if (availableCount + allocations.length >= availableAllocations.length) {
      availableAllocations = Arrays.copyOf(availableAllocations,
          Math.max(availableAllocations.length * 2, availableCount + allocations.length));
    }

    // 2. 将Allocations放回到: availableAllocations
    for (Allocation allocation : allocations) {
      // Weak sanity check that the allocation probably originated from this pool.
      Assertions.checkArgument(allocation.data == initialAllocationBlock
          || allocation.data.length == individualAllocationSize);
      availableAllocations[availableCount++] = allocation;
    }

    // 3. 更新统计数据
    allocatedCount -= allocations.length;

    // Wake up threads waiting for the allocated size to drop.
    notifyAll();
  }

  @Override
  public synchronized void trim() {

    // 总共的内存分配次数
    int targetAllocationCount = Util.ceilDivide(targetBufferSize, individualAllocationSize);

    // 要做什么呢?
    // 如果: targetAvailableCount < availableCount, 则需要删除多余的Allocation
    int targetAvailableCount = Math.max(0, targetAllocationCount - allocatedCount);
    if (targetAvailableCount >= availableCount) {
      // We're already at or below the target.
      return;
    }

    if (initialAllocationBlock != null) {
      // Some allocations are backed by an initial block. We need to make sure that we hold onto all
      // such allocations. Re-order the available allocations so that the ones backed by the initial
      // block come first.
      int lowIndex = 0;
      int highIndex = availableCount - 1;
      while (lowIndex <= highIndex) {
        Allocation lowAllocation = availableAllocations[lowIndex];
        if (lowAllocation.data == initialAllocationBlock) {
          // 1. 直接跳过: lowIndex
          lowIndex++;
        } else {
          // 2. loadIndex需要切换到后面
          Allocation highAllocation = availableAllocations[highIndex];
          if (highAllocation.data != initialAllocationBlock) {
            // 2.1 如果highIndex也在后面排列，则暂时不管loadIndex
            highIndex--;
          } else {
            // 2.2 交换
            availableAllocations[lowIndex++] = highAllocation;
            availableAllocations[highIndex--] = lowAllocation;
          }
        }
      }
      // lowIndex is the index of the first allocation not backed by an initial block.
      targetAvailableCount = Math.max(targetAvailableCount, lowIndex);
      if (targetAvailableCount >= availableCount) {
        // We're already at or below the target.
        return;
      }
    }

    // Discard allocations beyond the target.
    // 降低 availableCount 数量
    Arrays.fill(availableAllocations, targetAvailableCount, availableCount, null);
    availableCount = targetAvailableCount;
  }

  @Override
  public synchronized int getTotalBytesAllocated() {
    // 总共分配的内存
    return allocatedCount * individualAllocationSize;
  }

  @Override
  public int getIndividualAllocationLength() {
    // 单给Allocation的大小
    return individualAllocationSize;
  }

}

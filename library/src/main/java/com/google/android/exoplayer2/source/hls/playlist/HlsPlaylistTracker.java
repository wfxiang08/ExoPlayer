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
package com.google.android.exoplayer2.source.hls.playlist;

import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.source.AdaptiveMediaSourceEventListener.EventDispatcher;
import com.google.android.exoplayer2.source.chunk.ChunkedTrackBlacklistUtil;
import com.google.android.exoplayer2.source.hls.playlist.HlsMasterPlaylist.HlsUrl;
import com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist.Segment;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.util.UriUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Tracks playlists linked to a provided playlist url. The provided url might reference an HLS
 * master playlist or a media playlist.
 *
 * Track对应一个m3u8文件， 可能是master playlist或者media playlist; 注意不同的概念
 * 负责m3u8的下载，管理，状态汇报
 */
public final class HlsPlaylistTracker implements Loader.Callback<ParsingLoadable<HlsPlaylist>> {

  /**
   * Listener for primary playlist changes.
   */
  public interface PrimaryPlaylistListener {

    /**
     * Called when the primary playlist changes. （主要的playlist?)
     *
     * @param mediaPlaylist The primary playlist new snapshot.
     */
    void onPrimaryPlaylistRefreshed(HlsMediaPlaylist mediaPlaylist);

  }

  /**
   * Called on playlist loading events.
   */
  public interface PlaylistEventListener {

    /**
     * Called a playlist changes.
     */
    void onPlaylistChanged();

    /**
     * Called if an error is encountered while loading a playlist.
     *
     * @param url The loaded url that caused the error.
     * @param blacklistDurationMs The number of milliseconds for which the playlist has been
     *     blacklisted.
     */
    void onPlaylistBlacklisted(HlsUrl url, long blacklistDurationMs);

  }

  /**
   * The minimum number of milliseconds that a url is kept as primary url, if no
   * {@link #getPlaylistSnapshot} call is made for that url.
   */
  private static final long PRIMARY_URL_KEEPALIVE_MS = 15000;

  private final Uri initialPlaylistUri;
  private final DataSource.Factory dataSourceFactory;
  private final HlsPlaylistParser playlistParser;
  private final int minRetryCount;
  private final IdentityHashMap<HlsUrl, MediaPlaylistBundle> playlistBundles;
  private final Handler playlistRefreshHandler;
  private final PrimaryPlaylistListener primaryPlaylistListener;
  private final List<PlaylistEventListener> listeners;
  private final Loader initialPlaylistLoader;
  private final EventDispatcher eventDispatcher;

  private HlsMasterPlaylist masterPlaylist;
  private HlsUrl primaryHlsUrl;
  private HlsMediaPlaylist primaryUrlSnapshot;
  private boolean isLive;

  /**
   * @param initialPlaylistUri Uri for the initial playlist of the stream. Can refer a media
   *     playlist or a master playlist.
   * @param dataSourceFactory A factory for {@link DataSource} instances.
   * @param eventDispatcher A dispatcher to notify of events.
   * @param minRetryCount The minimum number of times the load must be retried before blacklisting a
   *     playlist.
   * @param primaryPlaylistListener A callback for the primary playlist change events.
   */
  public HlsPlaylistTracker(Uri initialPlaylistUri, DataSource.Factory dataSourceFactory,
      EventDispatcher eventDispatcher, int minRetryCount,
      PrimaryPlaylistListener primaryPlaylistListener) {

    // Playlist的Uri
    this.initialPlaylistUri = initialPlaylistUri;

    this.dataSourceFactory = dataSourceFactory;
    this.eventDispatcher = eventDispatcher;
    this.minRetryCount = minRetryCount;
    this.primaryPlaylistListener = primaryPlaylistListener;
    listeners = new ArrayList<>();

    // 执行管理 Loadable
    initialPlaylistLoader = new Loader("HlsPlaylistTracker:MasterPlaylist");

    // Parse Playlist文件
    playlistParser = new HlsPlaylistParser();
    playlistBundles = new IdentityHashMap<>();
    playlistRefreshHandler = new Handler();
  }

  /**
   * Registers a listener to receive events from the playlist tracker.
   *
   * @param listener The listener.
   */
  public void addListener(PlaylistEventListener listener) {
    listeners.add(listener);
  }

  /**
   * Unregisters a listener.
   *
   * @param listener The listener to unregister.
   */
  public void removeListener(PlaylistEventListener listener) {
    listeners.remove(listener);
  }

  /**
   * Starts tracking all the playlists related to the provided Uri.
   */
  public void start() {
    // 下载: Playlist, 并且通过 playlistParser 来解析
    // dataSourceFactory.createDataSource() 指定了网络请求
    ParsingLoadable<HlsPlaylist> masterPlaylistLoadable =
            new ParsingLoadable<>(dataSourceFactory.createDataSource(), initialPlaylistUri, C.DATA_TYPE_MANIFEST, playlistParser);

    // this Loader callback
    initialPlaylistLoader.startLoading(masterPlaylistLoadable, this, minRetryCount);
  }

  /**
   * Returns the master playlist.
   *
   * @return The master playlist. Null if the initial playlist has yet to be loaded.
   */
  public HlsMasterPlaylist getMasterPlaylist() {
    return masterPlaylist;
  }

  /**
   * Returns the most recent snapshot available of the playlist referenced by the provided
   * {@link HlsUrl}.
   *
   * @param url The {@link HlsUrl} corresponding to the requested media playlist.
   * @return The most recent snapshot of the playlist referenced by the provided {@link HlsUrl}. May
   *     be null if no snapshot has been loaded yet.
   */
  public HlsMediaPlaylist getPlaylistSnapshot(HlsUrl url) {
    maybeSetPrimaryUrl(url);
    return playlistBundles.get(url).getPlaylistSnapshot();
  }

  /**
   * Releases the playlist tracker.
   */
  public void release() {
    initialPlaylistLoader.release();
    for (MediaPlaylistBundle bundle : playlistBundles.values()) {
      bundle.release();
    }
    playlistRefreshHandler.removeCallbacksAndMessages(null);
    playlistBundles.clear();
  }

  /**
   * If the tracker is having trouble refreshing the primary playlist or loading an irreplaceable
   * playlist, this method throws the underlying error. Otherwise, does nothing.
   *
   * @throws IOException The underlying error.
   */
  public void maybeThrowPlaylistRefreshError() throws IOException {
    initialPlaylistLoader.maybeThrowError();
    if (primaryHlsUrl != null) {
      playlistBundles.get(primaryHlsUrl).mediaPlaylistLoader.maybeThrowError();
    }
  }

  /**
   * Triggers a playlist refresh and whitelists it.
   *
   * @param url The {@link HlsUrl} of the playlist to be refreshed.
   */
  public void refreshPlaylist(HlsUrl url) {
    playlistBundles.get(url).loadPlaylist();
  }

  /**
   * Returns whether this is live content.
   *
   * @return True if the content is live. False otherwise.
   */
  public boolean isLive() {
    return isLive;
  }

  // Loader.Callback implementation.

  @Override
  public void onLoadCompleted(ParsingLoadable<HlsPlaylist> loadable, long elapsedRealtimeMs,
      long loadDurationMs) {

    // m3u8加载完毕
    // 整个文件已经通过: playlistParser 进行解析
    // 注意: ParsingLoadable<HlsPlaylist> 的构建方式
    HlsPlaylist result = loadable.getResult();

    // 区分是一级索引还是二级索引
    HlsMasterPlaylist masterPlaylist;
    boolean isMediaPlaylist = result instanceof HlsMediaPlaylist;

    // 如果是二级索引，则需要构建一个虚拟的masterPlaylist
    if (isMediaPlaylist) {
      masterPlaylist = HlsMasterPlaylist.createSingleVariantMasterPlaylist(result.baseUri);
    } else /* result instanceof HlsMasterPlaylist */ {
      masterPlaylist = (HlsMasterPlaylist) result;
    }
    this.masterPlaylist = masterPlaylist;

    // XXX: 默认第一个为: primaryHlsUrl(因此在播放视频的时候刚开始总是不清晰....）
    primaryHlsUrl = masterPlaylist.variants.get(0);
    ArrayList<HlsUrl> urls = new ArrayList<>();
    urls.addAll(masterPlaylist.variants);
    urls.addAll(masterPlaylist.audios);
    urls.addAll(masterPlaylist.subtitles);

    // 每一个URL <--> MediaPlaylistBundle
    createBundles(urls);

    // 如何处理: MediaPlaylistBundle ?
    // 1. 如果是MediaPlaylist, 则加载完毕
    // 2. 如果是MastPlaylist, 则加载Playlist
    // 参考: createBundles
    MediaPlaylistBundle primaryBundle = playlistBundles.get(primaryHlsUrl);
    if (isMediaPlaylist) {
      // We don't need to load the playlist again. We can use the same result.
      primaryBundle.processLoadedPlaylist((HlsMediaPlaylist) result);
    } else {
      primaryBundle.loadPlaylist();
    }
    eventDispatcher.loadCompleted(loadable.dataSpec, C.DATA_TYPE_MANIFEST, elapsedRealtimeMs,
        loadDurationMs, loadable.bytesLoaded());
  }

  @Override
  public void onLoadCanceled(ParsingLoadable<HlsPlaylist> loadable, long elapsedRealtimeMs,
      long loadDurationMs, boolean released) {
    eventDispatcher.loadCanceled(loadable.dataSpec, C.DATA_TYPE_MANIFEST, elapsedRealtimeMs,
        loadDurationMs, loadable.bytesLoaded());
  }

  @Override
  public int onLoadError(ParsingLoadable<HlsPlaylist> loadable, long elapsedRealtimeMs,
      long loadDurationMs, IOException error) {
    boolean isFatal = error instanceof ParserException;
    eventDispatcher.loadError(loadable.dataSpec, C.DATA_TYPE_MANIFEST, elapsedRealtimeMs,
        loadDurationMs, loadable.bytesLoaded(), error, isFatal);
    return isFatal ? Loader.DONT_RETRY_FATAL : Loader.RETRY;
  }

  // Internal methods.

  private boolean maybeSelectNewPrimaryUrl() {
    List<HlsUrl> variants = masterPlaylist.variants;
    int variantsSize = variants.size();
    long currentTimeMs = SystemClock.elapsedRealtime();
    for (int i = 0; i < variantsSize; i++) {
      MediaPlaylistBundle bundle = playlistBundles.get(variants.get(i));
      if (currentTimeMs > bundle.blacklistUntilMs) {
        primaryHlsUrl = bundle.playlistUrl;
        bundle.loadPlaylist();
        return true;
      }
    }
    return false;
  }

  private void maybeSetPrimaryUrl(HlsUrl url) {
    if (!masterPlaylist.variants.contains(url)
        || (primaryUrlSnapshot != null && primaryUrlSnapshot.hasEndTag)) {
      // Only allow variant urls to be chosen as primary. Also prevent changing the primary url if
      // the last primary snapshot contains an end tag.
      return;
    }
    MediaPlaylistBundle currentPrimaryBundle = playlistBundles.get(primaryHlsUrl);
    long primarySnapshotAccessAgeMs =
        currentPrimaryBundle.lastSnapshotAccessTimeMs - SystemClock.elapsedRealtime();
    if (primarySnapshotAccessAgeMs > PRIMARY_URL_KEEPALIVE_MS) {
      primaryHlsUrl = url;
      playlistBundles.get(primaryHlsUrl).loadPlaylist();
    }
  }

  private void createBundles(List<HlsUrl> urls) {
    int listSize = urls.size();
    long currentTimeMs = SystemClock.elapsedRealtime();
    for (int i = 0; i < listSize; i++) {
      HlsUrl url = urls.get(i);
      MediaPlaylistBundle bundle = new MediaPlaylistBundle(url, currentTimeMs);
      playlistBundles.put(urls.get(i), bundle);
    }
  }

  /**
   * Called by the bundles when a snapshot changes.
   *
   * @param url The url of the playlist.
   * @param newSnapshot The new snapshot.
   * @return True if a refresh should be scheduled.
   */
  private boolean onPlaylistUpdated(HlsUrl url, HlsMediaPlaylist newSnapshot) {
    if (url == primaryHlsUrl) {
      if (primaryUrlSnapshot == null) {
        // This is the first primary url snapshot.
        isLive = !newSnapshot.hasEndTag;
      }
      primaryUrlSnapshot = newSnapshot;
      primaryPlaylistListener.onPrimaryPlaylistRefreshed(newSnapshot);
    }
    int listenersSize = listeners.size();
    for (int i = 0; i < listenersSize; i++) {
      listeners.get(i).onPlaylistChanged();
    }
    // If the primary playlist is not the final one, we should schedule a refresh.
    return url == primaryHlsUrl && !newSnapshot.hasEndTag;
  }

  private void notifyPlaylistBlacklisting(HlsUrl url, long blacklistMs) {
    int listenersSize = listeners.size();
    for (int i = 0; i < listenersSize; i++) {
      listeners.get(i).onPlaylistBlacklisted(url, blacklistMs);
    }
  }

  /**
   * TODO: Track discontinuities for media playlists that don't include the discontinuity number.
   */
  private HlsMediaPlaylist adjustPlaylistTimestamps(HlsMediaPlaylist oldPlaylist,
      HlsMediaPlaylist newPlaylist) {
    // 如何处理视频上的不连续性?
    if (newPlaylist.hasProgramDateTime) {
      if (newPlaylist.isNewerThan(oldPlaylist)) {
        return newPlaylist;
      } else {
        return oldPlaylist;
      }
    }
    // TODO: Once playlist type support is added, the snapshot's age can be added by using the
    // target duration.
    long primarySnapshotStartTimeUs = primaryUrlSnapshot != null
        ? primaryUrlSnapshot.startTimeUs : 0;
    if (oldPlaylist == null) {
      if (newPlaylist.startTimeUs == primarySnapshotStartTimeUs) {
        // Playback has just started or is VOD so no adjustment is needed.
        return newPlaylist;
      } else {
        return newPlaylist.copyWithStartTimeUs(primarySnapshotStartTimeUs);
      }
    }
    List<Segment> oldSegments = oldPlaylist.segments;
    int oldPlaylistSize = oldSegments.size();
    if (!newPlaylist.isNewerThan(oldPlaylist)) {
      // Playlist has not changed.
      return oldPlaylist;
    }
    int mediaSequenceOffset = newPlaylist.mediaSequence - oldPlaylist.mediaSequence;
    if (mediaSequenceOffset <= oldPlaylistSize) {
      long adjustedNewPlaylistStartTimeUs = mediaSequenceOffset == oldPlaylistSize
          ? oldPlaylist.getEndTimeUs()
          : oldPlaylist.startTimeUs + oldSegments.get(mediaSequenceOffset).relativeStartTimeUs;
      return newPlaylist.copyWithStartTimeUs(adjustedNewPlaylistStartTimeUs);
    }
    // No segments overlap, we assume the new playlist start coincides with the primary playlist.
    return newPlaylist.copyWithStartTimeUs(primarySnapshotStartTimeUs);
  }

  /**
   * Holds all information related to a specific Media Playlist.
   */
  private final class MediaPlaylistBundle implements Loader.Callback<ParsingLoadable<HlsPlaylist>>,
      Runnable {

    private final HlsUrl playlistUrl;
    private final Loader mediaPlaylistLoader;
    private final ParsingLoadable<HlsPlaylist> mediaPlaylistLoadable;

    private HlsMediaPlaylist playlistSnapshot;
    private long lastSnapshotAccessTimeMs;
    private long blacklistUntilMs;

    public MediaPlaylistBundle(HlsUrl playlistUrl, long initialLastSnapshotAccessTimeMs) {
      this.playlistUrl = playlistUrl;
      lastSnapshotAccessTimeMs = initialLastSnapshotAccessTimeMs;
      mediaPlaylistLoader = new Loader("HlsPlaylistTracker:MediaPlaylist");

      // 注意: Loadable的构建
      // UriUtil.resolveToUri(masterPlaylist.baseUri, playlistUrl.url)
      // 可能是从master m3u8触发，获取子的m3u8文件的URL
      //
      mediaPlaylistLoadable = new ParsingLoadable<>(dataSourceFactory.createDataSource(),
          UriUtil.resolveToUri(masterPlaylist.baseUri, playlistUrl.url), C.DATA_TYPE_MANIFEST,
          playlistParser);
    }

    public HlsMediaPlaylist getPlaylistSnapshot() {
      lastSnapshotAccessTimeMs = SystemClock.elapsedRealtime();
      return playlistSnapshot;
    }

    public void release() {
      mediaPlaylistLoader.release();
    }

    public void loadPlaylist() {
      // 再次开始Loading
      blacklistUntilMs = 0;
      if (!mediaPlaylistLoader.isLoading()) {
        mediaPlaylistLoader.startLoading(mediaPlaylistLoadable, this, minRetryCount);
      }
    }

    // Loader.Callback implementation.

    @Override
    public void onLoadCompleted(ParsingLoadable<HlsPlaylist> loadable, long elapsedRealtimeMs,
        long loadDurationMs) {
      // 解析完毕
      processLoadedPlaylist((HlsMediaPlaylist) loadable.getResult());

      // 通知加载情况
      eventDispatcher.loadCompleted(loadable.dataSpec, C.DATA_TYPE_MANIFEST, elapsedRealtimeMs,
          loadDurationMs, loadable.bytesLoaded());
    }

    @Override
    public void onLoadCanceled(ParsingLoadable<HlsPlaylist> loadable, long elapsedRealtimeMs,
        long loadDurationMs, boolean released) {
      // 通知加载情况
      eventDispatcher.loadCanceled(loadable.dataSpec, C.DATA_TYPE_MANIFEST, elapsedRealtimeMs,
          loadDurationMs, loadable.bytesLoaded());
    }

    @Override
    public int onLoadError(ParsingLoadable<HlsPlaylist> loadable, long elapsedRealtimeMs,
        long loadDurationMs, IOException error) {
      // 通知加载情况
      boolean isFatal = error instanceof ParserException;
      eventDispatcher.loadError(loadable.dataSpec, C.DATA_TYPE_MANIFEST, elapsedRealtimeMs,
          loadDurationMs, loadable.bytesLoaded(), error, isFatal);
      if (isFatal) {
        return Loader.DONT_RETRY_FATAL;
      }

      // 加载出错了，是否该重试呢?
      // 如何监控这种情况呢?
      boolean shouldRetry = true;
      if (ChunkedTrackBlacklistUtil.shouldBlacklist(error)) {
        blacklistUntilMs =
            SystemClock.elapsedRealtime() + ChunkedTrackBlacklistUtil.DEFAULT_TRACK_BLACKLIST_MS;
        notifyPlaylistBlacklisting(playlistUrl,
            ChunkedTrackBlacklistUtil.DEFAULT_TRACK_BLACKLIST_MS);
        shouldRetry = primaryHlsUrl == playlistUrl && !maybeSelectNewPrimaryUrl();
      }
      return shouldRetry ? Loader.RETRY : Loader.DONT_RETRY;
    }

    // Runnable implementation.

    @Override
    public void run() {
      loadPlaylist();
    }

    // Internal methods.

    private void processLoadedPlaylist(HlsMediaPlaylist loadedMediaPlaylist) {
      // 认为: loadedMediaPlaylist 已经加载完毕，然后呢?

      HlsMediaPlaylist oldPlaylist = playlistSnapshot;
      playlistSnapshot = adjustPlaylistTimestamps(oldPlaylist, loadedMediaPlaylist);


      long refreshDelayUs = C.TIME_UNSET;
      if (oldPlaylist != playlistSnapshot) {
        if (onPlaylistUpdated(playlistUrl, playlistSnapshot)) {
          refreshDelayUs = playlistSnapshot.targetDurationUs;
        }
      } else if (!loadedMediaPlaylist.hasEndTag) {
        refreshDelayUs = playlistSnapshot.targetDurationUs / 2;
      }
      if (refreshDelayUs != C.TIME_UNSET) {
        // See HLS spec v20, section 6.3.4 for more information on media playlist refreshing.
        playlistRefreshHandler.postDelayed(this, C.usToMs(refreshDelayUs));
      }
    }

  }

}

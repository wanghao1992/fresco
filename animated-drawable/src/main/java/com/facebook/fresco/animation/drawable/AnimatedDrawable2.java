/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */
package com.facebook.fresco.animation.drawable;

import javax.annotation.Nullable;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import com.facebook.common.logging.FLog;
import com.facebook.common.time.MonotonicClock;
import com.facebook.drawee.drawable.DrawableProperties;
import com.facebook.fresco.animation.backend.AnimationBackend;
import com.facebook.fresco.animation.frame.DropFramesFrameScheduler;
import com.facebook.fresco.animation.frame.FrameScheduler;

/**
 * Experimental new animated drawable that uses a supplied
 * {@link AnimationBackend} for drawing frames.
 */
public class AnimatedDrawable2 extends Drawable implements Animatable {

  private static final Class<?> TAG = AnimatedDrawable2.class;

  private static final AnimationListener NO_OP_LISTENER = new BaseAnimationListener();

  @Nullable
  private AnimationBackend mAnimationBackend;
  @Nullable
  private FrameScheduler mFrameScheduler;

  // Animation parameters
  private volatile boolean mIsRunning;
  private long mStartTimeMs;
  private long mLastFrameAnimationTimeMs;

  // Animation statistics
  private int mDroppedFrames;

  // Listeners
  private volatile AnimationListener mAnimationListener = NO_OP_LISTENER;

  // Holder for drawable properties like alpha to be able to re-apply if the backend changes.
  // The instance is created lazily only if needed.
  @Nullable
  private DrawableProperties mDrawableProperties;

  @Nullable
  private final MonotonicClock mMonotonicClock;

  /**
   * Runnable that invalidates the drawable that will be scheduled according to the next
   * target frame.
   */
  private final Runnable mInvalidateRunnable = new Runnable() {
    @Override
    public void run() {
      invalidateSelf();
    }
  };

  public AnimatedDrawable2() {
    this(null);
  }

  public AnimatedDrawable2(@Nullable AnimationBackend animationBackend) {
    this(animationBackend, null);
  }

  public AnimatedDrawable2(
      @Nullable AnimationBackend animationBackend,
      @Nullable MonotonicClock monotonicClock) {
    mAnimationBackend = animationBackend;
    mFrameScheduler = createSchedulerForBackendAndDelayMethod(mAnimationBackend);
    mMonotonicClock = monotonicClock;
  }

  /**
   * Start the animation.
   */
  @Override
  public void start() {
    if (mIsRunning || mAnimationBackend == null || mAnimationBackend.getFrameCount() <= 1) {
      return;
    }
    mIsRunning = true;
    mStartTimeMs = now();
    mLastFrameAnimationTimeMs = -1;
    invalidateSelf();
    mAnimationListener.onAnimationStart(this);
  }

  /**
   * Stop the animation at the current frame. It can be resumed by calling {@link #start()} again.
   */
  @Override
  public void stop() {
    if (!mIsRunning) {
      return;
    }
    mIsRunning = false;
    mStartTimeMs = 0;
    mLastFrameAnimationTimeMs = -1;
    unscheduleSelf(mInvalidateRunnable);
    mAnimationListener.onAnimationStop(this);
  }

  /**
   * Check whether the animation is running.
   * @return true if the animation is currently running
   */
  @Override
  public boolean isRunning() {
    return mIsRunning;
  }

  @Override
  protected void onBoundsChange(Rect bounds) {
    super.onBoundsChange(bounds);
    if (mAnimationBackend != null) {
      mAnimationBackend.setBounds(bounds);
    }
  }

  @Override
  public void draw(Canvas canvas) {
    if (mAnimationBackend == null || mFrameScheduler == null) {
      return;
    }
    long actualRenderTimeStartMs = now();
    long animationTimeMs = mIsRunning
        ? actualRenderTimeStartMs - mStartTimeMs
        : Math.max(mLastFrameAnimationTimeMs, 0);

    // What frame should be drawn?
    int frameNumberToDraw = mFrameScheduler.getFrameNumberToRender(
        animationTimeMs,
        mLastFrameAnimationTimeMs);

    mLastFrameAnimationTimeMs = animationTimeMs;

    // Check if the animation is finished and draw last frame if so
    if (frameNumberToDraw == FrameScheduler.FRAME_NUMBER_DONE) {
      frameNumberToDraw = mAnimationBackend.getFrameCount() - 1;
      mAnimationListener.onAnimationStop(this);
    } else if (frameNumberToDraw == 0) {
      mAnimationListener.onAnimationRepeat(this);
    }

    // Notify listeners that we're about to draw a new frame and
    // that the animation might be repeated
    mAnimationListener.onAnimationFrame(this, frameNumberToDraw);

    // Draw the frame
    boolean frameDrawn = mAnimationBackend.drawFrame(this, canvas, frameNumberToDraw);

    // Log potential dropped frames
    if (!frameDrawn) {
      onFrameDropped();
    }

    if (mIsRunning) {
      // Log performance
      long actualRenderTimeEnd = now();
      if (FLog.isLoggable(FLog.VERBOSE)) {
        FLog.v(
            TAG,
            "Animation jitter: %s ms. Render time: %s ms. Frame drawn: %s",
            actualRenderTimeStartMs -
                (mFrameScheduler.getTargetRenderTimeMs(frameNumberToDraw) + mStartTimeMs),
            actualRenderTimeEnd - actualRenderTimeStartMs,
            frameDrawn);
      }
      // Schedule the next frame if needed
      scheduleNextFrame(
          mFrameScheduler.getDelayUntilNextFrameMs(actualRenderTimeEnd - mStartTimeMs));
    }
  }

  @Override
  public void setAlpha(int alpha) {
    if (mDrawableProperties == null) {
      mDrawableProperties = new DrawableProperties();
    }
    mDrawableProperties.setAlpha(alpha);
    if (mAnimationBackend != null) {
      mAnimationBackend.setAlpha(alpha);
    }
  }

  @Override
  public void setColorFilter(ColorFilter colorFilter) {
    if (mDrawableProperties == null) {
      mDrawableProperties = new DrawableProperties();
    }
    mDrawableProperties.setColorFilter(colorFilter);
    if (mAnimationBackend != null) {
      mAnimationBackend.setColorFilter(colorFilter);
    }
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  /**
   * Update the animation backend to be used for the animation.
   * This will also stop the animation.
   * In order to remove the current animation backend, call this method with null.
   *
   * @param animationBackend the animation backend to be used or null
   */
  public void setAnimationBackend(@Nullable AnimationBackend animationBackend) {
    mAnimationBackend = animationBackend;
    if (mAnimationBackend != null) {
      mFrameScheduler = new DropFramesFrameScheduler(mAnimationBackend);
      mAnimationBackend.setBounds(getBounds());
      if (mDrawableProperties != null) {
        // re-apply to the same drawable so that the animation backend is updated.
        mDrawableProperties.applyTo(this);
      }
    }
    mFrameScheduler = createSchedulerForBackendAndDelayMethod(mAnimationBackend);
    stop();
  }

  @Nullable
  public AnimationBackend getAnimationBackend() {
    return mAnimationBackend;
  }

  public long getDroppedFrames() {
    return mDroppedFrames;
  }

  public long getStartTimeMs() {
    return mStartTimeMs;
  }

  public boolean isInfiniteAnimation() {
    return mFrameScheduler != null && mFrameScheduler.isInfiniteAnimation();
  }

  /**
   * Jump immediately to the given frame number
   *
   * @param targetFrameNumber the frame number to jump to
   */
  public void jumpToFrame(int targetFrameNumber) {
    if (mAnimationBackend == null || mFrameScheduler == null) {
      return;
    }
    stop();
    // In order to jump to a given frame, we have to compute the correct start time
    mStartTimeMs = now() - mFrameScheduler.getTargetRenderTimeMs(targetFrameNumber);
    invalidateSelf();
  }

  /**
   * Get the animation duration for 1 loop by summing all frame durations.
   *
   * @return the duration of 1 animation loop in ms
   */
  public long getLoopDurationMs() {
    if (mAnimationBackend == null) {
      return 0;
    }
    if (mFrameScheduler != null) {
      return mFrameScheduler.getLoopDurationMs();
    }
    int loopDurationMs = 0;
    for (int i = 0; i < mAnimationBackend.getFrameCount(); i++) {
      loopDurationMs += mAnimationBackend.getFrameDurationMs(i);
    }
    return loopDurationMs;
  }

  /**
   * Get the number of frames for the animation.
   * If no animation backend is set, 0 will be returned.
   *
   * @return the number of frames of the animation
   */
  public int getFrameCount() {
    return mAnimationBackend == null ? 0 : mAnimationBackend.getFrameCount();
  }

  /**
   * Get the loop count of the animation.
   * The returned value is either {@link AnimationBackend#LOOP_COUNT_INFINITE} if the animation
   * is repeated infinitely or a positive integer that corresponds to the number of loops.
   * If no animation backend is set, {@link AnimationBackend#LOOP_COUNT_INFINITE}
   * will be returned.
   *
   * @return the loop count of the animation or {@link AnimationBackend#LOOP_COUNT_INFINITE}
   */
  public int getLoopCount() {
    return mAnimationBackend == null
        ? 0
        : mAnimationBackend.getLoopCount();
  }

  /**
   * Set an animation listener that is notified for various animation events.
   * @param animationListener the listener to use
   */
  public void setAnimationListener(@Nullable AnimationListener animationListener) {
    mAnimationListener = animationListener != null
        ? animationListener
        : NO_OP_LISTENER;
  }

  /**
   * Schedule the next frame to be rendered after the given delay.
   *
   * @param nextFrameDelayMs the time in ms to update the frame
   */
  private void scheduleNextFrame(long nextFrameDelayMs) {
    scheduleSelf(mInvalidateRunnable, nextFrameDelayMs);
  }

  private void onFrameDropped() {
    mDroppedFrames++;
    // we need to drop frames
    if (FLog.isLoggable(FLog.VERBOSE)) {
      FLog.v(TAG, "Dropped a frame. Count: %s", mDroppedFrames);
    }
  }

  /**
   * @return the current time in ms
   */
  private long now() {
    return mMonotonicClock == null ? SystemClock.uptimeMillis() : mMonotonicClock.now();
  }

  @Nullable
  private static FrameScheduler createSchedulerForBackendAndDelayMethod(
      @Nullable AnimationBackend animationBackend) {
    if (animationBackend == null) {
      return null;
    }
    return new DropFramesFrameScheduler(animationBackend);
  }
}

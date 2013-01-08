// Copyright 2012 Google Inc. All Rights Reserved.

package com.android.server.wm;

import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.Surface;

import java.io.PrintWriter;

public class DimLayer {
    private static final String TAG = "DimLayer";
    private static final boolean DEBUG = false;

    /** Reference to the owner of this object. */
    final DisplayContent mDisplayContent;

    /** Actual surface that dims */
    Surface mDimSurface;

    /** Last value passed to mDimSurface.setAlpha() */
    float mAlpha = 0;

    /** Last value passed to mDimSurface.setLayer() */
    int mLayer = -1;

    /** Last values passed to mDimSurface.setSize() */
    int mLastDimWidth, mLastDimHeight;

    /** True after mDimSurface.show() has been called, false after mDimSurface.hide(). */
    private boolean mShowing = false;

    /** Value of mAlpha when beginning transition to mTargetAlpha */
    float mStartAlpha = 0;

    /** Final value of mAlpha following transition */
    float mTargetAlpha = 0;

    /** Time in units of SystemClock.uptimeMillis() at which the current transition started */
    long mStartTime;

    /** Time in milliseconds to take to transition from mStartAlpha to mTargetAlpha */
    long mDuration;

    DimLayer(WindowManagerService service, int displayId) {
        if (DEBUG) Slog.v(TAG, "Ctor: displayId=" + displayId);
        mDisplayContent = service.getDisplayContentLocked(displayId);
        Surface.openTransaction();
        try {
            if (WindowManagerService.DEBUG_SURFACE_TRACE) {
                mDimSurface = new WindowStateAnimator.SurfaceTrace(service.mFxSession,
                    "DimSurface",
                    16, 16, PixelFormat.OPAQUE,
                    Surface.FX_SURFACE_DIM | Surface.HIDDEN);
            } else {
                mDimSurface = new Surface(service.mFxSession, TAG,
                    16, 16, PixelFormat.OPAQUE,
                    Surface.FX_SURFACE_DIM | Surface.HIDDEN);
            }
            if (WindowManagerService.SHOW_TRANSACTIONS ||
                    WindowManagerService.SHOW_SURFACE_ALLOC) Slog.i(TAG,
                            "  DIM " + mDimSurface + ": CREATE");
            mDimSurface.setLayerStack(displayId);
        } catch (Exception e) {
            Slog.e(WindowManagerService.TAG, "Exception creating Dim surface", e);
        } finally {
            Surface.closeTransaction();
        }
    }

    /** Return true if dim layer is showing */
    boolean isDimming() {
        return mTargetAlpha != 0;
    }

    /** Return true if in a transition period */
    boolean isAnimating() {
        return mTargetAlpha != mAlpha;
    }

    float getTargetAlpha() {
        return mTargetAlpha;
    }

    private void setAlpha(float alpha) {
        if (mAlpha != alpha) {
            if (DEBUG) Slog.v(TAG, "setAlpha alpha=" + alpha);
            try {
                mDimSurface.setAlpha(alpha);
                if (alpha == 0 && mShowing) {
                    if (DEBUG) Slog.v(TAG, "setAlpha hiding");
                    mDimSurface.hide();
                    mShowing = false;
                } else if (alpha > 0 && !mShowing) {
                    if (DEBUG) Slog.v(TAG, "setAlpha showing");
                    mDimSurface.show();
                    mShowing = true;
                }
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failure setting alpha immediately", e);
            }
            mAlpha = alpha;
        }
    }

    /**
     * @param duration The time to test.
     * @return True if the duration would lead to an earlier end to the current animation.
     */
    private boolean durationEndsEarlier(long duration) {
        return SystemClock.uptimeMillis() + duration < mStartTime + mDuration;
    }

    /** Jump to the end of the animation.
     * NOTE: Must be called with Surface transaction open. */
    void show() {
        if (isAnimating()) {
            if (DEBUG) Slog.v(TAG, "show: immediate");
            show(mLayer, mTargetAlpha, 0);
        }
    }

    /**
     * Begin an animation to a new dim value.
     * NOTE: Must be called with Surface transaction open.
     *
     * @param layer The layer to set the surface to.
     * @param alpha The dim value to end at.
     * @param duration How long to take to get there in milliseconds.
     */
    void show(int layer, float alpha, long duration) {
        if (DEBUG) Slog.v(TAG, "show: layer=" + layer + " alpha=" + alpha
                + " duration=" + duration);
        if (mDimSurface == null) {
            Slog.e(TAG, "show: no Surface");
            // Make sure isAnimating() returns false.
            mTargetAlpha = mAlpha = 0;
            return;
        }

        // Set surface size to screen size.
        final DisplayInfo info = mDisplayContent.getDisplayInfo();
        // Multiply by 1.5 so that rotating a frozen surface that includes this does not expose a
        // corner.
        final int dw = (int) (info.logicalWidth * 1.5);
        final int dh = (int) (info.logicalHeight * 1.5);
        // back off position so 1/4 of Surface is before and 1/4 is after.
        final float xPos = -1 * dw / 6;
        final float yPos = -1 * dh / 6;

        if (mLastDimWidth != dw || mLastDimHeight != dh || mLayer != layer) {
            try {
                mDimSurface.setPosition(xPos, yPos);
                mDimSurface.setSize(dw, dh);
                mDimSurface.setLayer(layer);
            } catch (RuntimeException e) {
                Slog.w(TAG, "Failure setting size or layer", e);
            }
            mLastDimWidth = dw;
            mLastDimHeight = dh;
            mLayer = layer;
        }

        long curTime = SystemClock.uptimeMillis();
        final boolean animating = isAnimating();
        if ((animating && (mTargetAlpha != alpha || durationEndsEarlier(duration)))
                || (!animating && mAlpha != alpha)) {
            if (duration <= 0) {
                // No animation required, just set values.
                setAlpha(alpha);
            } else {
                // Start or continue animation with new parameters.
                mStartAlpha = mAlpha;
                mStartTime = curTime;
                mDuration = duration;
            }
        }
        if (DEBUG) Slog.v(TAG, "show: mStartAlpha=" + mStartAlpha + " mStartTime=" + mStartTime);
        mTargetAlpha = alpha;
    }

    /** Immediate hide.
     * NOTE: Must be called with Surface transaction open. */
    void hide() {
        if (mShowing) {
            if (DEBUG) Slog.v(TAG, "hide: immediate");
            hide(0);
        }
    }

    /**
     * Gradually fade to transparent.
     * NOTE: Must be called with Surface transaction open.
     *
     * @param duration Time to fade in milliseconds.
     */
    void hide(long duration) {
        if (mShowing && (mTargetAlpha != 0 || durationEndsEarlier(duration))) {
            if (DEBUG) Slog.v(TAG, "hide: duration=" + duration);
            show(mLayer, 0, duration);
        }
    }

    /**
     * Advance the dimming per the last #show(int, float, long) call.
     * NOTE: Must be called with Surface transaction open.
     *
     * @return True if animation is still required after this step.
     */
    boolean stepAnimation() {
        if (mDimSurface == null) {
            Slog.e(TAG, "stepAnimation: null Surface");
            // Ensure that isAnimating() returns false;
            mTargetAlpha = mAlpha = 0;
            return false;
        }

        if (isAnimating()) {
            final long curTime = SystemClock.uptimeMillis();
            final float alphaDelta = mTargetAlpha - mStartAlpha;
            float alpha = mStartAlpha + alphaDelta * (curTime - mStartTime) / mDuration;
            if (alphaDelta > 0 && alpha > mTargetAlpha ||
                    alphaDelta < 0 && alpha < mTargetAlpha) {
                // Don't exceed limits.
                alpha = mTargetAlpha;
            }
            if (DEBUG) Slog.v(TAG, "stepAnimation: curTime=" + curTime + " alpha=" + alpha);
            setAlpha(alpha);
        }

        return isAnimating();
    }

    /** Cleanup */
    void destroySurface() {
        if (DEBUG) Slog.v(TAG, "destroySurface.");
        if (mDimSurface != null) {
            mDimSurface.destroy();
            mDimSurface = null;
        }
    }

    public void printTo(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mDimSurface="); pw.println(mDimSurface);
        pw.print(prefix); pw.print(" mLayer="); pw.print(mLayer);
                pw.print(" mAlpha="); pw.println(mAlpha);
        pw.print(prefix); pw.print("mLastDimWidth="); pw.print(mLastDimWidth);
                pw.print(" mLastDimWidth="); pw.println(mLastDimWidth);
        pw.print(prefix); pw.print("Last animation: mStartTime="); pw.print(mStartTime);
                pw.print(" mDuration="); pw.print(mDuration);
                pw.print(" curTime="); pw.println(SystemClock.uptimeMillis());
        pw.print(" mStartAlpha="); pw.println(mStartAlpha);
                pw.print(" mTargetAlpha="); pw.print(mTargetAlpha);
    }
}

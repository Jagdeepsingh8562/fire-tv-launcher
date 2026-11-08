package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: the photo slideshow of {@link SaverView}. Chosen with:
 *   settings put secure screensaver_components com.gathin.tvhome/com.gathin.tvhome.PhotoDream
 * and Amazon's back with:
 *   settings put secure screensaver_components com.amazon.ftv.screensaver/.app.services.ScreensaverService
 */
public class PhotoDream extends DreamService {
    private SaverView saver;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        saver = new SaverView(this);
        setContentView(saver);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        saver.start();
    }

    @Override
    public void onDreamingStopped() {
        saver.stop();
        super.onDreamingStopped();
    }
}

package com.listinghound.app;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small listing photos for the lists, loaded in the background and kept in memory. */
final class Thumbs {
    private Thumbs() {}

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void load(ImageView v, String url) {
        if (url == null || url.isEmpty()) {
            v.setVisibility(View.GONE);
            return;
        }
        v.setVisibility(View.VISIBLE);
        v.setTag(url);
        Bitmap b = CACHE.get(url);
        if (b != null) {
            v.setImageBitmap(b);
            return;
        }
        v.setImageDrawable(null);
        POOL.execute(() -> {
            Bitmap got = Fetcher.thumbnail(url);
            if (got == null) return;
            CACHE.put(url, got);
            MAIN.post(() -> {
                if (url.equals(v.getTag())) v.setImageBitmap(got);
            });
        });
    }
}

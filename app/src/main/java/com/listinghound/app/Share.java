package com.listinghound.app;

import android.content.Context;
import android.content.Intent;

/** Sends a listing's link to WhatsApp, Messages, email, etc. */
final class Share {
    private Share() {}

    static String text(Listing l) {
        StringBuilder b = new StringBuilder();
        if (!l.priceOrBlank().isEmpty()) b.append(l.price).append(" · ");
        b.append(l.title);
        if (!l.city.isEmpty()) b.append(" (").append(l.city).append(')');
        b.append('\n').append(l.url());
        return b.toString();
    }

    static Intent chooser(Listing l) {
        return chooser(l.title, text(l));
    }

    static Intent chooser(String subject, String text) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, subject)
                .putExtra(Intent.EXTRA_TEXT, text);
        return Intent.createChooser(send, "Share listing");
    }

    static void share(Context c, Listing l) {
        Intent i = chooser(l);
        if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}

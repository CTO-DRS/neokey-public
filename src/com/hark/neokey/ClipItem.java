package com.hark.neokey;

/** Lightweight row for lists: holds only a preview, never the full text. */
final class ClipItem {
    static final int PREVIEW = 300;

    long id;
    String preview;     // first PREVIEW code points (SQLite substr counts code points, so emoji stay whole)
    int len;            // full length in chars (UTF-16 units as Java counts them)
    String title;       // optional, null when none
    boolean pinned, fav, archived;
    int pinOrder, kind;
    long created, modified, used;
    int matchStart = -1, matchEnd = -1;   // search hit inside preview, -1 when none

    boolean isLong() { return len > PREVIEW; }
}

package com.nwd.btmusic.aidl;

interface BtmusicAidlCallback {
    void onBtmusicPlayInfoChange(boolean isPlaying, boolean z2, int currentPosition, int totalSize,
        String name, String artist, String album, String str4, int i3, int i4);
}

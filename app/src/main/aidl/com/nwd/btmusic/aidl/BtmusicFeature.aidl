package com.nwd.btmusic.aidl;

import com.nwd.btmusic.aidl.BtmusicAidlCallback;

interface BtmusicFeature {
    void registerBtmusicCallback(BtmusicAidlCallback callback);
    void unregisterBtmusicCallback(BtmusicAidlCallback callback);
    void requestCurrentBtMusicInfo();
}

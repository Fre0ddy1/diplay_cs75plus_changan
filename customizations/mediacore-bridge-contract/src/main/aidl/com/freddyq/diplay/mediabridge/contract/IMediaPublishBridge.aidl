package com.freddyq.diplay.mediabridge.contract;
import android.os.Bundle;
/** NEW local bridge protocol. These are not OEM MediaCore transaction numbers. */
interface IMediaPublishBridge {
    Bundle begin(IBinder owner);
    Bundle publish(IBinder owner, long sequence, in Bundle media, in byte[] jpeg);
    Bundle end(IBinder owner);
    Bundle readback();
}

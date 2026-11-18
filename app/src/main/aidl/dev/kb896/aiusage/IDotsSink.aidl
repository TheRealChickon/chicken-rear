package dev.kb896.aiusage;

import android.os.Bundle;

/** Where DotsService's pictures go (rear/dots/DotsClient). */
oneway interface IDotsSink {
    /** Once per watch: "memory" a SharedMemory of "slots" squares of "size" x "size" RGBA pixels, premultiplied. */
    void frames(in Bundle memory) = 1;
    /** Slot [slot] holds picture [seq], whole. */
    void frame(int slot, long seq) = 2;
    /** No character, and why (logged; nothing stands in for it). */
    void gone(String why) = 3;
}

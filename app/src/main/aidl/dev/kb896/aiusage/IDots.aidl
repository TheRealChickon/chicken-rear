package dev.kb896.aiusage;

import dev.kb896.aiusage.IDotsSink;

/**
 * OpenAI's Dots character, drawn by the ChatGPT app's own renderer in this app's ":dots" process
 * (rear/dots/DotsService). Its clients are the shell (the back's usage page) and the settings' preview.
 */
interface IDots {
    /** Draw [character] (an id in ChatGPT's character list) in a square of [size] px: frames go to [sink] until stop(). */
    oneway void watch(IDotsSink sink, String character, int size) = 1;
    oneway void stop(IDotsSink sink) = 2;
    /** Its own signature move, now. */
    oneway void signature() = 3;
    /** OpenAI's own reduced motion on or off (spent: it barely stirs). */
    oneway void calm(boolean on) = 4;
    /** Why there is no character, "" when there is one or nothing was asked yet. */
    String state() = 5;
}

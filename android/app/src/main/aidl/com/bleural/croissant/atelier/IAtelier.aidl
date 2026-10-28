package com.bleural.croissant.atelier;

import android.os.SharedMemory;
import com.bleural.croissant.atelier.IAtelierHost;

/**
 * The launcher's calls into the engine process (atelier/AtelierService.kt), every one
 * oneway: an engine's call answers on its host with the same req. [engine] is the launcher's
 * number for one app's engine. Large texts (the code, an answer) come in SharedMemory.
 */
oneway interface IAtelier {
    void start(int engine, IAtelierHost host, in SharedMemory code, boolean words, int req);
    void draw(int engine, int req);
    void saved(int engine, int req);
    void restore(int engine, String state, int req);
    void call(int engine, int gen, int handler, String value, int req);
    void hear(int engine, String words, int req);
    void tool(int engine, String name, String args, int req);
    /** Stop the call [req] where it is: the launcher has given up on it. */
    void interrupt(int engine, int req);
    /** The answer to the engine's host call [call]. */
    void answer(int engine, int call, in SharedMemory value);
    void refuse(int engine, int call, String why);
    void stop(int engine);
}

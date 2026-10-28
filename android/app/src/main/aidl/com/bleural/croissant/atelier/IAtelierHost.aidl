package com.bleural.croissant.atelier;

/**
 * An engine's calls back into the launcher, every one oneway, never trusted: the launcher
 * checks each (atelier/IsolatedEngine.kt). [done] and [failed] end the launcher's call [req];
 * [ask] and [fetch] are the script's, answered with IAtelier.answer or refuse by [call].
 */
oneway interface IAtelierHost {
    void done(int req, String value);
    /** [line] is -1 when there is none. */
    void failed(int req, String message, String file, int line);
    void ask(int call, String text);
    void fetch(int call, String url);
    void cue(String name);
    void log(String level, String line);
}

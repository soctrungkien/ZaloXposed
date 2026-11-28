package io.github.electroheavenvn.zaloxposed.utilities.richtext;

import java.util.HashSet;
import java.util.Set;

import io.github.electroheavenvn.zaloxposed.utilities.Logger;

/**
 * Small bridge from the formatting engine to the module logger. Repeat failures
 * are reported once per process so a busy composer cannot flood the log.
 */
final class LogAdapter
{
    private LogAdapter() { }

    private static final Set<String> REPORTED = new HashSet<>();

    static void i(String tag, String message)
    {
        Logger.i("[" + tag + "] " + message);
    }

    static void w(String tag, String message)
    {
        Logger.w("[" + tag + "] " + message);
    }

    static void e(String tag, String message)
    {
        Logger.e("[" + tag + "] " + message);
    }

    static synchronized void swallowedOnce(String tag, String message, Throwable failure)
    {
        String key = tag + '|' + message;
        if (!REPORTED.add(key))
            return;
        Logger.e("[" + tag + "] " + message, failure);
    }
}

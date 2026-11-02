package com.freddyq.diplay.chainlistener;

import java.util.*;
import java.util.regex.Pattern;

/** Only selects existing log text; never sends a request to any OEM service. */
final class LogSelection {
    static final long DURATION_MILLIS = 10 * 60 * 1000L;
    static final String[] TAGS = {"TinnoveSmartScene[1.0.2.r]", "C_VCar", "TCSLog_VirtualCarPropertyService",
        "Ps-Ver@230625", "VehUtils#2.4.1106", "DefHal#2.4.1106", "CarflowDualManager",
        "Dual_WtCarDualInteractiveServiceIncallSDK", "Double-MediaProxy", "Double-InteractiveImpl0803",
        "Double-Media0803", "MediaCoreServiceManager", "wt_local_media_", "platform3_1.0.3.m", "vehicle-setting"};
    private static final Pattern MEDIA = Pattern.compile("(?i)(musicdata|mediainfochanged|onmediainfochanged|onmediachange|onproviderchanged|providerType|playstate|onpause|onstart|onprogress|getimagesformusic|bitmapchangecolor|send_media|send_music|send currentmusicdata|send media |media source :|sendmedia(playtime|album)|send album |sendalbumdata|filename =|initrhythmcarset|ongroupvolumechanged|mcurrentmediavolume)");
    private static final Pattern PROPERTY = Pattern.compile("(?i)(314003a6|394003cf|314103dc|21403408|2940348a|2141fa05|commonWrite:\\s*(0c14|0c19|3405))");
    private static final Pattern ADDRESS = Pattern.compile("(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b");
    private static final Pattern QUERY = Pattern.compile("(https?://[^\\s\"'<>?]+)\\?[^\\s\"'<>]*");
    static boolean keep(String line) { return MEDIA.matcher(line).find() || PROPERTY.matcher(line).find(); }
    static String redact(String line) {
        String result = ADDRESS.matcher(line).replaceAll("[BT-address]");
        return QUERY.matcher(result).replaceAll("$1?[query-redacted]");
    }
    static List<String> command(String since) {
        List<String> args = new ArrayList<>(Arrays.asList("/system/bin/logcat", "-b", "all", "-v", "threadtime", "-T", since, "*:S"));
        for (String tag : TAGS) args.add(tag + ":V");
        return args;
    }
}

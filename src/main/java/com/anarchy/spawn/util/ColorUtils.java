package com.anarchy.spawn.util;

import net.md_5.bungee.api.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ColorUtils {

    // Matches &#RRGGBB, #RRGGBB, and <#RRGGBB>
    private static final Pattern HEX_PATTERN = Pattern.compile("(?:&#|#|<#)([A-Fa-f0-9]{6})>?");

    public static String colorize(String message) {
        if (message == null || message.isEmpty()) return "";

        Matcher matcher = HEX_PATTERN.matcher(message);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hexCode = matcher.group(1);
            try {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(ChatColor.of("#" + hexCode).toString()));
            } catch (NoSuchMethodError | Exception e) {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);

        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }
}

package com.magmaguy.resourcepackmanager.utils;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

public class ChatColorConverter {
    private ChatColorConverter() {
    }

    public static String convert(String string) {
        if (string == null) return "";
        return ChatColor.translateAlternateColorCodes('&', string);
    }

    public static List<String> convert(List<?> list) {
        if (list == null) return new ArrayList<>();
        List<String> convertedList = new ArrayList<>();
        for (Object value : list)
            convertedList.add(convert(value + ""));
        return convertedList;
    }
}

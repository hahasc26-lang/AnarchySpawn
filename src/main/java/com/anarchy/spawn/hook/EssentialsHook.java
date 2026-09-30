package com.anarchy.spawn.hook;

import com.anarchy.spawn.AnarchySpawn;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class EssentialsHook {

    private static boolean isPresent = false;

    public static boolean init(AnarchySpawn plugin) {
        Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
        isPresent = (ess != null && ess.isEnabled());
        return isPresent;
    }

    public static boolean isPresent() {
        return isPresent;
    }
}

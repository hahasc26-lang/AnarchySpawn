package com.anarchy.spawn.listener;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.util.FoliaScheduler;
import com.anarchy.spawn.util.ModrinthAutoUpdater;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;

/**
 * 管理员进服时提示插件存在新版本（按玩家客户端语言输出）
 */
public class UpdateNotifyListener implements Listener {

    /** 进服后延迟发送，避免被其他插件的欢迎消息刷掉 */
    private static final long NOTIFY_DELAY_TICKS = 60L;

    private final AnarchySpawn plugin;

    public UpdateNotifyListener(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isUpdaterEnabled() || !plugin.getConfigManager().isUpdaterNotifyOnJoin()) {
            return;
        }
        ModrinthAutoUpdater updater = plugin.getAutoUpdater();
        if (updater == null || !updater.hasPendingUpdate()) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.hasPermission("anarchyspawn.update") && !player.hasPermission("anarchyspawn.admin")) {
            return;
        }

        FoliaScheduler.runForPlayerLater(plugin, player, () -> {
            Map<String, String> placeholders = updater.getPendingPlaceholders();
            if (!player.isOnline() || placeholders == null) {
                return;
            }
            String path = updater.isPendingUpdateDownloaded()
                    ? "updater.join-notify-downloaded"
                    : "updater.join-notify";
            plugin.getMessageManager().sendMessage(player, path, placeholders);
        }, NOTIFY_DELAY_TICKS);
    }
}


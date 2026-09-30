package com.anarchy.spawn.config;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.util.ColorUtils;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MessageManager {

    private final AnarchySpawn plugin;
    private final Map<String, FileConfiguration> languages = new HashMap<>();
    private FileConfiguration defaultMessages;
    private FileConfiguration serverMessages;
    private FileConfiguration clientDefaultMessages;
    private String defaultPrefix = "";

    public MessageManager(AnarchySpawn plugin) {
        this.plugin = plugin;
    }

    public void loadMessages() {
        languages.clear();
        File langFolder = new File(plugin.getDataFolder(), "languages");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        // Save bundled default language files if not present
        saveDefaultLanguageFile("zh_CN.yml");
        saveDefaultLanguageFile("zh_TW.yml");
        saveDefaultLanguageFile("en_US.yml");

        // Load all .yml files in languages directory
        File[] files = langFolder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                String fileName = file.getName();
                String langKey = fileName.substring(0, fileName.length() - 4).toLowerCase(Locale.ROOT);
                try {
                    YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
                    languages.put(langKey, cfg);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to load language file: " + fileName + " (" + e.getMessage() + ")");
                }
            }
        }

        // 1. 服务端语言配置 (Server Language)
        String configuredServer = plugin.getConfigManager().getServerLanguage();
        if (configuredServer == null || configuredServer.isEmpty()) {
            configuredServer = "zh_CN";
        }
        String serverKey = configuredServer.toLowerCase(Locale.ROOT).replace("-", "_");
        this.serverMessages = languages.get(serverKey);
        if (this.serverMessages == null && !languages.isEmpty()) {
            this.serverMessages = languages.values().iterator().next();
        }
        if (this.serverMessages == null) {
            this.serverMessages = new YamlConfiguration();
        }

        // 2. 客户端默认语言配置 (Client Default Language)
        String configuredClientDefault = plugin.getConfigManager().getClientDefaultLanguage();
        if (configuredClientDefault == null || configuredClientDefault.isEmpty()) {
            configuredClientDefault = configuredServer;
        }
        String clientKey = configuredClientDefault.toLowerCase(Locale.ROOT).replace("-", "_");
        this.clientDefaultMessages = languages.get(clientKey);
        if (this.clientDefaultMessages == null) {
            this.clientDefaultMessages = this.serverMessages;
        }

        this.defaultMessages = this.serverMessages;
        this.defaultPrefix = ColorUtils.colorize(serverMessages.getString("prefix", "&#FF5555&l[&#FFAA00AnarchySpawn&#FF5555&l] &r"));
    }

    private void saveDefaultLanguageFile(String resourceName) {
        File targetFile = new File(plugin.getDataFolder(), "languages" + File.separator + resourceName);
        if (!targetFile.exists()) {
            try {
                plugin.saveResource("languages/" + resourceName, false);
            } catch (Exception e) {
                // If saveResource fails, try copying stream manually
                try (InputStream in = plugin.getResource("languages/" + resourceName)) {
                    if (in != null) {
                        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                        cfg.save(targetFile);
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    public FileConfiguration resolveConfig(CommandSender sender) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            if (plugin.getConfigManager().isAutoLanguage()) {
                String playerLocale = getPlayerLocale(player);
                if (playerLocale != null && !playerLocale.isEmpty()) {
                    String normLocale = playerLocale.toLowerCase(Locale.ROOT).replace("-", "_");

                    // Exact match (e.g. "zh_tw", "zh_cn", "en_us")
                    if (languages.containsKey(normLocale)) {
                        return languages.get(normLocale);
                    }

                    // Dialect & Region mapping
                    if (normLocale.contains("tw") || normLocale.contains("hk") || normLocale.contains("mo") || normLocale.contains("hant")) {
                        if (languages.containsKey("zh_tw")) {
                            return languages.get("zh_tw");
                        }
                    }
                    if (normLocale.startsWith("zh") || normLocale.contains("cn") || normLocale.contains("sg") || normLocale.contains("hans")) {
                        if (languages.containsKey("zh_cn")) {
                            return languages.get("zh_cn");
                        }
                    }
                    if (normLocale.startsWith("en")) {
                        if (languages.containsKey("en_us")) {
                            return languages.get("en_us");
                        }
                    }

                    // Generic 2-letter fallback (e.g. "en", "zh")
                    String langPrefix = normLocale.contains("_") ? normLocale.split("_")[0] : normLocale;
                    for (Map.Entry<String, FileConfiguration> entry : languages.entrySet()) {
                        if (entry.getKey().startsWith(langPrefix)) {
                            return entry.getValue();
                        }
                    }
                }
            }
            // 客户端未匹配或 auto-language 未开启时，使用客户端默认语言配置
            return clientDefaultMessages != null ? clientDefaultMessages : serverMessages;
        }

        // 服务端 (控制台、后台日志、非玩家) 使用服务端语言配置
        return serverMessages != null ? serverMessages : defaultMessages;
    }

    @SuppressWarnings("deprecation")
    private String getPlayerLocale(Player player) {
        if (player == null) return null;
        try {
            return player.getLocale();
        } catch (Throwable t) {
            try {
                java.lang.reflect.Method method = player.spigot().getClass().getMethod("getLocale");
                return (String) method.invoke(player.spigot());
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public String getPrefix(CommandSender sender) {
        FileConfiguration cfg = resolveConfig(sender);
        if (cfg != null && cfg.contains("prefix")) {
            return ColorUtils.colorize(cfg.getString("prefix"));
        }
        return defaultPrefix;
    }

    public boolean hasMessage(CommandSender sender, String path) {
        FileConfiguration cfg = resolveConfig(sender);
        if (checkConfigHasMessage(cfg, path)) return true;
        if (sender instanceof Player && checkConfigHasMessage(clientDefaultMessages, path)) return true;
        return checkConfigHasMessage(serverMessages != null ? serverMessages : defaultMessages, path);
    }

    private boolean checkConfigHasMessage(FileConfiguration cfg, String path) {
        if (cfg == null || !cfg.contains(path)) return false;
        if (cfg.isList(path)) {
            return !cfg.getStringList(path).isEmpty();
        }
        String s = cfg.getString(path);
        return s != null && !s.trim().isEmpty();
    }

    public String getRaw(CommandSender sender, String path) {
        FileConfiguration cfg = resolveConfig(sender);
        if (cfg != null && cfg.contains(path)) {
            return cfg.getString(path);
        }
        if (sender instanceof Player && clientDefaultMessages != null && clientDefaultMessages.contains(path)) {
            return clientDefaultMessages.getString(path);
        }
        if (serverMessages != null && serverMessages.contains(path)) {
            return serverMessages.getString(path);
        }
        if (defaultMessages != null && defaultMessages.contains(path)) {
            return defaultMessages.getString(path);
        }
        return "&cMissing message: " + path;
    }

    public String getRaw(String path) {
        return getRaw(null, path);
    }

    public String getFormatted(CommandSender sender, String path, Map<String, String> placeholders) {
        String msg = getRaw(sender, path);
        if (msg == null) msg = "";
        return formatString(sender, msg, placeholders);
    }

    public String getFormatted(String path, Map<String, String> placeholders) {
        return getFormatted(null, path, placeholders);
    }

    public java.util.List<String> getFormattedList(CommandSender sender, String path, Map<String, String> placeholders) {
        FileConfiguration cfg = resolveConfig(sender);
        java.util.List<String> rawList = extractList(cfg, path);

        if (rawList == null && sender instanceof Player) {
            rawList = extractList(clientDefaultMessages, path);
        }
        if (rawList == null) {
            rawList = extractList(serverMessages != null ? serverMessages : defaultMessages, path);
        }

        if (rawList == null || rawList.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        java.util.List<String> result = new java.util.ArrayList<>(rawList.size());
        for (String line : rawList) {
            result.add(formatString(sender, line, placeholders));
        }
        return result;
    }

    private java.util.List<String> extractList(FileConfiguration cfg, String path) {
        if (cfg == null || !cfg.contains(path)) return null;
        if (cfg.isList(path)) {
            return cfg.getStringList(path);
        }
        String str = cfg.getString(path);
        if (str != null && !str.trim().isEmpty()) {
            return java.util.Arrays.asList(str.split("\\r?\\n"));
        }
        return null;
    }

    private String formatString(CommandSender sender, String raw, Map<String, String> placeholders) {
        if (raw == null) return "";
        String msg = raw;
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    msg = msg.replace("{" + entry.getKey() + "}", entry.getValue());
                }
            }
        }
        if (!msg.contains("{prefix}")) {
            // No prefix placeholder specified, leave as is
        } else {
            msg = msg.replace("{prefix}", getPrefix(sender));
        }
        return ColorUtils.colorize(msg);
    }

    public void sendMessage(CommandSender sender, String path, Map<String, String> placeholders) {
        if (sender == null) return;
        if (!hasMessage(sender, path)) return;

        FileConfiguration cfg = resolveConfig(sender);
        boolean isList = (cfg != null && cfg.isList(path)) || (defaultMessages != null && defaultMessages.isList(path));

        if (isList) {
            java.util.List<String> lines = getFormattedList(sender, path, placeholders);
            for (String line : lines) {
                if (line.startsWith("[prefix]")) {
                    sender.sendMessage(getPrefix(sender) + line.substring(8));
                } else {
                    sender.sendMessage(line);
                }
            }
        } else {
            String raw = getRaw(sender, path);
            if (raw == null || raw.trim().isEmpty()) return;
            if (raw.contains("\n")) {
                java.util.List<String> lines = getFormattedList(sender, path, placeholders);
                for (String line : lines) {
                    sender.sendMessage(line);
                }
            } else {
                String formatted = formatString(sender, raw, placeholders);
                if (formatted.startsWith("[noprefix]")) {
                    sender.sendMessage(formatted.substring(10));
                } else if (formatted.startsWith("[prefix]")) {
                    sender.sendMessage(getPrefix(sender) + formatted.substring(8));
                } else {
                    sender.sendMessage(getPrefix(sender) + formatted);
                }
            }
        }
    }

    public void sendMessage(CommandSender sender, String path) {
        sendMessage(sender, path, null);
    }

    public void broadcastMessage(String path, Map<String, String> placeholders) {
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (hasMessage(online, path)) {
                sendMessage(online, path, placeholders);
            }
        }
        if (hasMessage(null, path)) {
            sendMessage(org.bukkit.Bukkit.getConsoleSender(), path, placeholders);
        }
    }

    public void sendPermissionMessage(String permission, String path, Map<String, String> placeholders) {
        for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(permission) && hasMessage(online, path)) {
                sendMessage(online, path, placeholders);
            }
        }
    }


    public String getLocalizedBiome(CommandSender sender, String biomeName) {
        if (biomeName == null || biomeName.isEmpty()) return "Unknown";
        String clean = biomeName.trim().toUpperCase(Locale.ROOT);
        String path = "biomes." + clean;

        FileConfiguration cfg = resolveConfig(sender);
        if (cfg != null && cfg.contains(path)) {
            return cfg.getString(path);
        }
        if (defaultMessages != null && defaultMessages.contains(path)) {
            return defaultMessages.getString(path);
        }

        // Format neatly like PLAINS -> Plains, OLD_GROWTH_BIRCH_FOREST -> Old Growth Birch Forest
        String[] words = clean.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) continue;
            sb.append(Character.toUpperCase(words[i].charAt(0)));
            if (words[i].length() > 1) {
                sb.append(words[i].substring(1).toLowerCase(Locale.ROOT));
            }
            if (i < words.length - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    public String getLocalizedDirection(CommandSender sender, float yaw) {
        float rot = (yaw - 90) % 360;
        if (rot < 0) rot += 360.0f;

        String key;
        if (rot >= 337.5 || rot < 22.5) key = "W";
        else if (rot >= 22.5 && rot < 67.5) key = "NW";
        else if (rot >= 67.5 && rot < 112.5) key = "N";
        else if (rot >= 112.5 && rot < 157.5) key = "NE";
        else if (rot >= 157.5 && rot < 202.5) key = "E";
        else if (rot >= 202.5 && rot < 247.5) key = "SE";
        else if (rot >= 247.5 && rot < 292.5) key = "S";
        else key = "SW";

        String path = "directions." + key;
        FileConfiguration cfg = resolveConfig(sender);
        if (cfg != null && cfg.contains(path)) {
            return cfg.getString(path);
        }
        if (defaultMessages != null && defaultMessages.contains(path)) {
            return defaultMessages.getString(path);
        }

        switch (key) {
            case "N": return "北";
            case "NE": return "东北";
            case "E": return "东";
            case "SE": return "东南";
            case "S": return "南";
            case "SW": return "西南";
            case "W": return "西";
            case "NW": return "西北";
            default: return key;
        }
    }

    public void sendRawMessage(CommandSender sender, String message) {
        if (sender != null && message != null && !message.isEmpty()) {
            sender.sendMessage(ColorUtils.colorize(message));
        }
    }

    @SuppressWarnings("deprecation")
    public void sendTitle(Player player, String titlePath, String subtitlePath, Map<String, String> placeholders, int fadeIn, int stay, int fadeOut) {
        if (player == null || !plugin.getConfigManager().isTitleEnabled()) return;
        String title = hasMessage(player, titlePath) ? getFormatted(player, titlePath, placeholders) : "";
        String subtitle = hasMessage(player, subtitlePath) ? getFormatted(player, subtitlePath, placeholders) : "";
        if (title.trim().isEmpty() && subtitle.trim().isEmpty()) return;
        try {
            player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("deprecation")
    public void sendActionBar(Player player, String path, Map<String, String> placeholders) {
        if (player == null || !plugin.getConfigManager().isActionbarEnabled()) return;
        if (!hasMessage(player, path)) return;
        String msg = getFormatted(player, path, placeholders);
        if (msg.trim().isEmpty()) return;
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(msg));
        } catch (Throwable e) {
            try {
                player.sendMessage(msg);
            } catch (Throwable ignored) {}
        }
    }

    public String getPrefix() {
        return defaultPrefix;
    }
}

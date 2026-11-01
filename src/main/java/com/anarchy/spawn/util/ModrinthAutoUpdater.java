package com.anarchy.spawn.util;

import com.anarchy.spawn.AnarchySpawn;
import com.anarchy.spawn.config.ConfigManager;
import com.anarchy.spawn.config.MessageManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * 基于 Modrinth API v2 的自动更新管理器
 * <p>
 * 兼容设计目标：
 * <ul>
 * <li>JDK: 纯原生兼容 Java 8 至最新 Java 版本（无任何 Java 9+ API 依赖，杜绝 readAllBytes）</li>
 * <li>服务端: 完整兼容 Minecraft 1.12.x 至 26.3+ (Spigot / Paper / Purpur / Folia)</li>
 * <li>三方库: 采用反射与降级机制适配旧版 1.12.x Gson 与现代 Gson 2.8.6+</li>
 * <li>编译警告清零: 针对新版 Paper 消除 getDescription() 与 ChatColor 废弃警告及多余注解</li>
 * </ul>
 */
public final class ModrinthAutoUpdater {

    public enum Status {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        DOWNLOADED,
        ALREADY_DOWNLOADED,
        FAILED
    }

    @FunctionalInterface
    public interface Reporter {
        void report(Level level, String path, Map<String, String> placeholders);
    }

    private static final String API_BASE = "https://api.modrinth.com/v2";
    /** 服务端支持的加载器过滤器标签 */
    private static final String LOADERS_FILTER = "[\"bukkit\",\"spigot\",\"paper\",\"purpur\",\"folia\"]";
    private static final long TICKS_PER_HOUR = 20L * 60L * 60L;

    /** 匹配 § 或 & 引导的传统颜色与格式代码（用于替代已被废弃的 ChatColor.stripColor） */
    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)[§&][0-9a-fk-or]");

    private final AnarchySpawn plugin;
    private final String currentVersion;
    private final ExecutorService executor;

    /** 正在进行中的检查任务（防止并发重复发起） */
    private CompletableFuture<Status> inFlight;
    /** 定时检查任务引用 */
    private volatile Object timerTask;
    /** 最近一次检测到的可用新版本 */
    private volatile RemoteVersion pendingUpdate;
    /** 本次运行期间已成功落盘的版本号 */
    private volatile String downloadedVersion;

    public ModrinthAutoUpdater(AnarchySpawn plugin) {
        this.plugin = plugin;
        this.currentVersion = resolvePluginVersion(plugin);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AnarchySpawn-Updater");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 跨版本兼容获取插件版本（Paper 1.20+ getPluginMeta() / 1.12.2 getDescription()）
     * 消除直接调用 getDescription() 产生的废弃警告
     */
    @SuppressWarnings("deprecation")
    private static String resolvePluginVersion(AnarchySpawn plugin) {
        try {
            // Paper 1.20.4+ 现代接口
            Method getPluginMeta = plugin.getClass().getMethod("getPluginMeta");
            Object meta = getPluginMeta.invoke(plugin);
            if (meta != null) {
                Method getVersion = meta.getClass().getMethod("getVersion");
                return (String) getVersion.invoke(meta);
            }
        } catch (Throwable ignored) {}
        // 1.12.2 ~ 旧版通用接口
        return plugin.getDescription().getVersion();
    }

    // =========================================================================
    // 生命周期
    // =========================================================================

    public void start(boolean initial) {
        stopTimer();
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isUpdaterEnabled()) {
            return;
        }

        if (initial && cfg.isUpdaterCheckOnStartup()) {
            checkAsync(cfg.isUpdaterAutoDownload(), consoleReporter());
        }

        int hours = cfg.getUpdaterCheckIntervalHours();
        if (hours > 0) {
            long period = hours * TICKS_PER_HOUR;
            // 调度到统一跨平台调度器中执行
            this.timerTask = FoliaScheduler.runTimerGlobalTask(plugin,
                    () -> checkAsync(plugin.getConfigManager().isUpdaterAutoDownload(), consoleReporter()),
                    period, period);
        }
    }

    public void shutdown() {
        stopTimer();
        executor.shutdownNow();
    }

    private void stopTimer() {
        Object task = this.timerTask;
        this.timerTask = null;
        if (task != null) {
            FoliaScheduler.cancelTask(task);
        }
    }

    // =========================================================================
    // 对外 API
    // =========================================================================

    public synchronized CompletableFuture<Status> checkAsync(boolean download, Reporter reporter) {
        if (inFlight != null && !inFlight.isDone()) {
            reporter.report(Level.INFO, "updater.already-checking", basePlaceholders());
            return inFlight;
        }
        CompletableFuture<Status> future = new CompletableFuture<>();
        inFlight = future;
        try {
            executor.execute(() -> {
                try {
                    future.complete(runCheck(download, reporter));
                } catch (Throwable t) {
                    Map<String, String> ph = basePlaceholders();
                    ph.put("error", describe(t));
                    reporter.report(Level.WARNING, "updater.check-failed", ph);
                    future.complete(Status.FAILED);
                }
            });
        } catch (Exception rejected) {
            future.complete(Status.FAILED);
        }
        return future;
    }

    public boolean hasPendingUpdate() {
        return pendingUpdate != null;
    }

    public boolean isPendingUpdateDownloaded() {
        RemoteVersion remote = pendingUpdate;
        return remote != null && remote.versionNumber.equals(downloadedVersion);
    }

    public Map<String, String> getPendingPlaceholders() {
        RemoteVersion remote = pendingUpdate;
        return remote == null ? null : versionPlaceholders(remote);
    }

    public Reporter consoleReporter() {
        return (level, path, placeholders) -> {
            MessageManager mm = plugin.getMessageManager();
            if (mm == null) {
                return;
            }
            for (String line : mm.getFormattedList(Bukkit.getConsoleSender(), path, placeholders)) {
                String clean = line;
                if (clean.startsWith("[noprefix]")) {
                    clean = clean.substring(10);
                } else if (clean.startsWith("[prefix]")) {
                    clean = clean.substring(8);
                }
                // 使用正则过滤替代 ChatColor.stripColor，彻底消除废弃类型警告
                clean = STRIP_COLOR_PATTERN.matcher(clean).replaceAll("");
                if (clean != null && !clean.trim().isEmpty()) {
                    plugin.getLogger().log(level, clean);
                }
            }
        };
    }

    // =========================================================================
    // 核心检测与下载流程
    // =========================================================================

    private Status runCheck(boolean download, Reporter rawReporter) {
        Reporter reporter = (level, path, placeholders) -> rawReporter.report(level, path,
                placeholders == null ? null : new HashMap<>(placeholders));
        ConfigManager cfg = plugin.getConfigManager();
        Map<String, String> base = basePlaceholders();
        reporter.report(Level.INFO, "updater.checking", base);

        RemoteVersion remote;
        try {
            remote = fetchLatestVersion(cfg.getUpdaterProjectId(), cfg.getUpdaterChannels());
        } catch (HttpStatusException e) {
            base.put("code", String.valueOf(e.code));
            reporter.report(Level.WARNING, "updater.http-error", base);
            return Status.FAILED;
        } catch (Exception e) {
            base.put("error", describe(e));
            reporter.report(Level.WARNING, "updater.check-failed", base);
            return Status.FAILED;
        }

        if (remote == null) {
            reporter.report(Level.WARNING, "updater.no-version-found", base);
            return Status.FAILED;
        }

        if (!isNewerVersion(currentVersion, remote.versionNumber)) {
            pendingUpdate = null;
            reporter.report(Level.INFO, "updater.up-to-date", base);
            return Status.UP_TO_DATE;
        }

        pendingUpdate = remote;
        Map<String, String> ph = versionPlaceholders(remote);

        if (remote.versionNumber.equals(downloadedVersion)) {
            reporter.report(Level.INFO, "updater.already-downloaded", ph);
            return Status.ALREADY_DOWNLOADED;
        }

        reporter.report(Level.INFO, "updater.update-available", ph);
        if (!download) {
            reporter.report(Level.INFO, "updater.download-hint", ph);
            return Status.UPDATE_AVAILABLE;
        }

        reporter.report(Level.INFO, "updater.downloading", ph);
        try {
            File target = downloadUpdate(remote, cfg.isUpdaterVerifySha512());
            downloadedVersion = remote.versionNumber;
            ph.put("path", target.getPath());
            reporter.report(Level.INFO, "updater.download-success", ph);
            return Status.DOWNLOADED;
        } catch (HashMismatchException e) {
            reporter.report(Level.WARNING, "updater.hash-mismatch", ph);
        } catch (HttpStatusException e) {
            ph.put("code", String.valueOf(e.code));
            reporter.report(Level.WARNING, "updater.http-error", ph);
        } catch (Exception e) {
            ph.put("error", describe(e));
            reporter.report(Level.WARNING, "updater.download-failed", ph);
        }
        return Status.FAILED;
    }

    private RemoteVersion fetchLatestVersion(String projectId, Set<String> channels) throws IOException {
        String url = API_BASE + "/project/" + encode(projectId) + "/version?loaders=" + encode(LOADERS_FILTER);
        HttpURLConnection conn = openConnection(url);
        String json;
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new HttpStatusException(code);
            }
            try (InputStream in = conn.getInputStream()) {
                json = new String(readFully(in), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }

        JsonElement root = parseJsonSafely(json);
        if (root == null || !root.isJsonArray()) {
            return null;
        }

        RemoteVersion best = null;
        for (JsonElement element : root.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject version = element.getAsJsonObject();
            String type = getString(version, "version_type");
            String number = getString(version, "version_number");
            if (type == null || number == null || !channels.contains(type.toLowerCase(Locale.ROOT))) {
                continue;
            }

            JsonObject file = pickJarFile(version.get("files"));
            if (file == null) {
                continue;
            }
            String downloadUrl = getString(file, "url");
            if (downloadUrl == null) {
                continue;
            }

            if (best == null || compareVersions(number, best.versionNumber) > 0) {
                String sha512 = null;
                JsonElement hashes = file.get("hashes");
                if (hashes != null && hashes.isJsonObject()) {
                    sha512 = getString(hashes.getAsJsonObject(), "sha512");
                }
                String id = getString(version, "id");
                best = new RemoteVersion(
                        number,
                        type.toLowerCase(Locale.ROOT),
                        downloadUrl,
                        sha512,
                        "https://modrinth.com/plugin/" + projectId + "/version/" + (id != null ? id : number));
            }
        }
        return best;
    }

    private File downloadUpdate(RemoteVersion remote, boolean verifyHash) throws Exception {
        File updateFolder = resolveUpdateFolder();
        if (!updateFolder.exists() && !updateFolder.mkdirs()) {
            throw new IOException("Unable to create update folder: " + updateFolder.getPath());
        }

        String targetName = resolveRunningJarFile().getName();
        File target = new File(updateFolder, targetName);
        File temp = new File(updateFolder, targetName + ".part");

        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        HttpURLConnection conn = openConnection(remote.downloadUrl);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new HttpStatusException(code);
            }
            try (InputStream in = new DigestInputStream(conn.getInputStream(), digest)) {
                Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp.toPath());
            throw e;
        } finally {
            conn.disconnect();
        }

        if (verifyHash) {
            String actual = toHex(digest.digest());
            if (remote.sha512 == null || !actual.equalsIgnoreCase(remote.sha512)) {
                Files.deleteIfExists(temp.toPath());
                throw new HashMismatchException();
            }
        }

        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable atomicFallback) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    // =========================================================================
    // 跨版本隔离兼容与辅助方法 (1.12.x ~ 26.3+)
    // =========================================================================

    private HttpURLConnection openConnection(String urlStr) throws IOException {
        URL url = toURLSafe(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent",
                "anarchy-mc/" + plugin.getName() + "/" + currentVersion + " (https://modrinth.com/plugin/anarchyspawn)");
        return conn;
    }

    private static URL toURLSafe(String urlStr) throws IOException {
        try {
            return URI.create(urlStr).toURL();
        } catch (Throwable t) {
            return new URL(urlStr);
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int nRead;
        while ((nRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    @SuppressWarnings("deprecation")
    private static JsonElement parseJsonSafely(String json) {
        try {
            Method parseString = JsonParser.class.getMethod("parseString", String.class);
            return (JsonElement) parseString.invoke(null, json);
        } catch (Throwable ignored) {
            try {
                return new JsonParser().parse(json);
            } catch (Throwable e) {
                return null;
            }
        }
    }

    private File resolveRunningJarFile() {
        try {
            Method getFileMethod = JavaPlugin.class.getDeclaredMethod("getFile");
            getFileMethod.setAccessible(true);
            return (File) getFileMethod.invoke(plugin);
        } catch (Throwable ignored) {
            try {
                URI uri = plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
                return new File(uri);
            } catch (Throwable e) {
                return new File(plugin.getName() + ".jar");
            }
        }
    }

    private File resolveUpdateFolder() {
        try {
            File folder = Bukkit.getUpdateFolderFile();
            if (folder != null) return folder;
        } catch (Throwable ignored) {}
        return new File(plugin.getDataFolder().getParentFile(), "update");
    }

    // =========================================================================
    // 语义化版本号比较逻辑
    // =========================================================================

    public static boolean isNewerVersion(String local, String remote) {
        return compareVersions(remote, local) > 0;
    }

    public static int compareVersions(String a, String b) {
        String[] va = splitVersion(a);
        String[] vb = splitVersion(b);

        String[] ca = va[0].split("\\.");
        String[] cb = vb[0].split("\\.");
        int len = Math.max(ca.length, cb.length);
        for (int i = 0; i < len; i++) {
            int na = i < ca.length ? leadingNumber(ca[i]) : 0;
            int nb = i < cb.length ? leadingNumber(cb[i]) : 0;
            if (na != nb) {
                return Integer.compare(na, nb);
            }
        }

        String qa = va[1];
        String qb = vb[1];
        if (qa.isEmpty() && qb.isEmpty()) return 0;
        if (qa.isEmpty()) return 1;
        if (qb.isEmpty()) return -1;

        int ra = qualifierRank(qa);
        int rb = qualifierRank(qb);
        if (ra != rb) {
            return Integer.compare(ra, rb);
        }
        return Integer.compare(trailingNumber(qa), trailingNumber(qb));
    }

    private static String[] splitVersion(String version) {
        String v = version == null ? "" : version.trim().replaceFirst("^[vV]", "");
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        int dash = v.indexOf('-');
        if (dash >= 0) {
            return new String[] { v.substring(0, dash), v.substring(dash + 1).toLowerCase(Locale.ROOT) };
        }
        return new String[] { v, "" };
    }

    private static int qualifierRank(String q) {
        if (q.contains("snapshot") || q.contains("dev")) return 0;
        if (q.contains("alpha")) return 1;
        if (q.contains("beta")) return 2;
        if (q.contains("pre") || q.contains("rc")) return 3;
        return 4;
    }

    private static int leadingNumber(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return parseIntSafe(s.substring(0, i));
    }

    private static int trailingNumber(String s) {
        int i = s.length();
        while (i > 0 && Character.isDigit(s.charAt(i - 1))) i--;
        return parseIntSafe(s.substring(i));
    }

    private static int parseIntSafe(String s) {
        if (s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // =========================================================================
    // 占位符与工具方法
    // =========================================================================

    private Map<String, String> basePlaceholders() {
        ConfigManager cfg = plugin.getConfigManager();
        Map<String, String> ph = new HashMap<>();
        ph.put("current", currentVersion);
        ph.put("project", cfg.getUpdaterProjectId());
        ph.put("channels", String.join(", ", cfg.getUpdaterChannels()));
        return ph;
    }

    private Map<String, String> versionPlaceholders(RemoteVersion remote) {
        Map<String, String> ph = basePlaceholders();
        ph.put("latest", remote.versionNumber);
        ph.put("channel", remote.versionType);
        ph.put("url", remote.pageUrl);
        return ph;
    }

    private static JsonObject pickJarFile(JsonElement filesElement) {
        if (filesElement == null || !filesElement.isJsonArray()) {
            return null;
        }
        JsonArray files = filesElement.getAsJsonArray();
        JsonObject fallback = null;
        for (JsonElement el : files) {
            if (!el.isJsonObject()) continue;
            JsonObject file = el.getAsJsonObject();
            String name = getString(file, "filename");
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            JsonElement primary = file.get("primary");
            if (primary != null && primary.isJsonPrimitive() && primary.getAsBoolean()) {
                return file;
            }
            if (fallback == null) {
                fallback = file;
            }
        }
        return fallback;
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    // =========================================================================
    // 内部类模型
    // =========================================================================

    private static final class RemoteVersion {
        final String versionNumber;
        final String versionType;
        final String downloadUrl;
        final String sha512;
        final String pageUrl;

        RemoteVersion(String versionNumber, String versionType, String downloadUrl, String sha512, String pageUrl) {
            this.versionNumber = versionNumber;
            this.versionType = versionType;
            this.downloadUrl = downloadUrl;
            this.sha512 = sha512;
            this.pageUrl = pageUrl;
        }
    }

    private static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static final class HashMismatchException extends IOException {
        HashMismatchException() {
            super("SHA-512 mismatch");
        }
    }
}

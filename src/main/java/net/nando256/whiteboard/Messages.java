package net.nando256.whiteboard;

import static java.util.Map.entry;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

final class Messages {

  private static final Map<String, String> DEFAULT_MESSAGES =
      Map.ofEntries(
          entry("error.generic", "&cError: check the server log."),
          entry("cmd.grid.usage", "&e/whiteboard grid <WxH>"),
          entry(
              "cmd.grid.tip",
              "&7Look at the top-left frame when running the command (it becomes [1,1])."),
          entry("error.targetFrame", "&cAim at the top-left frame."),
          entry("error.noFrames", "&cCould not find enough item frames nearby."),
          entry("error.missingFrames", "&cNot enough frames at the required positions."),
          entry(
              "error.autoPlace",
              "&cFailed to place a frame at ({0},{1}). You need a solid block behind it."),
          entry("board.init.book", "&aInitialised board via book: {0}x{1}."),
          entry("board.init.command", "&aCreated board: {0}x{1} (top-left is [1,1], lock=ON)."),
          entry(
              "board.help.commands",
              "&7Text: /whiteboard text, HTML: /whiteboard htext, BG: /whiteboard bg, Clear: /whiteboard clear"),
          entry("board.autoPlaced", "&7Automatically placed {0} missing frames."),
          entry("book.extraTokens", "&eIgnored unknown arguments: {0}"),
          entry(
              "book.html.defaultMode",
              "&7Rendering the book as HTML. Add [text] at the beginning to force plain mode."),
          entry("book.clear", "&7Cleared the board as instructed in the book ({0} tiles)."),
          entry("book.noText", "&eNo renderable text found."),
          entry("book.added", "&aAdded {0} items. (/whiteboard undo to revert)"),
          entry("book.html.empty", "&eHTML parsing produced no drawable content."),
          entry("book.html.added", "&aAdded HTML content. (/whiteboard undo to revert)"),
          entry("book.text.added", "&aAdded text. (/whiteboard undo to revert)"),
          entry("usage.htext", "&e/whiteboard htext <html> <size> <#RRGGBB> <x> <y> [lineH]"),
          entry("usage.htext.book", "&e/whiteboard htext book <size> <x> <y> [lineH]"),
          entry("usage.htext.colorTip", "&7Use <font color=...> or style=\"color:\" in HTML to set colours."),
          entry(
              "usage.htext.bookTip",
              "&7Book variant allows omitting size/color/coords (defaults 16, black, 0,0)."),
          entry(
              "usage.book.modeTip",
              "&7Add [text] / [plain] to force plain rendering at the top of the book."),
          entry("usage.text", "&e/whiteboard text <msg> <size> <#RRGGBB> <x> <y>"),
          entry("usage.text.book", "&e/whiteboard text book <size> <#RRGGBB> <x> <y> [lineH]"),
          entry(
              "usage.text.modeTip",
              "&7Use [text]/[plain] or [html]/[htext] to choose book render mode."),
          entry("usage.text.defaults", "&7Book variant defaults to 16, black, 0,0 if omitted."),
          entry("usage.bg", "&e/whiteboard bg <#RRGGBB>"),
          entry("bg.changed", "&aChanged background to {0} ({1} tiles)."),
          entry("board.cleared", "&aCleared all text ({0} tiles). Background is kept."),
          entry("undo.none", "&eNothing to undo."),
          entry("undo.done", "&aUndid the last draw action."),
          entry("redo.none", "&eNothing to redo."),
          entry("redo.done", "&aRedid the previous undo."),
          entry("cmd.lock.usage", "&e/whiteboard lock <on|off>"),
          entry("lock.state", "&aLock set to {0}."),
          entry("password.set", "&aSet board password."),
          entry("password.changed", "&aUpdated board password."),
          entry("password.cleared", "&aRemoved board password."),
          entry(
              "password.required",
              "&cBoard is locked with a password. Include [pass <password>] in the book."),
          entry("password.mismatch", "&cPassword mismatch."),
          entry(
              "password.locked",
              "&cBoard is locked with a password. Use a book with [pass <password>] to edit."),
          entry(
              "destroy.denied",
              "&cYou must be OP or have whiteboard.admin to destroy a board."),
          entry("destroy.done", "&aRemoved the board ({0} frames)."),
          entry("destroy.none", "&eNo frames were removed."),
          entry("usage.font", "&e/whiteboard font <family> [PLAIN|BOLD|ITALIC]"),
          entry("font.warn", "&eWarning: \"{0}\" might not exist on this server."),
          entry("font.changed", "&aBase font changed to \"{0}\"."),
          entry("help.grid", "&e/whiteboard grid <WxH> &7…turn frames into a linked board"),
          entry("help.text", "&e/whiteboard text <msg> <size> <#RRGGBB> <x> <y>"),
          entry("help.text.book", "&e/whiteboard text book <size> <#RRGGBB> <x> <y> [lineH]"),
          entry("help.htext", "&e/whiteboard htext <html> <size> <#RRGGBB> <x> <y> [lineH]"),
          entry("help.htext.book", "&e/whiteboard htext book <size> <#RRGGBB> <x> <y> [lineH]"),
          entry("help.bg", "&e/whiteboard bg <#RRGGBB> &7…change background"),
          entry("help.clear", "&e/whiteboard clear &7…remove all text"),
          entry("help.undo", "&e/whiteboard undo / redo &7…undo/redo last action"),
          entry("help.lock", "&e/whiteboard lock <on|off>"),
          entry("help.font", "&e/whiteboard font <family> [style]"),
          entry("help.tip.quick", "&7Right-click the board while holding a book to apply instantly."),
          entry("help.tip.directives", "&7Add [size 20], [color #ff0], [pos 10 40], [line 18], [clear], etc."),
          entry("error.noBook", "&cHold a book or place one in a nearby lectern."),
          entry("error.emptyBook", "&eThe book has no content."),
          entry("info.lecternLoaded", "&7Loaded book from lectern ({0}m)."),
          entry("error.notBoard", "&cThis frame isn’t part of a board. Run /whiteboard grid first."),
          entry("error.groupMissing", "&cBoard data could not be found."),
          entry("error.targetMap", "&cAim at a frame containing a filled map."),
          entry("error.mapView", "&cCould not access MapView."),
          entry("book.noneRendered", "&eNothing could be rendered from the book."),
          entry("book.applied", "&aApplied book content to the board. (/whiteboard undo to revert)"),
          entry("lectern.placed", "&7Placed the book onto a nearby lectern."),
          entry("lectern.notFound", "&eNo empty lectern nearby."));

  private final JavaPlugin plugin;
  private final Map<String, Map<String, String>> cache = new HashMap<>();
  private final java.util.Set<String> announcedBundles = new java.util.HashSet<>();
  private final java.util.Set<String> announcedLocales = new java.util.HashSet<>();
  private final Map<java.util.UUID, String> playerLocales = new HashMap<>();
  private final java.util.Set<String> debugMessages = new java.util.HashSet<>();
  private String defaultLanguage = "auto";

  Messages(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  void load(String languageCode) {
    defaultLanguage = normalize(languageCode);
    cache.clear();
    ensureResource("en_us");
    ensureResource("ja_jp");
    if (!"auto".equals(defaultLanguage)) {
      ensureResource(defaultLanguage);
    }
  }

  void send(CommandSender sender, String key, Object... args) {
    Map<String, String> bundle = resolveBundle(sender);
    String message = ChatColor.translateAlternateColorCodes('&', format(bundle, key, args));
    sender.sendMessage(message);
  }

  String format(String key, Object... args) {
    Map<String, String> bundle =
        getBundle("auto".equals(defaultLanguage) ? "en_us" : defaultLanguage);
    return ChatColor.translateAlternateColorCodes('&', format(bundle, key, args));
  }

  private String format(Map<String, String> bundle, String key, Object... args) {
    String pattern = bundle.getOrDefault(key, DEFAULT_MESSAGES.getOrDefault(key, key));
    if (pattern == null) pattern = key;
    return new MessageFormat(pattern, Locale.ROOT).format(args);
  }

  private Map<String, String> resolveBundle(CommandSender sender) {
    List<String> candidates = new ArrayList<>();
    String lang = defaultLanguage;
    if ("auto".equals(lang) && sender instanceof Player player) {
      String locale = normalize(player.getLocale());
      if (!"auto".equals(locale)) {
        lang = locale;
      } else {
        lang = "en_us";
      }
    }

    if (!"auto".equals(lang)) {
      candidates.add(lang);
      int idx = lang.indexOf('_');
      if (idx > 0) candidates.add(lang.substring(0, idx));
    }

    if (!"auto".equals(defaultLanguage) && !defaultLanguage.equals(lang)) {
      candidates.add(defaultLanguage);
    }

    candidates.add("en_us");

    for (String candidate : candidates) {
      Map<String, String> bundle = getBundle(candidate);
      if (bundle != null) {
        return bundle;
      }
    }
    return DEFAULT_MESSAGES;
  }

  private Map<String, String> getBundle(String languageCode) {
    if (languageCode == null || languageCode.isBlank()) return DEFAULT_MESSAGES;
    if (languageCode.equals("auto")) return DEFAULT_MESSAGES;
    return cache.computeIfAbsent(languageCode, this::loadBundleSafely);
  }

  private Map<String, String> loadBundleSafely(String languageCode) {
    try {
      return loadBundle(languageCode);
    } catch (Exception ex) {
      plugin
          .getLogger()
          .log(
              Level.WARNING,
              "Failed to load language " + languageCode + ", falling back to en_us",
              ex);
      if (!"en_us".equals(languageCode)) {
        return loadBundleSafely("en_us");
      }
      return DEFAULT_MESSAGES;
    }
  }

  private Map<String, String> loadBundle(String languageCode) throws IOException {
    Map<String, String> merged = new HashMap<>(DEFAULT_MESSAGES);

    YamlConfiguration resourceYaml = loadFromResource(languageCode);
    if (resourceYaml == null && !"en_us".equals(languageCode)) {
      plugin
          .getLogger()
          .warning(
              "Language bundle " + languageCode + " not found. Falling back to en_us.");
      return loadBundle("en_us");
    }
    if (resourceYaml != null) mergeInto(merged, resourceYaml);

    File file = dataFile(languageCode);
    if (resourceYaml != null) {
      saveDefaults(file, resourceYaml, languageCode);
    }
    if (file.exists()) {
      mergeInto(merged, YamlConfiguration.loadConfiguration(file));
    }

    if (announcedBundles.add(languageCode)) {
      plugin
          .getLogger()
          .info(
              "[Whiteboard] loaded "
                  + merged.size()
                  + " messages for "
                  + languageCode);
    }
    return merged;
  }

  private File dataFile(String languageCode) throws IOException {
    File langDir = new File(plugin.getDataFolder(), "lang");
    if (!langDir.exists() && !langDir.mkdirs()) {
      throw new IOException("Could not create lang directory " + langDir.getAbsolutePath());
    }
    return new File(langDir, languageCode + ".yml");
  }

  private YamlConfiguration loadFromResource(String languageCode) throws IOException {
    String resourcePath = "lang/" + languageCode + ".yml";
    try (java.io.InputStream stream = plugin.getResource(resourcePath)) {
      if (stream == null) return null;
      java.io.InputStreamReader reader =
          new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8);
      return YamlConfiguration.loadConfiguration(reader);
    }
  }

  private void ensureResource(String languageCode) {
    if (languageCode == null || languageCode.isBlank() || languageCode.equals("auto")) return;
    try {
      loadBundle(languageCode);
    } catch (IOException ex) {
      plugin
          .getLogger()
          .log(
              Level.WARNING,
              "Failed to prepare language file " + languageCode + ".yml",
              ex);
    }
  }

  private String normalize(String code) {
    if (code == null || code.isBlank()) return "auto";
    return code.toLowerCase(Locale.ROOT).replace('-', '_');
  }

  private void mergeInto(Map<String, String> target, YamlConfiguration yaml) {
    if (yaml == null) return;

    ConfigurationSection base = yaml.getConfigurationSection("messages");
    if (base == null) return;

    // messages 配下の「すべてのキー」を相対パスで取得（例: "error.generic", "cmd.grid.usage"...）
    for (String rel : base.getKeys(true)) {
      if (base.isConfigurationSection(rel)) continue; // セクションはスキップ

      String val;
      if (base.isList(rel)) {
        // 行メッセージ対応（必要なら結合文字は変更）
        val = String.join("\n", base.getStringList(rel));
      } else {
        Object obj = base.get(rel);
        if (obj == null) continue;
        val = String.valueOf(obj);
      }

      // ★ここで上書き（putIfAbsentではない）
      // DEFAULT_MESSAGES が "error.generic" 形式ならこれでOK
      target.put(rel, val);

      // もし DEFAULT_MESSAGES 側が "messages.error.generic" 形式ならこちらを使う:
      // target.put("messages." + rel, val);
    }
  }

  private void saveDefaults(File file, YamlConfiguration yaml, String languageCode)
      throws IOException {
    if (!yaml.contains("language")) yaml.set("language", languageCode);
    yaml.save(file);
  }
}

package net.nando256.whiteboard;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.util.*;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Lectern;
import org.bukkit.Rotation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/* =========================================================
 * Whiteboard Plugin (Grid + Global Text + Wrap + Undo/Redo
 * + Group Lock/Destroy + Noto Fonts)
 *
 *  - /whiteboard fill
 *  - /whiteboard grid <WxH>
 *  - /whiteboard bg <#RRGGBB> / clear / reset
 *  - /whiteboard text <msg> [size] [#RRGGBB]
 *  - /whiteboard gtext <msg> <size> <#RRGGBB> <x> <y>
 *  - /whiteboard gtextw <msg> <size> <#RRGGBB> <x> <y> [lineH]
 *  - /whiteboard gbg <#RRGGBB> / gclear
 *  - /whiteboard gundo / gredo
 *  - /whiteboard glock <on|off>   ← 殴り・回転などを禁止/許可
 *  - /whiteboard gdestroy         ← グループごと破壊（コマンド専用）
 *  - /whiteboard font <family> [style] / fontfile <file> [style]
 * ========================================================= */

public final class WhiteboardPlugin extends JavaPlugin implements Listener {

  private static final FontRenderContext FONT_CONTEXT =
      new FontRenderContext(new AffineTransform(), true, true);
  private static final Pattern GRID_SIZE_PATTERN = Pattern.compile("^\\d+x\\d+$");
  private static final Map<String, Color> CSS_COLOR_MAP = createCssColorMap();
  private static final Pattern BOOK_MODE_PREFIX =
      Pattern.compile(
          "^\\s*(?:\\[(plain|text|html|htext)\\]|(plain|text|html|htext)\\s*:)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern INT_PATTERN = Pattern.compile("-?\\d+");

  /* ============ 1枚マップの管理 ============ */
  private final Map<Integer, WhiteboardRenderer> boards = new HashMap<>();

  /* ============ 連結グループの管理 ============ */
  private final Map<String, BoardGroup> groups = new HashMap<>(); // groupId -> group
  private final Map<Integer, String> mapToGroup = new HashMap<>(); // mapId  -> groupId
  private final Map<UUID, String> frameToGroup = new HashMap<>(); // ItemFrame UUID -> groupId
  private final Set<UUID> protectedFrames = new HashSet<>(); // 破壊・回転禁止の対象

  @Override
  public void onEnable() {
    getLogger().info("Whiteboard enabled (Grid, Wrap, Undo/Redo, Lock/Destroy)");
    File fontsDir = new File(getDataFolder(), "fonts");
    if (!fontsDir.exists()) fontsDir.mkdirs();
    loadCustomFonts(fontsDir);

    // イベント登録（ロック保護）
    getServer().getPluginManager().registerEvents(this, this);
  }

  // 1) 左クリック等のダメージ（プレイヤー/発射物/クリエも含む）
  @org.bukkit.event.EventHandler(
      priority = org.bukkit.event.EventPriority.HIGHEST,
      ignoreCancelled = true)
  public void onDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
    if (!(e.getEntity() instanceof org.bukkit.entity.ItemFrame frame)) return;
    if (!protectedFrames.contains(frame.getUniqueId())) return;

    // だれが殴ってもキャンセル（プレイヤー、モブ、矢、トライデント等）
    e.setCancelled(true);
  }

  // 2) ハンギング系の破壊（爆発・物理・ブロック破壊巻き込みなど）
  @org.bukkit.event.EventHandler(
      priority = org.bukkit.event.EventPriority.HIGHEST,
      ignoreCancelled = true)
  public void onHangingBreak(org.bukkit.event.hanging.HangingBreakEvent e) {
    if (!(e.getEntity() instanceof org.bukkit.entity.ItemFrame frame)) return;
    if (!protectedFrames.contains(frame.getUniqueId())) return;

    e.setCancelled(true);
  }

  // 3) 右クリック操作（回転／アイテム差し替え）
  @org.bukkit.event.EventHandler(
      priority = org.bukkit.event.EventPriority.HIGHEST,
      ignoreCancelled = true)
  public void onInteractEntity(org.bukkit.event.player.PlayerInteractEntityEvent e) {
    if (!(e.getRightClicked() instanceof org.bukkit.entity.ItemFrame frame)) return;
    Player player = e.getPlayer();
    if (handleBookInteract(player, frame)) {
      e.setCancelled(true);
      return;
    }
    if (protectedFrames.contains(frame.getUniqueId())) {
      e.setCancelled(true);
    }
  }

  // 4) （保険）あらゆるダメージイベントの最終網
  @org.bukkit.event.EventHandler(
      priority = org.bukkit.event.EventPriority.HIGHEST,
      ignoreCancelled = true)
  public void onAnyDamage(org.bukkit.event.entity.EntityDamageEvent e) {
    if (!(e.getEntity() instanceof org.bukkit.entity.ItemFrame frame)) return;
    if (!protectedFrames.contains(frame.getUniqueId())) return;

    e.setCancelled(true);
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    if (!(sender instanceof Player p)) return true;
    String sub = (args.length == 0) ? "help" : args[0].toLowerCase(Locale.ROOT);

    try {
      switch (sub) {
        case "grid":
          return handleGridCommand(p, Arrays.copyOfRange(args, 1, args.length));
        case "text":
          return handleTextCommand(p, Arrays.copyOfRange(args, 1, args.length), false);
        case "htext":
          return handleTextCommand(p, Arrays.copyOfRange(args, 1, args.length), true);
        case "bg":
          return handleBackgroundCommand(p, Arrays.copyOfRange(args, 1, args.length));
        case "clear":
          return handleClearCommand(p);
        case "undo":
          return handleUndoCommand(p);
        case "redo":
          return handleRedoCommand(p);
        case "lock":
          return handleLockCommand(p, Arrays.copyOfRange(args, 1, args.length));
        case "font":
          return handleFontCommand(p, Arrays.copyOfRange(args, 1, args.length));
        case "help":
        default:
          sendHelp(p);
          return true;
      }
    } catch (Throwable t) {
      p.sendMessage("§cエラー：サーバーログを確認してください。");
      t.printStackTrace();
      return true;
    }
  }

  private boolean handleGridCommand(Player p, String[] subArgs) {
    if (subArgs.length < 1
        || !GRID_SIZE_PATTERN.matcher(subArgs[0].toLowerCase(Locale.ROOT)).matches()) {
      p.sendMessage("§e/whiteboard grid <WxH>");
      p.sendMessage("§7左上にしたい額縁を狙って実行（必ず [1,1] になります）");
      return true;
    }

    String[] wh = subArgs[0].toLowerCase(Locale.ROOT).split("x");
    int width = Math.max(1, parseIntSafe(wh[0], 1));
    int height = Math.max(1, parseIntSafe(wh[1], 1));

    ItemFrame topLeft = rayItemFrame(p, 5.0);
    if (topLeft == null) {
      p.sendMessage("§c左上にしたい額縁を狙ってください。");
      return true;
    }

    BlockFace face = topLeft.getFacing();
    Location baseLoc = frameBlockCenter(topLeft);
    Vector base = baseLoc.toVector();

    Vector rightBase = rightVector(face);
    Vector downBase = new Vector(0, -1, 0);

    double scanRadius = Math.max(width, height) + 1.5;
    Collection<Entity> nearby =
        topLeft.getWorld().getNearbyEntities(baseLoc, scanRadius, scanRadius, scanRadius);
    List<ItemFrame> candidates = new ArrayList<>();
    List<Vector> offsets = new ArrayList<>();
    for (Entity e : nearby) {
      if (!(e instanceof ItemFrame frame)) continue;
      if (frame.getFacing() != face) continue;
      candidates.add(frame);
      offsets.add(frameBlockCenter(frame).toVector().subtract(base));
    }
    if (candidates.isEmpty()) {
      p.sendMessage("§c周囲に額縁が見つかりません。");
      return true;
    }

    GridAssembly assembly =
        resolveGridOrientation(
            width, height, baseLoc, topLeft, candidates, offsets, rightBase, downBase);
    if (assembly == null) {
      p.sendMessage("§c不足: 必要な位置に額縁が見つかりませんでした。");
      return true;
    }
    ItemFrame[][] grid = assembly.tiles;
    Vector right = assembly.right;
    Vector down = assembly.down;
    World world = topLeft.getWorld();
    int autoPlaced = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        if (grid[y][x] != null) continue;
        Location center = computeFrameCenter(baseLoc, right, down, x, y);
        ItemFrame created = ensureFrameExists(world, center, face);
        if (created == null) {
          p.sendMessage(
              "§c("
                  + (x + 1)
                  + ","
                  + (y + 1)
                  + ") に額縁を設置できませんでした。壁となるブロックが必要です。");
          return true;
        }
        grid[y][x] = created;
        autoPlaced++;
      }
    }

    String groupId = UUID.randomUUID().toString();
    BoardGroup group = new BoardGroup(groupId, width, height);
    groups.put(groupId, group);

    group.baseTopLeft = baseLoc.clone();
    group.rightUnit = right.clone();
    group.downUnit = down.clone();
    group.locked = true;

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        ItemFrame frame = grid[y][x];

        MapView view = Bukkit.createMap(frame.getWorld());
        view.getRenderers().clear();
        view.setLocked(true);

        WhiteboardRenderer renderer = new WhiteboardRenderer();
        renderer.setBackground(Color.WHITE);
        renderer.setBorderVisible(false);
        view.addRenderer(renderer);
        boards.put(view.getId(), renderer);

        group.tiles[y][x] = renderer;
        group.centers[y][x] = frameBlockCenter(frame);
        group.frames[y][x] = frame.getUniqueId();

        mapToGroup.put(view.getId(), groupId);
        frameToGroup.put(frame.getUniqueId(), groupId);
        protectedFrames.add(frame.getUniqueId());

        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        meta.setMapView(view);
        meta.setDisplayName("§bWhiteboard");
        map.setItemMeta(meta);
        frame.setItem(map, false);
      }
    }

    p.sendMessage("§aグリッドを設定: " + width + "x" + height + "（左上が[1,1]、ロック=ON）");
    p.sendMessage("§7テキスト: /whiteboard text, HTML: /whiteboard htext, 背景: /whiteboard bg, クリア: /whiteboard clear");
    if (autoPlaced > 0)
      p.sendMessage("§7不足していた額縁を " + autoPlaced + " 枚自動配置しました。");
    return true;
  }

  private boolean handleTextCommand(Player p, String[] subArgs, boolean htmlMode) {
    if (subArgs.length == 0) {
      sendTextUsage(p, htmlMode);
      return true;
    }

    boolean fromBook = subArgs[0].equalsIgnoreCase("book");
    if (htmlMode) {
      int minArgs = fromBook ? 4 : 4;
      if (subArgs.length < minArgs) {
        sendTextUsage(p, true);
        return true;
      }
    } else {
      int minArgs = fromBook ? 5 : 5;
      if (subArgs.length < minArgs) {
        sendTextUsage(p, false);
        return true;
      }
    }

    BoardGroup group = requireGroupBySight(p);
    if (group == null) return true;

    if (fromBook) {
      ParsedBookCommand parsed = parseBookArguments(subArgs, htmlMode);
      if (!parsed.extraTokens.isEmpty()) {
        p.sendMessage("§e未解釈の引数を無視しました: " + String.join(", ", parsed.extraTokens));
      }

      BookPayload payload = readBookPayload(p);
      if (payload == null) return true;

      RenderMode mode =
          payload.explicitMode ? payload.mode : RenderMode.HTML; // default to HTML when unspecified
      if (!payload.explicitMode && !htmlMode && mode == RenderMode.HTML) {
        p.sendMessage("§7本の内容をHTMLとして描画します。（先頭に [text] でプレーン表示に切り替え）");
      }

      if (payload.clearBefore) {
        int cleared = clearGroupTexts(group);
        p.sendMessage("§7ブック指示によりボードをクリアしました。（" + cleared + " 枚）");
      }

      int resolvedSize =
          clamp(payload.sizeOverride != null ? payload.sizeOverride : parsed.size, 8, 64);
      Color resolvedColor =
          payload.colorOverride != null ? payload.colorOverride : parsed.color;
      if (resolvedColor == null) resolvedColor = Color.BLACK;
      int resolvedGx = (payload.gxOverride != null) ? payload.gxOverride : parsed.gx;
      int resolvedGy = (payload.gyOverride != null) ? payload.gyOverride : parsed.gy;
      Integer resolvedLineH =
          (payload.lineHeightOverride != null)
              ? clamp(payload.lineHeightOverride, 8, 256)
              : parsed.lineHeight;

      int added =
          (mode == RenderMode.PLAIN)
              ? renderPlainText(
                  group,
                  payload.text,
                  resolvedSize,
                  resolvedColor,
                  resolvedGx,
                  resolvedGy,
                  resolvedLineH)
              : renderHtmlText(
                  group,
                  payload.text,
                  resolvedSize,
                  resolvedColor,
                  resolvedGx,
                  resolvedGy,
                  resolvedLineH);
      if (added == 0) {
        p.sendMessage("§e追加できるテキストが見つかりませんでした。");
      } else {
        p.sendMessage("§a" + added + " 要素を追加しました。(/whiteboard undo で取り消し)");
      }
      return true;
    }

    if (htmlMode) {
      String html = subArgs[0];
      int size = clamp(parseIntSafe(subArgs[1], 16), 8, 64);
      int index = 2;
      if (subArgs.length <= index) {
        sendTextUsage(p, true);
        return true;
      }

      Color color = Color.BLACK;
      if (!isInteger(subArgs[index])) {
        color = parseHtmlColor(subArgs[index], color);
        index++;
      }

      if (subArgs.length - index < 2) {
        sendTextUsage(p, true);
        return true;
      }

      int gx = parseIntSafe(subArgs[index++], 0);
      int gy = parseIntSafe(subArgs[index++], 0);
      Integer customLineHeight = null;
      if (subArgs.length > index) {
        customLineHeight =
            clamp(parseIntSafe(subArgs[index], defaultLineHeight(size)), 8, 256);
      }

      int added = renderHtmlText(group, html, size, color, gx, gy, customLineHeight);
      if (added == 0) {
        p.sendMessage("§eHTML解析の結果、描画対象がありませんでした。");
      } else {
        p.sendMessage("§aHTMLテキストを追加しました。(/whiteboard undo で取り消し)");
      }
      return true;
    } else {
      String msg = subArgs[0];
      int size = clamp(parseIntSafe(subArgs[1], 16), 8, 64);
      Color color = parseHtmlColor(subArgs[2], Color.BLACK);
      int gx = parseIntSafe(subArgs[3], 0);
      int gy = parseIntSafe(subArgs[4], 0);

      TextAction action = new TextAction();
      TextAtom atom = new TextAtom(msg, size, color, gx, gy);
      action.atoms.add(atom);
      applyTextAtom(group, atom, action.id);
      group.redo.clear();
      group.undo.push(action);

      p.sendMessage("§a文字を追加しました。(/whiteboard undo で取り消し)");
      return true;
    }
  }

  private void sendTextUsage(Player p, boolean htmlMode) {
    if (htmlMode) {
      p.sendMessage("§e/whiteboard htext <html> <size> <x> <y> [lineH]");
      p.sendMessage("§e/whiteboard htext book <size> <x> <y> [lineH]");
      p.sendMessage("§7※色はHTMLの <font color=\"...\"> または style=\"color:\" で指定できます。");
      p.sendMessage("§7※book 版は引数省略可（size=16, color=黒, 座標=0,0）。");
      p.sendMessage("§7※本の先頭に [text] / [plain] を置くとプレーン表示を強制できます。");
    } else {
      p.sendMessage("§e/whiteboard text <msg> <size> <#RRGGBB> <x> <y>");
      p.sendMessage("§e/whiteboard text book <size> <#RRGGBB> <x> <y> [lineH]");
      p.sendMessage("§7※[text] / [plain]、[html] / [htext] で本の描画モードを指定できます。");
      p.sendMessage("§7※book 版は size/color/x/y を省略すると 16,黒,0,0 を使います。");
    }
  }

  private boolean handleBackgroundCommand(Player p, String[] subArgs) {
    if (subArgs.length < 1) {
      p.sendMessage("§e/whiteboard bg <#RRGGBB>");
      return true;
    }
    BoardGroup group = requireGroupBySight(p);
    if (group == null) return true;

    Color color = parseHtmlColor(subArgs[0], Color.WHITE);
    int count = 0;
    for (int y = 0; y < group.H; y++) {
      for (int x = 0; x < group.W; x++) {
        WhiteboardRenderer renderer = group.tiles[y][x];
        if (renderer == null) continue;
        renderer.setBackground(color);
        renderer.requestRedraw();
        count++;
      }
    }
    p.sendMessage("§a背景色を " + subArgs[0] + " に変更しました。（" + count + " 枚）");
    return true;
  }

  private boolean handleClearCommand(Player p) {
    BoardGroup group = requireGroupBySight(p);
    if (group == null) return true;
    int cleared = clearGroupTexts(group);
    p.sendMessage("§a連結全体のテキストを消去しました（" + cleared + " 枚）。背景は保持します。");
    return true;
  }

  private boolean handleUndoCommand(Player p) {
    BoardGroup group = requireGroupBySight(p);
    if (group == null) return true;
    if (group.undo.isEmpty()) {
      p.sendMessage("§e取り消す操作がありません。");
      return true;
    }

    TextAction action = group.undo.pop();
    removeAction(group, action.id);
    group.redo.push(action);
    p.sendMessage("§a直前の描画を取り消しました。");
    return true;
  }

  private boolean handleRedoCommand(Player p) {
    BoardGroup group = requireGroupBySight(p);
    if (group == null) return true;
    if (group.redo.isEmpty()) {
      p.sendMessage("§eやり直す操作がありません。");
      return true;
    }

    TextAction action = group.redo.pop();
    for (TextAtom atom : action.atoms) applyTextAtom(group, atom, action.id);
    group.undo.push(action);
    p.sendMessage("§a取り消しをやり直しました。");
    return true;
  }

  private int clearGroupTexts(BoardGroup group) {
    int cleared = 0;
    for (int y = 0; y < group.H; y++) {
      for (int x = 0; x < group.W; x++) {
        WhiteboardRenderer renderer = group.tiles[y][x];
        if (renderer == null) continue;
        renderer.clearTexts();
        renderer.requestRedraw();
        cleared++;
      }
    }
    group.undo.clear();
    group.redo.clear();
    return cleared;
  }

  private boolean handleLockCommand(Player p, String[] subArgs) {
    if (subArgs.length < 1
        || !(subArgs[0].equalsIgnoreCase("on") || subArgs[0].equalsIgnoreCase("off"))) {
      p.sendMessage("§e/whiteboard lock <on|off>");
      return true;
    }

    BoardGroup group = requireGroupBySight(p);
    if (group == null) return true;

    boolean on = subArgs[0].equalsIgnoreCase("on");
    group.locked = on;
    for (int y = 0; y < group.H; y++) {
      for (int x = 0; x < group.W; x++) {
        UUID id = group.frames[y][x];
        if (id == null) continue;
        if (on) protectedFrames.add(id);
        else protectedFrames.remove(id);
      }
    }
    p.sendMessage("§aロックを " + (on ? "ON" : "OFF") + " にしました。");
    return true;
  }

  private boolean handleFontCommand(Player p, String[] subArgs) {
    if (subArgs.length < 1) {
      p.sendMessage("§e/whiteboard font <family> [PLAIN|BOLD|ITALIC]");
      return true;
    }

    BoardGroup group = requireGroupBySight(p);
    if (group == null) return true;

    String family = subArgs[0];
    int style = parseFontStyle(subArgs.length >= 2 ? subArgs[1] : "PLAIN");
    Font baseFont = new Font(family, style, 16);
    if (!fontFamilyExists(family))
      p.sendMessage("§e注意: \"" + family + "\" が見つからない可能性があります。");

    for (int y = 0; y < group.H; y++) {
      for (int x = 0; x < group.W; x++) {
        WhiteboardRenderer renderer = group.tiles[y][x];
        if (renderer == null) continue;
        renderer.setBaseFont(baseFont);
        renderer.requestRedraw();
      }
    }
    p.sendMessage("§aベースフォントを \"" + baseFont.getFamily() + "\" に変更しました。");
    return true;
  }

  private void sendHelp(Player p) {
    p.sendMessage("§e/whiteboard grid <WxH> §7…額縁を連結してホワイトボード化");
    p.sendMessage("§e/whiteboard text <msg> <size> <#RRGGBB> <x> <y>");
    p.sendMessage("§e/whiteboard text book <size> <#RRGGBB> <x> <y> [lineH]");
    p.sendMessage("§e/whiteboard htext <html> <size> <#RRGGBB> <x> <y> [lineH]");
    p.sendMessage("§e/whiteboard htext book <size> <#RRGGBB> <x> <y> [lineH]");
    p.sendMessage("§e/whiteboard bg <#RRGGBB> §7…背景変更");
    p.sendMessage("§e/whiteboard clear §7…連結文字を全消去");
    p.sendMessage("§e/whiteboard undo / redo §7…直前の操作を取り消し / やり直し");
    p.sendMessage("§e/whiteboard lock <on|off>");
    p.sendMessage("§e/whiteboard font <family> [style]");
    p.sendMessage("§7※本を手に持って額縁を右クリックすると即時に反映されます。");
    p.sendMessage("§7※本の先頭で [size 20], [color #ff0], [pos 10 40], [line 18], [clear] などを指定可能。");
  }

  private ItemStack findBookInHand(Player p) {
    ItemStack main = p.getInventory().getItemInMainHand();
    if (isBook(main)) return main;
    ItemStack off = p.getInventory().getItemInOffHand();
    if (isBook(off)) return off;
    return null;
  }

  private boolean isBook(ItemStack stack) {
    if (stack == null) return false;
    Material type = stack.getType();
    return type == Material.WRITTEN_BOOK || type == Material.WRITABLE_BOOK;
  }

  private BookPayload readBookPayload(Player p) {
    return readBookPayload(p, null);
  }

  private BookPayload readBookPayload(Player p, ItemStack preferredBook) {
    ItemStack handBook = (preferredBook != null && isBook(preferredBook)) ? preferredBook : null;
    if (handBook == null) {
      handBook = findBookInHand(p);
      if (!isBook(handBook)) handBook = null;
    }
    LecternHit lecternHit = null;
    ItemStack source = handBook;

    if (source == null) {
      lecternHit = findLecternBook(p, 5.0);
      if (lecternHit != null) source = lecternHit.book;
    }

    if (source == null) {
      p.sendMessage("§c本を手に持つか、近くの所見台に本を設置してください。");
      return null;
    }

    BookPayload payload = extractBookPayload(source);
    if (payload == null || payload.text.isEmpty()) {
      p.sendMessage("§e本に内容がありません。");
      return null;
    }

    if (lecternHit != null) {
      double dist = Math.sqrt(lecternHit.distanceSq);
      p.sendMessage(String.format(Locale.ROOT, "§7所見台の本を読み込みました（%.1fm）。", dist));
      payload = payload.withLectern(lecternHit.distanceSq);
    }
    return payload;
  }

  private LecternHit findLecternBook(Player p, double radius) {
    Location origin = p.getLocation();
    World world = origin.getWorld();
    if (world == null) return null;

    int range = (int) Math.ceil(radius);
    double bestDistSq = radius * radius;
    LecternHit best = null;

    for (int dx = -range; dx <= range; dx++) {
      for (int dy = -range; dy <= range; dy++) {
        for (int dz = -range; dz <= range; dz++) {
          Block block =
              world.getBlockAt(
                  origin.getBlockX() + dx, origin.getBlockY() + dy, origin.getBlockZ() + dz);
          if (block.getType() != Material.LECTERN) continue;
          Location blockCenter =
              block.getLocation().add(0.5, 0.5, 0.5); // center to measure distance
          double distSq = blockCenter.distanceSquared(origin);
          if (distSq > bestDistSq) continue;

          if (!(block.getState() instanceof Lectern lectern)) continue;
          org.bukkit.inventory.Inventory inv = lectern.getInventory();
          if (inv == null) continue;
          ItemStack book = inv.getItem(0);
          if (!isBook(book)) continue;

          if (best == null || distSq < best.distanceSq) {
            best = new LecternHit(book.clone(), distSq);
          }
        }
      }
    }
    return best;
  }

  private BookPayload extractBookPayload(ItemStack book) {
    if (book == null) return null;
    if (!(book.getItemMeta() instanceof BookMeta meta)) return null;

    List<String> pages = collectBookPages(meta);
    if (pages.isEmpty()) return null;

    String raw = String.join("\n", pages);
    ModePrefixResult prefix = parseBookMode(raw);
    BookDirectives directives = parseBookDirectives(prefix.content);
    return new BookPayload(
        directives.content,
        prefix.mode,
        prefix.explicit,
        directives.size,
        directives.color,
        directives.gx,
        directives.gy,
        directives.lineHeight,
        directives.clearBefore);
  }

  private List<String> collectBookPages(BookMeta meta) {
    List<String> pages = new ArrayList<>();
    try {
      java.lang.reflect.Method pagesMethod = meta.getClass().getMethod("pages");
      Object result = pagesMethod.invoke(meta);
      if (result instanceof Collection<?> collection) {
        Object serializer = null;
        java.lang.reflect.Method serializeMethod = null;
        try {
          Class<?> serializerClass =
              Class.forName(
                  "net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer");
          Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component");
          java.lang.reflect.Method plainText = serializerClass.getMethod("plainText");
          serializer = plainText.invoke(null);
          serializeMethod = serializerClass.getMethod("serialize", componentClass);
        } catch (ClassNotFoundException ignored) {
          serializer = null;
          serializeMethod = null;
        }
        for (Object element : collection) {
          if (serializer != null && serializeMethod != null) {
            try {
              pages.add((String) serializeMethod.invoke(serializer, element));
            } catch (Exception e) {
              pages.add(element.toString());
            }
          } else {
            pages.add(element.toString());
          }
        }
      }
    } catch (NoSuchMethodException ignored) {
      // 古い API 互換: fallback to legacy getPages()
    } catch (Exception ignored) {
      // その他の失敗は無視し、旧 API にフォールバック
    }

    if (pages.isEmpty()) {
      pages.addAll(meta.getPages());
    }
    return pages;
  }

  private ModePrefixResult parseBookMode(String raw) {
    if (raw == null) return new ModePrefixResult(RenderMode.HTML, false, "");
    String trimmed = raw.stripLeading();
    java.util.regex.Matcher matcher = BOOK_MODE_PREFIX.matcher(trimmed);
    if (matcher.find()) {
      String keyword =
          (matcher.group(1) != null) ? matcher.group(1) : matcher.group(2); // bracket or word
      String remainder = trimmed.substring(matcher.end()).stripLeading();
      RenderMode mode = interpretModeKeyword(keyword);
      return new ModePrefixResult(mode, true, remainder);
    }
    return new ModePrefixResult(RenderMode.HTML, false, trimmed);
  }

  private RenderMode interpretModeKeyword(String keyword) {
    if (keyword == null) return RenderMode.HTML;
    String lower = keyword.toLowerCase(Locale.ROOT);
    return switch (lower) {
      case "plain", "text" -> RenderMode.PLAIN;
      case "html", "htext" -> RenderMode.HTML;
      default -> RenderMode.HTML;
    };
  }

  private BookDirectives parseBookDirectives(String content) {
    if (content == null) return new BookDirectives("", null, null, null, null, null, false);
    String working = content.stripLeading();
    Integer size = null;
    Color color = null;
    Integer gx = null;
    Integer gy = null;
    Integer lineHeight = null;
    boolean clearBefore = false;

    while (true) {
      String trimmed = working.stripLeading();
      if (!trimmed.startsWith("[")) break;
      int close = trimmed.indexOf(']');
      if (close < 0) break;

      String token = trimmed.substring(1, close).trim();
      String remainder = trimmed.substring(close + 1);
      boolean matched = false;
      String lower = token.toLowerCase(Locale.ROOT);

      if (lower.startsWith("size")) {
        Integer value = parseFirstInt(token);
        if (value != null) {
          size = clamp(value, 8, 64);
          matched = true;
        }
      } else if (lower.startsWith("line")) {
        Integer value = parseFirstInt(token);
        if (value != null) {
          lineHeight = clamp(value, 8, 256);
          matched = true;
        }
      } else if (lower.equals("clear")) {
        clearBefore = true;
        matched = true;
      } else if (lower.startsWith("color")) {
        String arg = token.replaceFirst("(?i)color", "").trim();
        while (!arg.isEmpty()
            && (arg.charAt(0) == '=' || arg.charAt(0) == ':' || arg.charAt(0) == ',')) {
          arg = arg.substring(1).trim();
        }
        if (arg.isEmpty()) arg = token;
        Color parsed = tryParseColorToken(arg);
        if (parsed == null) parsed = tryParseColorToken(token);
        if (parsed != null) {
          color = parsed;
          matched = true;
        }
      } else if (lower.startsWith("pos") || lower.startsWith("xy") || lower.startsWith("offset")) {
        Integer[] pos = parsePositionToken(token);
        if (pos != null) {
          gx = pos[0];
          gy = pos[1];
          matched = true;
        }
      }

      if (matched) {
        working = remainder;
      } else {
        break;
      }
    }
    return new BookDirectives(working.stripLeading(), size, color, gx, gy, lineHeight, clearBefore);
  }

  private Integer parseFirstInt(String token) {
    java.util.regex.Matcher matcher = INT_PATTERN.matcher(token);
    if (!matcher.find()) return null;
    try {
      return Integer.parseInt(matcher.group());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private Integer[] parsePositionToken(String token) {
    java.util.regex.Matcher matcher = INT_PATTERN.matcher(token);
    if (!matcher.find()) return null;
    Integer x;
    Integer y = 0;
    try {
      x = Integer.parseInt(matcher.group());
    } catch (NumberFormatException e) {
      return null;
    }
    if (matcher.find()) {
      try {
        y = Integer.parseInt(matcher.group());
      } catch (NumberFormatException e) {
        y = 0;
      }
    }
    return new Integer[] {x, y};
  }

  private ParsedBookCommand parseBookArguments(String[] subArgs, boolean htmlMode) {
    int size = 16;
    Color color = Color.BLACK;
    int gx = 0;
    int gy = 0;
    Integer lineHeight = null;
    int index = 1; // skip "book"

    if (index < subArgs.length && isInteger(subArgs[index])) {
      size = clamp(parseIntSafe(subArgs[index], size), 8, 64);
      index++;
    }

    if (index < subArgs.length) {
      Color parsedColor = tryParseColorToken(subArgs[index]);
      if (parsedColor != null) {
        color = parsedColor;
        index++;
      }
    }

    if (index < subArgs.length && isInteger(subArgs[index])) {
      gx = parseIntSafe(subArgs[index], gx);
      index++;
    }

    if (index < subArgs.length && isInteger(subArgs[index])) {
      gy = parseIntSafe(subArgs[index], gy);
      index++;
    }

    if (index < subArgs.length && isInteger(subArgs[index])) {
      lineHeight = clamp(parseIntSafe(subArgs[index], defaultLineHeight(size)), 8, 256);
      index++;
    }

    List<String> extras = new ArrayList<>();
    while (index < subArgs.length) {
      extras.add(subArgs[index++]);
    }
    return new ParsedBookCommand(
        size, color, gx, gy, lineHeight, Collections.unmodifiableList(extras));
  }

  private List<HtmlToken> createPlainTextTokens(String text, Color color, int size) {
    List<HtmlToken> tokens = new ArrayList<>();
    if (text == null || text.isEmpty()) return tokens;
    String normalized = text.replace("\r", "");
    StringBuilder current = new StringBuilder();
    boolean lineStart = true;
    boolean pendingSpace = false;
    for (int i = 0; i < normalized.length(); i++) {
      char ch = normalized.charAt(i);
      if (ch == '\n') {
        if (current.length() > 0) {
          tokens.add(HtmlToken.text(current.toString(), color, size));
          current.setLength(0);
        }
        tokens.add(HtmlToken.lineBreak());
        lineStart = true;
        pendingSpace = false;
        continue;
      }
      if (Character.isWhitespace(ch)) {
        if (!lineStart) pendingSpace = true;
        continue;
      }
      if (pendingSpace) {
        if (current.length() == 0) current.append(' ');
        else current.append(' ');
        pendingSpace = false;
      }
      current.append(ch);
      lineStart = false;
    }
    if (current.length() > 0) {
      tokens.add(HtmlToken.text(current.toString(), color, size));
    }
    return tokens;
  }

  private int renderPlainText(
      BoardGroup group, String text, int size, Color color, int gx, int gy, Integer customLineH) {
    List<HtmlToken> tokens = createPlainTextTokens(text, color, size);
    if (tokens.isEmpty()) return 0;
    int lineHeight =
        clamp(customLineH != null ? customLineH : defaultLineHeight(size), 8, 256);
    return renderTokens(group, tokens, gx, gy, lineHeight);
  }

  private int renderHtmlText(
      BoardGroup group,
      String html,
      int defaultSize,
      Color defaultColor,
      int gx,
      int gy,
      Integer customLineH) {
    List<HtmlToken> tokens = parseHtmlTokens(html, defaultColor, defaultSize);
    if (tokens.isEmpty()) return 0;
    int lineHeight =
        clamp(customLineH != null ? customLineH : defaultLineHeight(defaultSize), 8, 256);
    return renderTokens(group, tokens, gx, gy, lineHeight);
  }

  private int renderTokens(
      BoardGroup group, List<HtmlToken> tokens, int gx, int gy, int baseLineHeight) {
    Font baseFont = resolveBaseFont(group);
    int canvasWidth = group.W * 128;

    TextAction action = new TextAction();
    int x = gx;
    int y = gy;
    int lineHeight = baseLineHeight;
    boolean added = false;

    for (HtmlToken token : tokens) {
      if (token.lineBreak) {
        x = 0;
        y += lineHeight;
        lineHeight = baseLineHeight;
        continue;
      }
      if (token.text == null || token.text.isEmpty()) continue;

      Font font = baseFont.deriveFont((float) token.size);
      int tokenLineHeight = defaultLineHeight(token.size);
      if (tokenLineHeight > lineHeight) lineHeight = tokenLineHeight;

      int idx = 0;
      String text = token.text;
      while (idx < text.length()) {
        while (idx < text.length() && text.charAt(idx) == ' ' && x == 0) idx++;
        if (idx >= text.length()) break;

        int remaining = canvasWidth - x;
        if (remaining <= 0) {
          x = 0;
          y += lineHeight;
          lineHeight = Math.max(baseLineHeight, tokenLineHeight);
          continue;
        }

        int next = findWrapPoint(text, idx, font, remaining);
        if (next <= idx) {
          if (x != 0) {
            x = 0;
            y += lineHeight;
            lineHeight = Math.max(baseLineHeight, tokenLineHeight);
            continue;
          }
          next = Math.min(idx + 1, text.length());
        }

        String piece = text.substring(idx, next);
        if (piece.isBlank()) {
          idx = next;
          continue;
        }

        TextAtom atom = new TextAtom(piece, token.size, token.color, x, y);
        action.atoms.add(atom);
        applyTextAtom(group, atom, action.id);
        added = true;

        x += measureWidth(font, piece);
        idx = next;

        if (idx < text.length()) {
          x = 0;
          y += lineHeight;
          lineHeight = Math.max(baseLineHeight, tokenLineHeight);
        }
      }
    }

    if (!added) return 0;
    group.redo.clear();
    group.undo.push(action);
    return action.atoms.size();
  }

  private List<HtmlToken> parseHtmlTokens(String html, Color defaultColor, int defaultSize) {
    List<HtmlToken> tokens = new ArrayList<>();
    if (html == null || html.isEmpty()) return tokens;

    String input = html.replace("\r", "");
    Deque<HtmlStyle> stack = new ArrayDeque<>();
    stack.push(new HtmlStyle(defaultColor, defaultSize));

    StringBuilder buffer = new StringBuilder();
    int i = 0;
    while (i < input.length()) {
      char ch = input.charAt(i);
      if (ch == '<') {
        if (buffer.length() > 0) {
          emitHtmlText(buffer, stack.peek(), tokens);
          buffer.setLength(0);
        }

        int close = input.indexOf('>', i + 1);
        if (close == -1) break;
        String raw = input.substring(i + 1, close).trim();
        boolean closing = raw.startsWith("/");
        boolean selfClosing = raw.endsWith("/");
        String content = raw;
        if (closing) content = raw.substring(1).trim();
        if (selfClosing) content = content.substring(0, content.length() - 1).trim();

        String name = content;
        String attrPart = "";
        int spaceIdx = content.indexOf(' ');
        if (spaceIdx >= 0) {
          name = content.substring(0, spaceIdx);
          attrPart = content.substring(spaceIdx + 1);
        }
        name = name.toLowerCase(Locale.ROOT);
        Map<String, String> attrs = parseHtmlAttributes(attrPart);

        if (closing) {
          if ((name.equals("span") || name.equals("font")) && stack.size() > 1) {
            stack.pop();
          } else if (name.equals("p") || name.equals("div")) {
            tokens.add(HtmlToken.lineBreak());
            tokens.add(HtmlToken.lineBreak());
          }
        } else {
          if (name.equals("br")) {
            tokens.add(HtmlToken.lineBreak());
          } else if (name.equals("p") || name.equals("div")) {
            if (!tokens.isEmpty() && !tokens.get(tokens.size() - 1).lineBreak) {
              tokens.add(HtmlToken.lineBreak());
            }
            tokens.add(HtmlToken.lineBreak());
          } else if (name.equals("span") || name.equals("font")) {
            HtmlStyle child = deriveStyleFromAttributes(stack.peek(), attrs);
            stack.push(child);
            if (selfClosing && stack.size() > 1) stack.pop();
          }
        }

        i = close + 1;
        continue;
      }

      if (ch == '&') {
        int semi = input.indexOf(';', i + 1);
        if (semi > i) {
          buffer.append(decodeHtmlEntity(input.substring(i + 1, semi)));
          i = semi + 1;
          continue;
        }
      }

      buffer.append(ch);
      i++;
    }

    if (buffer.length() > 0) {
      emitHtmlText(buffer, stack.peek(), tokens);
    }
    return tokens;
  }

  private void emitHtmlText(StringBuilder buffer, HtmlStyle style, List<HtmlToken> tokens) {
    if (buffer.isEmpty()) return;
    String raw = buffer.toString();
    buffer.setLength(0);

    StringBuilder current = new StringBuilder();
    boolean lineStart = tokens.isEmpty() || tokens.get(tokens.size() - 1).lineBreak;
    boolean pendingSpace = false;
    for (int i = 0; i < raw.length(); i++) {
      char ch = raw.charAt(i);
      if (ch == '\n') {
        if (current.length() > 0) {
          tokens.add(HtmlToken.text(current.toString(), style.color, style.size));
          current.setLength(0);
        }
        tokens.add(HtmlToken.lineBreak());
        lineStart = true;
        pendingSpace = false;
        continue;
      }
      if (Character.isWhitespace(ch)) {
        if (!lineStart) pendingSpace = true;
        continue;
      }
      if (pendingSpace) {
        if (current.length() == 0) current.append(' ');
        else current.append(' ');
        pendingSpace = false;
      }
      current.append(ch);
      lineStart = false;
    }
    if (current.length() > 0) {
      tokens.add(HtmlToken.text(current.toString(), style.color, style.size));
    }
  }

  private Map<String, String> parseHtmlAttributes(String raw) {
    Map<String, String> map = new HashMap<>();
    if (raw == null || raw.isEmpty()) return map;
    int i = 0;
    while (i < raw.length()) {
      while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
      if (i >= raw.length()) break;
      int eq = raw.indexOf('=', i);
      if (eq == -1) break;
      String key = raw.substring(i, eq).trim().toLowerCase(Locale.ROOT);
      i = eq + 1;
      if (i >= raw.length()) break;

      char quote = raw.charAt(i);
      String value;
      if (quote == '"' || quote == '\'') {
        i++;
        int end = raw.indexOf(quote, i);
        if (end == -1) {
          value = raw.substring(i);
          i = raw.length();
        } else {
          value = raw.substring(i, end);
          i = end + 1;
        }
      } else {
        int end = i;
        while (end < raw.length() && !Character.isWhitespace(raw.charAt(end))) end++;
        value = raw.substring(i, end);
        i = end;
      }
      map.put(key, value);
    }
    return map;
  }

  private HtmlStyle deriveStyleFromAttributes(HtmlStyle parent, Map<String, String> attrs) {
    Color color = parent.color;
    int size = parent.size;

    if (attrs.containsKey("color")) color = parseCssColor(attrs.get("color"), color);
    if (attrs.containsKey("size")) size = clamp(parseFontSize(attrs.get("size"), size), 8, 256);
    if (attrs.containsKey("font-size"))
      size = clamp(parseFontSize(attrs.get("font-size"), size), 8, 256);
    if (attrs.containsKey("style")) {
      String styleDecl = attrs.get("style");
      for (String decl : styleDecl.split(";")) {
        int colon = decl.indexOf(':');
        if (colon == -1) continue;
        String key = decl.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String value = decl.substring(colon + 1).trim();
        if (key.equals("color")) color = parseCssColor(value, color);
        if (key.equals("font-size")) size = clamp(parseFontSize(value, size), 8, 256);
      }
    }
    return new HtmlStyle(color, size);
  }

  private int parseFontSize(String raw, int current) {
    if (raw == null) return current;
    String v = raw.trim().toLowerCase(Locale.ROOT);
    if (v.endsWith("px")) v = v.substring(0, v.length() - 2).trim();
    if (v.endsWith("%")) {
      try {
        double pct = Double.parseDouble(v.substring(0, v.length() - 1));
        return Math.max(1, (int) Math.round(current * pct / 100.0));
      } catch (NumberFormatException ignored) {
      }
      return current;
    }
    if (v.endsWith("em")) {
      try {
        double em = Double.parseDouble(v.substring(0, v.length() - 2));
        return Math.max(1, (int) Math.round(current * em));
      } catch (NumberFormatException ignored) {
        return current;
      }
    }
    return Math.max(1, parseIntSafe(v, current));
  }

  private int findWrapPoint(String text, int start, Font font, int maxWidth) {
    int len = text.length();
    int best = start;
    int lastSpace = -1;
    for (int i = start; i < len; i++) {
      char ch = text.charAt(i);
      if (ch == ' ') lastSpace = i;
      int width = measureWidth(font, text.substring(start, i + 1));
      if (width <= maxWidth) {
        best = i + 1;
      } else {
        if (lastSpace >= start && lastSpace < i) return lastSpace + 1;
        return best;
      }
    }
    return best;
  }

  private Font resolveBaseFont(BoardGroup group) {
    for (int y = 0; y < group.H; y++) {
      for (int x = 0; x < group.W; x++) {
        WhiteboardRenderer renderer = group.tiles[y][x];
        if (renderer != null) return renderer.getBaseFont();
      }
    }
    return new Font("Noto Sans CJK JP", Font.PLAIN, 16);
  }

  private static int defaultLineHeight(int size) {
    return Math.max(8, (int) Math.round(size * 1.25));
  }

  private static int measureWidth(Font font, String text) {
    if (text == null || text.isEmpty()) return 0;
    Rectangle2D bounds = font.getStringBounds(text, FONT_CONTEXT);
    return (int) Math.ceil(bounds.getWidth());
  }

  private String decodeHtmlEntity(String entity) {
    String key = entity.trim();
    if (key.isEmpty()) return "";
    if (key.startsWith("#x") || key.startsWith("#X")) {
      try {
        int code = Integer.parseInt(key.substring(2), 16);
        return Character.toString((char) code);
      } catch (NumberFormatException ignored) {
        return "&" + entity + ";";
      }
    }
    if (key.startsWith("#")) {
      try {
        int code = Integer.parseInt(key.substring(1));
        return Character.toString((char) code);
      } catch (NumberFormatException ignored) {
        return "&" + entity + ";";
      }
    }
    return switch (key.toLowerCase(Locale.ROOT)) {
      case "lt" -> "<";
      case "gt" -> ">";
      case "amp" -> "&";
      case "quot" -> "\"";
      case "apos" -> "'";
      case "nbsp" -> " ";
      default -> "&" + entity + ";";
    };
  }

  private static Color parseCssColor(String raw, Color fallback) {
    if (raw == null) return fallback;
    String v = raw.trim();
    if (v.isEmpty()) return fallback;
    String lower = v.toLowerCase(Locale.ROOT);
    if (lower.startsWith("rgb(") && lower.endsWith(")")) {
      String inner = lower.substring(4, lower.length() - 1);
      String[] parts = inner.split(",");
      if (parts.length == 3) {
        try {
          int r = clamp(parseCssColorComponent(parts[0]), 0, 255);
          int g = clamp(parseCssColorComponent(parts[1]), 0, 255);
          int b = clamp(parseCssColorComponent(parts[2]), 0, 255);
          return new Color(r, g, b);
        } catch (NumberFormatException ignored) {
          return fallback;
        }
      }
      return fallback;
    }
    Color named = CSS_COLOR_MAP.get(lower.replace(" ", ""));
    if (named != null) return named;
    return parseHtmlColor(v, fallback);
  }

  private static int parseCssColorComponent(String raw) {
    String v = raw.trim();
    if (v.endsWith("%")) {
      double pct = Double.parseDouble(v.substring(0, v.length() - 1));
      return (int) Math.round(255 * (pct / 100.0));
    }
    return Integer.parseInt(v);
  }

  private Color tryParseColorToken(String token) {
    if (token == null) return null;
    String trimmed = token.trim();
    if (trimmed.isEmpty()) return null;
    String normalized = trimmed.toLowerCase(Locale.ROOT).replace(" ", "");

    Color named = CSS_COLOR_MAP.get(normalized);
    if (named != null) return named;

    if (normalized.startsWith("rgb(") && normalized.endsWith(")")) {
      String inner = normalized.substring(4, normalized.length() - 1);
      String[] parts = inner.split(",");
      if (parts.length == 3) {
        try {
          int r = clamp(parseCssColorComponent(parts[0]), 0, 255);
          int g = clamp(parseCssColorComponent(parts[1]), 0, 255);
          int b = clamp(parseCssColorComponent(parts[2]), 0, 255);
          return new Color(r, g, b);
        } catch (Exception ignored) {
          return null;
        }
      }
      return null;
    }

    String hex = trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
    if (hex.length() == 3 && isHexDigits(hex)) {
      StringBuilder sb = new StringBuilder(6);
      for (int i = 0; i < 3; i++) sb.append(hex.charAt(i)).append(hex.charAt(i));
      hex = sb.toString();
    }
    if (hex.length() == 6 && isHexDigits(hex)) {
      try {
        return new Color(Integer.parseInt(hex, 16));
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static boolean isHexDigits(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (!(c >= '0' && c <= '9')
          && !(c >= 'a' && c <= 'f')
          && !(c >= 'A' && c <= 'F')) return false;
    }
    return true;
  }

  /* =================== ここからユーティリティ =================== */

  // どのグループか（視線先の額縁から特定）
  private BoardGroup requireGroupBySight(Player p) {
    ItemFrame f = rayItemFrame(p, 5.0);
    MapView view = requireMapViewOnFrame(p, f);
    if (view == null) return null;
    String gid = mapToGroup.get(view.getId());
    if (gid == null) {
      p.sendMessage("§cこの額縁は連結ボードではありません。/whiteboard grid を先に実行。");
      return null;
    }
    BoardGroup g = groups.get(gid);
    if (g == null || g.baseTopLeft == null) {
      p.sendMessage("§cグループ情報が見つかりません。");
      return null;
    }
    return g;
  }

  private MapView requireMapViewOnFrame(Player p, ItemFrame frame) {
    if (frame == null
        || frame.getItem() == null
        || frame.getItem().getType() != Material.FILLED_MAP) {
      p.sendMessage("§c地図が入った額縁を狙ってください。");
      return null;
    }
    MapMeta mm = (MapMeta) frame.getItem().getItemMeta();
    MapView view = mm.getMapView();
    if (view == null) {
      p.sendMessage("§cMapView を取得できませんでした。");
      return null;
    }
    return view;
  }

  private BoardGroup groupFromFrame(ItemFrame frame) {
    if (frame == null) return null;
    ItemStack item = frame.getItem();
    if (item == null || item.getType() != Material.FILLED_MAP) return null;
    MapMeta meta = (MapMeta) item.getItemMeta();
    if (meta == null) return null;
    MapView view = meta.getMapView();
    if (view == null) return null;
    String gid = mapToGroup.get(view.getId());
    if (gid == null) return null;
    return groups.get(gid);
  }

  private ItemFrame rayItemFrame(Player p, double maxDist) {
    Location eye = p.getEyeLocation();
    for (double t = 0.0; t <= maxDist; t += 0.25) {
      Location pos = eye.clone().add(eye.getDirection().multiply(t));
      for (Entity e : pos.getWorld().getNearbyEntities(pos, 0.4, 0.4, 0.4)) {
        if (e instanceof ItemFrame f) return f;
      }
    }
    return null;
  }

  private ItemFrame ensureFrameExists(World world, Location center, BlockFace facing) {
    if (world == null) return null;
    ItemFrame existing = findFrameNear(world, center, facing);
    if (existing != null) return existing;

    Block support = center.getBlock().getRelative(facing.getOppositeFace());
    if (support.getType().isAir()) return null;

    try {
      ItemFrame spawned =
          world.spawn(center, ItemFrame.class, frame -> {
            frame.setFacingDirection(facing, true);
            frame.setRotation(Rotation.NONE);
          });
      if (spawned == null || !spawned.isValid()) return null;
      return spawned;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private ItemFrame findFrameNear(World world, Location center, BlockFace facing) {
    if (world == null) return null;
    double radius = 0.25;
    for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
      if (entity instanceof ItemFrame frame) {
        if (frame.getFacing() != facing) continue;
        if (frameBlockCenter(frame).distanceSquared(center) <= 0.05) return frame;
      }
    }
    return null;
  }

  private boolean handleBookInteract(Player player, ItemFrame frame) {
    ItemStack held = findBookInHand(player);
    if (!isBook(held)) return false;

    BoardGroup group = groupFromFrame(frame);
    if (group == null) return false;

    BookPayload payload = readBookPayload(player, held);
    if (payload == null) return true; // エラーメッセージは内部で表示済み

    if (payload.clearBefore) {
      int cleared = clearGroupTexts(group);
      player.sendMessage("§7ブック指示によりボードをクリアしました。（" + cleared + " 枚）");
    }

    int size =
        clamp(payload.sizeOverride != null ? payload.sizeOverride : 16, 8, 64);
    Color color =
        (payload.colorOverride != null) ? payload.colorOverride : Color.BLACK;
    int gx = (payload.gxOverride != null) ? payload.gxOverride : 0;
    int gy = (payload.gyOverride != null) ? payload.gyOverride : 0;
    Integer lineHeight =
        (payload.lineHeightOverride != null) ? clamp(payload.lineHeightOverride, 8, 256) : null;

    RenderMode mode = payload.explicitMode ? payload.mode : RenderMode.HTML;

    int added =
        (mode == RenderMode.PLAIN)
            ? renderPlainText(group, payload.text, size, color, gx, gy, lineHeight)
            : renderHtmlText(group, payload.text, size, color, gx, gy, lineHeight);

    if (added == 0) {
      player.sendMessage("§e描画できる内容がありませんでした。");
    } else {
      player.sendMessage("§a本の内容をホワイトボードに反映しました。(/whiteboard undo で取り消し)");
    }
    return true;
  }

  private static int parseIntSafe(String s, int def) {
    try {
      return Integer.parseInt(s);
    } catch (Exception e) {
      return def;
    }
  }

  private static boolean isInteger(String s) {
    if (s == null) return false;
    String v = s.trim();
    if (v.isEmpty()) return false;
    try {
      Integer.parseInt(v);
      return true;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static Color parseHtmlColor(String s, Color def) {
    if (s == null) return def;
    String v = s.trim();
    if (v.isEmpty()) return def;
    if (v.charAt(0) == '#') v = v.substring(1);
    if (v.length() == 3) {
      StringBuilder sb = new StringBuilder(6);
      for (int i = 0; i < 3; i++) sb.append(v.charAt(i)).append(v.charAt(i));
      v = sb.toString();
    }
    if (v.length() != 6) return def;
    try {
      return new Color(Integer.parseInt(v, 16));
    } catch (NumberFormatException e) {
      return def;
    }
  }

  private static boolean fontFamilyExists(String family) {
    String[] names =
        GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
    for (String n : names) if (n.equalsIgnoreCase(family)) return true;
    return false;
  }

  private static int parseFontStyle(String s) {
    String v = (s == null ? "PLAIN" : s).toUpperCase(Locale.ROOT);
    return switch (v) {
      case "BOLD" -> Font.BOLD;
      case "ITALIC" -> Font.ITALIC;
      case "BOLDITALIC", "BOLD_ITALIC" -> (Font.BOLD | Font.ITALIC);
      default -> Font.PLAIN;
    };
  }

  private static Map<String, Color> createCssColorMap() {
    Map<String, Color> map = new HashMap<>();
    map.put("black", new Color(0x000000));
    map.put("white", new Color(0xFFFFFF));
    map.put("red", new Color(0xFF0000));
    map.put("green", new Color(0x008000));
    map.put("blue", new Color(0x0000FF));
    map.put("yellow", new Color(0xFFFF00));
    map.put("cyan", new Color(0x00FFFF));
    map.put("aqua", new Color(0x00FFFF));
    map.put("magenta", new Color(0xFF00FF));
    map.put("fuchsia", new Color(0xFF00FF));
    map.put("gray", new Color(0x808080));
    map.put("grey", new Color(0x808080));
    map.put("lightgray", new Color(0xD3D3D3));
    map.put("lightgrey", new Color(0xD3D3D3));
    map.put("darkgray", new Color(0xA9A9A9));
    map.put("darkgrey", new Color(0xA9A9A9));
    map.put("orange", new Color(0xFFA500));
    map.put("brown", new Color(0xA52A2A));
    map.put("purple", new Color(0x800080));
    map.put("pink", new Color(0xFFC0CB));
    map.put("lime", new Color(0x00FF00));
    map.put("navy", new Color(0x000080));
    map.put("teal", new Color(0x008080));
    map.put("olive", new Color(0x808000));
    map.put("maroon", new Color(0x800000));
    map.put("silver", new Color(0xC0C0C0));
    map.put("gold", new Color(0xFFD700));
    return Collections.unmodifiableMap(map);
  }

  private void loadCustomFonts(File fontsDir) {
    if (!fontsDir.exists() || !fontsDir.isDirectory()) return;
    File[] files =
        fontsDir.listFiles(
            (dir, name) -> {
              String lower = name.toLowerCase(Locale.ROOT);
              return lower.endsWith(".ttf") || lower.endsWith(".otf");
            });
    if (files == null) return;
    for (File file : files) {
      try (FileInputStream fis = new FileInputStream(file)) {
        Font font = Font.createFont(Font.TRUETYPE_FONT, fis);
        GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
        getLogger()
            .info("Loaded custom font: " + font.getFamily() + " (" + file.getName() + ")");
      } catch (Exception e) {
        getLogger()
            .warning("Failed to load font " + file.getName() + ": " + e.getMessage());
      }
    }
  }

  // 壁向きに応じた “右” ベクトル（1タイル分）
  private static Vector rightVector(BlockFace face) {
    return switch (face) {
      case NORTH -> new Vector(+1, 0, 0); // +X が右
      case SOUTH -> new Vector(-1, 0, 0); // -X が右
      case EAST -> new Vector(0, 0, +1); // +Z が右
      case WEST -> new Vector(0, 0, -1); // -Z が右
      default -> new Vector(0, 0, 0);
    };
  }

  // 額縁の“ブロック中心”
  private static Location frameBlockCenter(ItemFrame f) {
    Location l = f.getLocation();
    return new Location(
        l.getWorld(),
        Math.floor(l.getX()) + 0.5,
        Math.floor(l.getY()) + 0.5,
        Math.floor(l.getZ()) + 0.5);
  }

  private WhiteboardRenderer getOrAttachRenderer(MapView view) {
    WhiteboardRenderer r = boards.get(view.getId());
    if (r != null) return r;
    for (MapRenderer mr : view.getRenderers()) {
      if (mr instanceof WhiteboardRenderer wr) {
        boards.put(view.getId(), wr);
        return wr;
      }
    }
    WhiteboardRenderer nw = new WhiteboardRenderer();
    view.addRenderer(nw);
    boards.put(view.getId(), nw);
    return nw;
  }

  /* ====== グループ座標系を使って “1行” を分配 ====== */
  private void applyTextAtom(BoardGroup g, TextAtom a, UUID actionId) {
    final double TOL = 0.75;
    Vector ORG = g.baseTopLeft.toVector();
    Vector RIGHT = g.rightUnit.clone();
    Vector DOWN = g.downUnit.clone();

    for (int ty = 0; ty < g.H; ty++)
      for (int tx = 0; tx < g.W; tx++) {
        WhiteboardRenderer r = g.tiles[ty][tx];
        Location center = g.centers[ty][tx];
        if (r == null || center == null) continue;

        Vector rel = center.toVector().subtract(ORG);
        double u = rel.dot(RIGHT);
        double v = rel.dot(DOWN);
        int ix = (int) Math.round(u);
        int iy = (int) Math.round(v);
        if (Math.abs(u - ix) > TOL || Math.abs(v - iy) > TOL) continue;

        int localX = a.gx - ix * 128;
        int localY = a.gy - iy * 128;

        r.addText(new TextEntry(a.msg, a.size, a.col, localX, localY, actionId));
        r.requestRedraw();
      }
  }

  private void removeAction(BoardGroup g, UUID actionId) {
    for (int ty = 0; ty < g.H; ty++)
      for (int tx = 0; tx < g.W; tx++) {
        WhiteboardRenderer r = g.tiles[ty][tx];
        if (r == null) continue;
        r.removeByActionId(actionId);
      }
  }

  private static final class ParsedBookCommand {
    final int size;
    final Color color;
    final int gx;
    final int gy;
    final Integer lineHeight;
    final List<String> extraTokens;

    ParsedBookCommand(
        int size,
        Color color,
        int gx,
        int gy,
        Integer lineHeight,
        List<String> extraTokens) {
      this.size = size;
      this.color = color;
      this.gx = gx;
      this.gy = gy;
      this.lineHeight = lineHeight;
      this.extraTokens = extraTokens;
    }
  }

  private enum RenderMode {
    HTML,
    PLAIN
  }

  private static final class ModePrefixResult {
    final RenderMode mode;
    final boolean explicit;
    final String content;

    ModePrefixResult(RenderMode mode, boolean explicit, String content) {
      this.mode = mode;
      this.explicit = explicit;
      this.content = content;
    }
  }

  private static final class BookDirectives {
    final String content;
    final Integer size;
    final Color color;
    final Integer gx;
    final Integer gy;
    final Integer lineHeight;
    final boolean clearBefore;

    BookDirectives(
        String content,
        Integer size,
        Color color,
        Integer gx,
        Integer gy,
        Integer lineHeight,
        boolean clearBefore) {
      this.content = content;
      this.size = size;
      this.color = color;
      this.gx = gx;
      this.gy = gy;
      this.lineHeight = lineHeight;
      this.clearBefore = clearBefore;
    }
  }

  private static final class LecternHit {
    final ItemStack book;
    final double distanceSq;

    LecternHit(ItemStack book, double distanceSq) {
      this.book = book;
      this.distanceSq = distanceSq;
    }
  }

  static final class BookPayload {
    final String text;
    final RenderMode mode;
    final boolean explicitMode;
    final Integer sizeOverride;
    final Color colorOverride;
    final Integer gxOverride;
    final Integer gyOverride;
    final Integer lineHeightOverride;
    final boolean clearBefore;
    final boolean fromLectern;
    final double distanceSq;

    BookPayload(
        String text,
        RenderMode mode,
        boolean explicitMode,
        Integer sizeOverride,
        Color colorOverride,
        Integer gxOverride,
        Integer gyOverride,
        Integer lineHeightOverride,
        boolean clearBefore) {
      this(
          text,
          mode,
          explicitMode,
          sizeOverride,
          colorOverride,
          gxOverride,
          gyOverride,
          lineHeightOverride,
          clearBefore,
          false,
          0.0);
    }

    BookPayload(
        String text,
        RenderMode mode,
        boolean explicitMode,
        Integer sizeOverride,
        Color colorOverride,
        Integer gxOverride,
        Integer gyOverride,
        Integer lineHeightOverride,
        boolean clearBefore,
        boolean fromLectern,
        double distanceSq) {
      this.text = text;
      this.mode = mode;
      this.explicitMode = explicitMode;
      this.sizeOverride = sizeOverride;
      this.colorOverride = colorOverride;
      this.gxOverride = gxOverride;
      this.gyOverride = gyOverride;
      this.lineHeightOverride = lineHeightOverride;
      this.clearBefore = clearBefore;
      this.fromLectern = fromLectern;
      this.distanceSq = distanceSq;
    }

    BookPayload withLectern(double distSq) {
      return new BookPayload(
          text,
          mode,
          explicitMode,
          sizeOverride,
          colorOverride,
          gxOverride,
          gyOverride,
          lineHeightOverride,
          clearBefore,
          true,
          distSq);
    }
  }

  private GridAssembly resolveGridOrientation(
      int width,
      int height,
      Location baseLoc,
      ItemFrame topLeft,
      List<ItemFrame> candidates,
      List<Vector> offsets,
      Vector rightBase,
      Vector downBase) {
    GridAssembly best = null;

    Vector[][] orientations =
        new Vector[][] {
          {rightBase.clone(), downBase.clone()},
          {rightBase.clone(), downBase.clone().multiply(-1)},
          {rightBase.clone().multiply(-1), downBase.clone()},
          {rightBase.clone().multiply(-1), downBase.clone().multiply(-1)}
        };

    for (Vector[] pair : orientations) {
      ItemFrame[][] grid =
          assembleGrid(width, height, baseLoc, topLeft, candidates, offsets, pair[0], pair[1]);
      if (grid == null) continue;
      int count = countFrames(grid);
      if (best == null || count > best.existingCount) {
        best = new GridAssembly(grid, pair[0], pair[1], count);
      }
    }
    return best;
  }

  private ItemFrame[][] assembleGrid(
      int width,
      int height,
      Location baseLoc,
      ItemFrame topLeft,
      List<ItemFrame> candidates,
      List<Vector> offsets,
      Vector right,
      Vector down) {
    final double tolerance = 0.60;
    ItemFrame[][] grid = new ItemFrame[height][width];
    double[][] bestDist = new double[height][width];
    for (int y = 0; y < height; y++) Arrays.fill(bestDist[y], Double.POSITIVE_INFINITY);

    Vector baseVec = baseLoc.toVector();
    for (int i = 0; i < candidates.size(); i++) {
      Vector rel = offsets.get(i);
      double u = rel.dot(right);
      double v = rel.dot(down);
      int ix = (int) Math.round(u);
      int iy = (int) Math.round(v);
      if (ix < 0 || ix >= width || iy < 0 || iy >= height) continue;
      if (Math.abs(u - ix) > tolerance || Math.abs(v - iy) > tolerance) continue;

      ItemFrame frame = candidates.get(i);
      Vector expected =
          baseVec
              .clone()
              .add(right.clone().multiply(ix))
              .add(down.clone().multiply(iy));
      double distSq = frameBlockCenter(frame).toVector().distanceSquared(expected);
      if (distSq < bestDist[iy][ix]) {
        grid[iy][ix] = frame;
        bestDist[iy][ix] = distSq;
      }
    }

    if (grid[0][0] == null || !grid[0][0].getUniqueId().equals(topLeft.getUniqueId())) return null;
    return grid;
  }

  private int countFrames(ItemFrame[][] grid) {
    int count = 0;
    for (ItemFrame[] row : grid) {
      for (ItemFrame frame : row) if (frame != null) count++;
    }
    return count;
  }

  private Location computeFrameCenter(Location base, Vector right, Vector down, int x, int y) {
    return base.clone()
        .add(right.clone().multiply(x))
        .add(down.clone().multiply(y));
  }

  /* =================== 連結グループ型 =================== */

  private static final class GridAssembly {
    final ItemFrame[][] tiles;
    final Vector right;
    final Vector down;
    final int existingCount;

    GridAssembly(ItemFrame[][] tiles, Vector right, Vector down, int existingCount) {
      this.tiles = tiles;
      this.right = right.clone();
      this.down = down.clone();
      this.existingCount = existingCount;
    }
  }

  private static final class HtmlStyle {
    final Color color;
    final int size;

    HtmlStyle(Color color, int size) {
      this.color = color;
      this.size = size;
    }
  }

  private static final class HtmlToken {
    final String text;
    final Color color;
    final int size;
    final boolean lineBreak;

    private HtmlToken(String text, Color color, int size, boolean lineBreak) {
      this.text = text;
      this.color = color;
      this.size = size;
      this.lineBreak = lineBreak;
    }

    static HtmlToken text(String text, Color color, int size) {
      return new HtmlToken(text, color, size, false);
    }

    static HtmlToken lineBreak() {
      return new HtmlToken("", null, 0, true);
    }
  }

  static final class BoardGroup {
    final String id;
    final int W, H;
    final WhiteboardRenderer[][] tiles; // [H][W]
    final Location[][] centers; // [H][W]
    final UUID[][] frames; // [H][W] ItemFrame UUID
    Location baseTopLeft;
    Vector rightUnit, downUnit;
    boolean locked = true;

    final Deque<TextAction> undo = new ArrayDeque<>();
    final Deque<TextAction> redo = new ArrayDeque<>();

    BoardGroup(String id, int W, int H) {
      this.id = id;
      this.W = W;
      this.H = H;
      this.tiles = new WhiteboardRenderer[H][W];
      this.centers = new Location[H][W];
      this.frames = new UUID[H][W];
    }
  }

  static final class TextAction {
    final UUID id = UUID.randomUUID();
    final List<TextAtom> atoms = new ArrayList<>();
  }

  static final class TextAtom {
    final String msg;
    final int size;
    final Color col;
    final int gx, gy;

    TextAtom(String m, int s, Color c, int x, int y) {
      msg = m;
      size = s;
      col = c;
      gx = x;
      gy = y;
    }
  }
}

/* =================== テキスト要素 & レンダラー =================== */

final class TextEntry {
  final String text;
  final int size;
  final Color color;
  final int x, y;
  final UUID actionId;

  TextEntry(String t, int s, Color c, int x, int y) {
    this(t, s, c, x, y, null);
  }

  TextEntry(String t, int s, Color c, int x, int y, UUID actionId) {
    this.text = t;
    this.size = s;
    this.color = c;
    this.x = x;
    this.y = y;
    this.actionId = actionId;
  }
}

final class WhiteboardRenderer extends MapRenderer {

  private final BufferedImage buffer = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
  private final List<TextEntry> texts = new ArrayList<>();
  private Color background = Color.WHITE;
  private boolean border = true;
  private volatile boolean dirty = true;

  // 既定フォント：Noto（Ubuntu: sudo apt install fonts-noto-cjk 推奨）
  private Font baseFont = new Font("Noto Sans CJK JP", Font.PLAIN, 16);

  WhiteboardRenderer() {
    super(true);
  }

  void setBackground(Color c) {
    this.background = c;
    dirty = true;
  }

  void setBorderVisible(boolean v) {
    this.border = v;
    dirty = true;
  }

  void addText(TextEntry te) {
    this.texts.add(te);
    dirty = true;
  }

  void clearTexts() {
    this.texts.clear();
    dirty = true;
  }

  void resetToDefaults() {
    this.background = Color.WHITE;
    this.border = true;
    this.texts.clear();
    this.dirty = true;
  }

  void setBaseFont(Font f) {
    if (f != null) this.baseFont = f;
    this.dirty = true;
  }

  Font getBaseFont() {
    return baseFont;
  }

  void requestRedraw() {
    this.dirty = true;
  }

  void removeByActionId(UUID id) {
    if (id == null) return;
    texts.removeIf(te -> id.equals(te.actionId));
    dirty = true;
  }

  @Override
  public void render(MapView view, MapCanvas canvas, Player player) {
    if (dirty) {
      Graphics2D g = (Graphics2D) buffer.getGraphics();
      try {
        g.setColor(background);
        g.fillRect(0, 0, 128, 128);

        if (border) {
          g.setColor(new Color(0x404040));
          g.fillRect(0, 0, 128, 2);
          g.fillRect(0, 126, 128, 2);
          g.fillRect(0, 0, 2, 128);
          g.fillRect(126, 0, 2, 128);
        }

        g.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (TextEntry te : texts) {
          g.setFont(baseFont.deriveFont((float) te.size));
          g.setColor(te.color);
          g.drawString(te.text, te.x, te.y);
        }
      } finally {
        g.dispose();
      }
      dirty = false;
    }
    canvas.drawImage(0, 0, buffer);
  }
}

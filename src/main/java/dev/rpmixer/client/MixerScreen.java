package dev.rpmixer.client;

import dev.rpmixer.core.CategoryDef;
import dev.rpmixer.core.Categories;
import dev.rpmixer.core.OverlayBuilder;
import dev.rpmixer.core.PackInfo;
import dev.rpmixer.core.Status;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Eigenes Menü. Bewusst nur mit Standard-Widgets (Buttons, Textfelder) gebaut und ohne Überschreiben von
 * Maus-/Tastatur-Callbacks, damit es von Signaturänderungen der Eingabe-API möglichst unabhängig bleibt.
 */
public final class MixerScreen extends Screen {
    private static final String ENTRY_NONE = "\u0000none";
    private static final String ENTRY_INHERIT = "\u0000inherit";
    private static final int RIGHT_X = 142;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREEN = 0xFF55FF55;
    private static final int ORANGE = 0xFFFFAA00;
    private static final int RED = 0xFFFF5555;
    private static final int GRAY = 0xFFAAAAAA;

    private record Entry(String id, PackInfo pack) {}

    private final Screen parent;
    private final Map<String, String> pending = new LinkedHashMap<>();
    private int catIdx = 0;
    private int matIdx = 0;
    private int page = 0;
    private int pageCount = 1;
    private boolean dirty = false;
    private boolean started = false;
    private String focusedPack = null;
    private Text statusText = Text.empty();
    private int statusColor = WHITE;
    private int infoTop = 0;
    private int infoBottom = 0;

    private TextFieldWidget searchField;
    private TextFieldWidget nameField;
    private TextFieldWidget importField;

    public MixerScreen(Screen parent) {
        super(Text.translatable("rpm.title"));
        this.parent = parent;
        pending.putAll(RpmClient.config.active().selections);
    }

    // ------------------------------------------------------------------ Aufbau

    @Override
    protected void init() {
        if (searchField == null) {
            searchField = new TextFieldWidget(this.textRenderer, 0, 0, 100, 16, Text.empty());
            searchField.setPlaceholder(Text.translatable("rpm.search"));
            searchField.setMaxLength(60);
            searchField.setChangedListener(s -> {
                page = 0;
                rebuild();
            });
            nameField = new TextFieldWidget(this.textRenderer, 0, 0, 96, 16, Text.empty());
            nameField.setMaxLength(40);
            nameField.setText(RpmClient.config.activeProfile);
            importField = new TextFieldWidget(this.textRenderer, 0, 0, 100, 16, Text.empty());
            importField.setPlaceholder(Text.translatable("rpm.import.placeholder"));
            importField.setMaxLength(400);
        }
        if (!started) {
            started = true;
            MappingSelfCheck.runOnce(this.client);
            RpmClient.rescanAsync(err -> {
                if (err != null) setStatus(Text.translatable("rpm.msg.rescan_failed", err), RED);
                else reportMissingReferences();
                rebuild();
            });
        }
        rebuild();
    }

    private CategoryDef cat() {
        return Categories.ALL.get(catIdx);
    }

    private String key() {
        return cat().keyFor(matIdx);
    }

    private ButtonWidget btn(Text label, int x, int y, int w, int h, ButtonWidget.PressAction a) {
        return ButtonWidget.builder(label, a).dimensions(x, y, w, h).build();
    }

    private void rebuild() {
        if (this.client == null) return;
        boolean refocusSearch = searchField.isFocused();
        this.clearChildren();
        CategoryDef cat = cat();
        int w = this.width;
        int h = this.height;

        // Profilzeile
        int x = 6;
        this.addDrawableChild(btn(Text.literal("<"), x, 6, 16, 16, b -> cycleProfile(-1)));
        x += 18;
        this.addDrawableChild(btn(Text.literal(">"), x, 6, 16, 16, b -> cycleProfile(1)));
        x += 18;
        nameField.setX(x);
        nameField.setY(6);
        nameField.setWidth(96);
        this.addDrawableChild(nameField);
        x += 98;
        this.addDrawableChild(btn(Text.translatable("rpm.btn.new"), x, 6, 36, 16, b -> newProfile()));
        x += 38;
        this.addDrawableChild(btn(Text.translatable("rpm.btn.rename"), x, 6, 46, 16, b -> renameProfile()));
        x += 48;
        this.addDrawableChild(btn(Text.translatable("rpm.btn.delete"), x, 6, 42, 16, b -> deleteProfile()));
        x += 44;
        this.addDrawableChild(btn(Text.translatable("rpm.btn.export"), x, 6, 46, 16, b -> exportProfile()));

        // Kategorien (2 Spalten)
        for (int i = 0; i < Categories.ALL.size(); i++) {
            final int idx = i;
            CategoryDef c = Categories.ALL.get(i);
            boolean any = pending.keySet().stream().anyMatch(k -> k.equals(c.id) || k.startsWith(c.id + ":"));
            MutableText label = Text.translatable("rpm.category." + c.id);
            if (i == catIdx) label = Text.literal("> ").append(label).formatted(Formatting.YELLOW);
            else if (any) label = label.formatted(Formatting.GREEN);
            int bx = 6 + (i % 2) * 65;
            int by = 28 + (i / 2) * 15;
            this.addDrawableChild(btn(label, bx, by, 64, 14, b -> {
                catIdx = idx;
                matIdx = 0;
                page = 0;
                rebuild();
            }));
        }
        infoTop = 28 + ((Categories.ALL.size() + 1) / 2) * 15 + 4;

        // Rechte Seite: Suche, Material, Liste
        int rw = w - RIGHT_X - 6;
        searchField.setX(RIGHT_X);
        searchField.setY(26);
        searchField.setWidth(rw);
        this.addDrawableChild(searchField);
        int listTop = 46;
        if (cat.perMaterial) {
            this.addDrawableChild(btn(Text.literal("<"), RIGHT_X, 44, 16, 14, b -> cycleMaterial(-1)));
            ButtonWidget mat = btn(materialLabel(), RIGHT_X + 18, 44, rw - 36, 14, b -> { });
            mat.active = false;
            this.addDrawableChild(mat);
            this.addDrawableChild(btn(Text.literal(">"), RIGHT_X + rw - 16, 44, 16, 14, b -> cycleMaterial(1)));
            listTop = 62;
        }
        int pageBtnY = h - 66;
        int listBottom = h - 69;
        infoBottom = pageBtnY - 2;
        List<Entry> entries = buildEntries();
        int rowH = 17;
        int pageSize = Math.max(1, (listBottom - listTop) / rowH);
        pageCount = Math.max(1, (entries.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pageCount - 1));
        for (int i = page * pageSize; i < Math.min(entries.size(), (page + 1) * pageSize); i++) {
            Entry e = entries.get(i);
            int by = listTop + (i - page * pageSize) * rowH;
            ButtonWidget b = ButtonWidget.builder(entryLabel(e), bb -> choose(e)).dimensions(RIGHT_X, by, rw, 16)
                    .tooltip(Tooltip.of(entryTooltip(e))).build();
            this.addDrawableChild(b);
        }
        this.addDrawableChild(btn(Text.literal("<"), RIGHT_X, pageBtnY, 24, 14, b -> {
            page = Math.max(0, page - 1);
            rebuild();
        }));
        this.addDrawableChild(btn(Text.literal(">"), RIGHT_X + 26, pageBtnY, 24, 14, b -> {
            page = Math.min(pageCount - 1, page + 1);
            rebuild();
        }));

        // Untere Zeilen
        importField.setX(6);
        importField.setY(h - 47);
        importField.setWidth(w - 6 - 6 - 70);
        this.addDrawableChild(importField);
        this.addDrawableChild(btn(Text.translatable("rpm.btn.import"), w - 6 - 66, h - 47, 66, 16,
                b -> importFromField()));
        int bw = (w - 12 - 5 * 2) / 6;
        int by2 = h - 29;
        String[] keys = {"rescan", "folder", "remove", "apply", "reset", "done"};
        ButtonWidget.PressAction[] actions = {
                b -> rescan(), b -> openFolder(), b -> removeFocused(), b -> apply(), b -> resetAll(), b -> close()};
        for (int i = 0; i < 6; i++) {
            this.addDrawableChild(btn(Text.translatable("rpm.btn." + keys[i]), 6 + i * (bw + 2), by2, bw, 16,
                    actions[i]));
        }
        if (refocusSearch) {
            this.setFocused(searchField);
            searchField.setFocused(true);
        }
    }

    private Text materialLabel() {
        Text m = matIdx == 0 ? Text.translatable("rpm.material.all")
                : Text.translatable("rpm.material." + Categories.MATERIALS.get(matIdx - 1));
        return Text.translatable("rpm.material.label", m);
    }

    private void cycleMaterial(int dir) {
        int n = Categories.MATERIALS.size() + 1;
        matIdx = (matIdx + dir + n) % n;
        page = 0;
        rebuild();
    }

    // ------------------------------------------------------------------ Liste

    private List<Entry> buildEntries() {
        List<Entry> list = new ArrayList<>();
        if (matIdx > 0) list.add(new Entry(ENTRY_INHERIT, null));
        list.add(new Entry(ENTRY_NONE, null));
        String sel = pending.get(key());
        if (sel != null && !OverlayBuilder.SKIP.equals(sel) && RpmClient.library.get(sel) == null) {
            list.add(new Entry(sel, null)); // fehlendes Pack sichtbar machen
        }
        String q = searchField.getText().trim().toLowerCase(Locale.ROOT);
        for (PackInfo p : RpmClient.library.packs()) {
            if (q.isEmpty() || p.name.toLowerCase(Locale.ROOT).contains(q)) list.add(new Entry(p.id, p));
        }
        return list;
    }

    private boolean isSelected(Entry e) {
        String sel = pending.get(key());
        return switch (e.id) {
            case ENTRY_INHERIT -> sel == null;
            case ENTRY_NONE -> matIdx > 0 ? OverlayBuilder.SKIP.equals(sel) : sel == null;
            default -> e.id.equals(sel);
        };
    }

    private Text entryLabel(Entry e) {
        MutableText t;
        if (e.id.equals(ENTRY_INHERIT)) {
            t = Text.translatable("rpm.entry.inherit");
        } else if (e.id.equals(ENTRY_NONE)) {
            t = Text.translatable("rpm.entry.none");
        } else if (e.pack == null) {
            t = Text.translatable("rpm.entry.missing", e.id).formatted(Formatting.RED);
        } else if (!e.pack.valid) {
            t = Text.literal(e.pack.name + " - ").append(Text.translatable("rpm.status.invalid"))
                    .formatted(Formatting.DARK_RED);
        } else {
            Status s = e.pack.status(cat().targetsFor(matIdx));
            t = Text.literal(e.pack.name + " - ").append(Text.translatable("rpm.status." + s.name().toLowerCase(Locale.ROOT)))
                    .formatted(switch (s) {
                        case AVAILABLE -> Formatting.GREEN;
                        case PARTIAL -> Formatting.YELLOW;
                        case NONE -> Formatting.GRAY;
                    });
        }
        return isSelected(e) ? Text.literal("> ").formatted(Formatting.YELLOW).append(t) : t;
    }

    private Text entryTooltip(Entry e) {
        if (e.id.equals(ENTRY_INHERIT)) return Text.translatable("rpm.tip.inherit");
        if (e.id.equals(ENTRY_NONE)) return Text.translatable("rpm.tip.none");
        if (e.pack == null) return Text.translatable("rpm.tip.missing");
        PackInfo p = e.pack;
        MutableText t = Text.literal(p.id).append("\n");
        t.append(Text.translatable(p.inLibrary ? "rpm.tip.copy" : "rpm.tip.original")).append("\n");
        if (!p.valid) return t.append(Text.literal(RpmClient.scrub(p.error)).formatted(Formatting.RED));
        List<String> found = new ArrayList<>();
        for (CategoryDef c : Categories.ALL) {
            Status s = p.status(c.targets);
            if (s != Status.NONE) {
                String n = Text.translatable("rpm.category." + c.id).getString();
                found.add(s == Status.PARTIAL ? n + "*" : n);
            }
        }
        t.append(Text.translatable("rpm.tip.detected", found.isEmpty() ? "-" : String.join(", ", found)));
        if (p.customSky) t.append("\n").append(Text.translatable("rpm.tip.customsky").formatted(Formatting.GOLD));
        return t;
    }

    private void choose(Entry e) {
        String k = key();
        switch (e.id) {
            case ENTRY_INHERIT -> pending.remove(k);
            case ENTRY_NONE -> {
                if (matIdx > 0) pending.put(k, OverlayBuilder.SKIP);
                else pending.remove(k);
            }
            default -> {
                pending.put(k, e.id);
                focusedPack = e.id;
            }
        }
        dirty = true;
        setStatus(Text.translatable("rpm.msg.pending"), ORANGE);
        rebuild();
    }

    // ------------------------------------------------------------------ Profile

    private void cycleProfile(int dir) {
        List<String> names = RpmClient.config.profileNames();
        int i = Math.max(0, names.indexOf(RpmClient.config.activeProfile));
        String next = names.get((i + dir + names.size()) % names.size());
        RpmClient.config.activeProfile = next;
        pending.clear();
        pending.putAll(RpmClient.config.active().selections);
        nameField.setText(next);
        dirty = true;
        saveConfig();
        reportMissingReferences();
        if (statusColor != ORANGE && statusColor != RED) {
            setStatus(Text.translatable("rpm.msg.profile_loaded", next), GREEN);
        }
        rebuild();
    }

    private void newProfile() {
        String n = RpmClient.config.createProfile(nameField.getText(), pending);
        if (n == null) {
            setStatus(Text.translatable("rpm.msg.profile_name_invalid"), RED);
        } else {
            saveConfig();
            setStatus(Text.translatable("rpm.msg.profile_created", n), GREEN);
        }
        rebuild();
    }

    private void renameProfile() {
        String old = RpmClient.config.activeProfile;
        if (RpmClient.config.renameProfile(old, nameField.getText())) {
            saveConfig();
            setStatus(Text.translatable("rpm.msg.profile_renamed", RpmClient.config.activeProfile), GREEN);
        } else {
            setStatus(Text.translatable("rpm.msg.profile_name_invalid"), RED);
        }
        rebuild();
    }

    private void deleteProfile() {
        String name = RpmClient.config.activeProfile;
        if (RpmClient.config.profiles.size() <= 1) {
            setStatus(Text.translatable("rpm.msg.profile_last"), RED);
            return;
        }
        this.client.setScreen(new ConfirmScreen(yes -> {
            if (yes && RpmClient.config.deleteProfile(name)) {
                pending.clear();
                pending.putAll(RpmClient.config.active().selections);
                nameField.setText(RpmClient.config.activeProfile);
                saveConfig();
                setStatus(Text.translatable("rpm.msg.profile_deleted", name), GREEN);
            }
            this.client.setScreen(this);
        }, Text.translatable("rpm.confirm.profile.title"), Text.translatable("rpm.confirm.profile.msg", name)));
    }

    private void exportProfile() {
        String name = RpmClient.config.activeProfile;
        Map<String, String> snapshot = new LinkedHashMap<>(pending);
        String file = name.replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
        Path target = RpmClient.dataDir.resolve("exports").resolve(file);
        RpmClient.IO.submit(() -> {
            try {
                RpmClient.config.exportProfile(name, snapshot, target);
                MinecraftClient.getInstance().execute(() ->
                        setStatus(Text.translatable("rpm.msg.exported", "resourcepackmixer/exports/" + file), GREEN));
            } catch (IOException e) {
                String m = RpmClient.scrub(e.getMessage());
                MinecraftClient.getInstance().execute(() -> setStatus(Text.translatable("rpm.msg.error", m), RED));
            }
        });
    }

    private void saveConfig() {
        try {
            RpmClient.config.save(RpmClient.configFile);
        } catch (IOException e) {
            setStatus(Text.translatable("rpm.msg.error", RpmClient.scrub(e.getMessage())), RED);
        }
    }

    // ------------------------------------------------------------------ Aktionen

    private void reportMissingReferences() {
        int missing = 0;
        for (String v : pending.values()) {
            if (!OverlayBuilder.SKIP.equals(v) && RpmClient.library.get(v) == null) missing++;
        }
        if (missing > 0) setStatus(Text.translatable("rpm.msg.missing_refs", missing), ORANGE);
    }

    private void apply() {
        if (OverlayApplier.isBusy()) {
            setStatus(Text.translatable("rpm.msg.busy"), ORANGE);
            return;
        }
        RpmClient.config.active().selections = new LinkedHashMap<>(pending);
        saveConfig();
        setStatus(Text.translatable("rpm.msg.applying"), WHITE);
        OverlayApplier.apply(pending, o -> {
            if (!o.ok()) {
                setStatus(Text.translatable(o.error().equals("busy") ? "rpm.msg.busy" : "rpm.msg.error", o.error()), RED);
                return;
            }
            for (String w : o.warnings()) RpmClient.LOG.warn("Overlay: {}", w);
            for (String n : o.notes()) RpmClient.LOG.info("Overlay: {}", n);
            dirty = false;
            if (!o.overlayActive()) {
                setStatus(Text.translatable("rpm.msg.reset_done"), GREEN);
            } else if (!o.warnings().isEmpty()) {
                setStatus(Text.translatable("rpm.msg.applied_warn", o.files(), o.packs(), o.warnings().get(0)), ORANGE);
            } else {
                setStatus(Text.translatable("rpm.msg.applied", o.files(), o.packs()), GREEN);
            }
        });
    }

    private void resetAll() {
        pending.clear();
        dirty = true;
        setStatus(Text.translatable("rpm.msg.reset_pending"), ORANGE);
        rebuild();
    }

    private void rescan() {
        setStatus(Text.translatable("rpm.msg.rescanning"), WHITE);
        RpmClient.rescanAsync(err -> {
            if (err != null) setStatus(Text.translatable("rpm.msg.rescan_failed", err), RED);
            else {
                setStatus(Text.translatable("rpm.msg.rescanned", RpmClient.library.packs().size()), GREEN);
                reportMissingReferencesIfAny();
            }
            rebuild();
        });
    }

    private void reportMissingReferencesIfAny() {
        int before = 0;
        for (String v : pending.values()) if (!OverlayBuilder.SKIP.equals(v) && RpmClient.library.get(v) == null) before++;
        if (before > 0) setStatus(Text.translatable("rpm.msg.missing_refs", before), ORANGE);
    }

    private void openFolder() {
        Path dir = RpmClient.library.libraryDir();
        try {
            Files.createDirectories(dir);
            Util.getOperatingSystem().open(dir.toUri());
        } catch (IOException | RuntimeException e) {
            setStatus(Text.translatable("rpm.msg.error", RpmClient.scrub(e.getMessage())), RED);
        }
    }

    private void importFromField() {
        String raw = importField.getText().trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) raw = raw.substring(1, raw.length() - 1);
        if (raw.isEmpty()) {
            setStatus(Text.translatable("rpm.msg.import_empty"), ORANGE);
            return;
        }
        Path p;
        try {
            p = Path.of(raw);
        } catch (InvalidPathException e) {
            setStatus(Text.translatable("rpm.msg.import_bad_path"), RED);
            return;
        }
        if (raw.toLowerCase(Locale.ROOT).endsWith(".json")) importProfileFile(p);
        else importPaths(List.of(p));
    }

    /** Drag-and-drop aus dem Dateimanager (ohne @Override, damit eine abweichende Signatur nicht den Build bricht). */
    public void onFilesDropped(List<Path> paths) {
        importPaths(paths);
    }

    private void importPaths(List<Path> paths) {
        setStatus(Text.translatable("rpm.msg.importing"), WHITE);
        List<Path> copy = new ArrayList<>(paths);
        java.util.Set<String> hidden = new TreeSet<>(RpmClient.config.hiddenPacks);
        RpmClient.IO.submit(() -> {
            int ok = 0;
            String err = null;
            for (Path p : copy) {
                try {
                    RpmClient.library.importPack(p);
                    ok++;
                } catch (IOException | RuntimeException e) {
                    err = (p.getFileName() == null ? "?" : p.getFileName().toString()) + ": "
                            + RpmClient.scrub(e.getMessage());
                }
            }
            try {
                RpmClient.library.rescan(hidden);
            } catch (IOException | RuntimeException e) {
                err = RpmClient.scrub(e.getMessage());
            }
            int fok = ok;
            String ferr = err;
            MinecraftClient.getInstance().execute(() -> {
                if (ferr != null) setStatus(Text.translatable("rpm.msg.import_failed", ferr), RED);
                else setStatus(Text.translatable("rpm.msg.imported", fok), GREEN);
                rebuild();
            });
        });
    }

    private void importProfileFile(Path p) {
        RpmClient.IO.submit(() -> {
            try {
                String name = RpmClient.config.importProfile(p);
                MinecraftClient.getInstance().execute(() -> {
                    saveConfig();
                    setStatus(Text.translatable("rpm.msg.profile_imported", name), GREEN);
                    rebuild();
                });
            } catch (IOException e) {
                String m = RpmClient.scrub(e.getMessage());
                MinecraftClient.getInstance().execute(() -> setStatus(Text.translatable("rpm.msg.error", m), RED));
            }
        });
    }

    private void removeFocused() {
        if (focusedPack == null || RpmClient.library.get(focusedPack) == null) {
            setStatus(Text.translatable("rpm.msg.remove_select"), ORANGE);
            return;
        }
        String id = focusedPack;
        PackInfo info = RpmClient.library.get(id);
        boolean copy = info.inLibrary;
        this.client.setScreen(new ConfirmScreen(yes -> {
            this.client.setScreen(this);
            if (!yes) return;
            if (copy) {
                RpmClient.IO.submit(() -> {
                    try {
                        RpmClient.library.removeCopy(id);
                    } catch (IOException e) {
                        RpmClient.LOG.warn("Entfernen fehlgeschlagen: {}", RpmClient.scrub(e.getMessage()));
                    }
                    MinecraftClient.getInstance().execute(this::afterRemove);
                });
            } else {
                RpmClient.config.hiddenPacks.add(id); // Original bleibt unangetastet, nur ausgeblendet
                saveConfig();
                afterRemove();
            }
        }, Text.translatable("rpm.confirm.remove.title"),
                Text.translatable(copy ? "rpm.confirm.remove.copy" : "rpm.confirm.remove.hide", info.name)));
    }

    private void afterRemove() {
        focusedPack = null;
        RpmClient.rescanAsync(err -> {
            setStatus(Text.translatable("rpm.msg.removed"), GREEN);
            reportMissingReferencesIfAny();
            rebuild();
        });
    }

    private void setStatus(Text t, int color) {
        statusText = t;
        statusColor = color;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    // ------------------------------------------------------------------ Zeichnen

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        CategoryDef cat = cat();
        int y = infoTop;

        Text head = Text.translatable("rpm.category." + cat.id);
        ctx.drawTextWithShadow(this.textRenderer, head, 6, y, 0xFFFFFF55);
        y += 11;

        String sel = pending.get(key());
        Text selText;
        PackInfo selInfo = null;
        if (sel == null) selText = Text.translatable(matIdx > 0 ? "rpm.entry.inherit" : "rpm.entry.none");
        else if (OverlayBuilder.SKIP.equals(sel)) selText = Text.translatable("rpm.entry.none");
        else {
            selInfo = RpmClient.library.get(sel);
            selText = selInfo == null ? Text.translatable("rpm.entry.missing", sel) : Text.literal(selInfo.name);
        }
        y = wrap(ctx, Text.translatable("rpm.info.selected", selText), 6, y, 128, WHITE);
        if (selInfo != null) {
            Status s = selInfo.valid ? selInfo.status(cat.targetsFor(matIdx)) : null;
            Text st = s == null ? Text.translatable("rpm.status.invalid")
                    : Text.translatable("rpm.status." + s.name().toLowerCase(Locale.ROOT));
            int col = s == null ? RED : s == Status.AVAILABLE ? GREEN : s == Status.PARTIAL ? ORANGE : GRAY;
            y = wrap(ctx, Text.translatable("rpm.info.status", st), 6, y, 128, col);
            if (cat.id.equals("sky") && selInfo.customSky) {
                wrap(ctx, Text.translatable("rpm.info.customsky"), 6, y, 128, ORANGE);
            }
        }

        ctx.drawTextWithShadow(this.textRenderer, Text.literal((page + 1) + "/" + pageCount), RIGHT_X + 54,
                this.height - 63, GRAY);
        if (dirty) {
            ctx.drawTextWithShadow(this.textRenderer, Text.translatable("rpm.info.dirty"), RIGHT_X + 90,
                    this.height - 63, ORANGE);
        }
        ctx.drawTextWithShadow(this.textRenderer, statusText, 6, this.height - 11, statusColor);
    }

    private int wrap(DrawContext ctx, Text text, int x, int y, int width, int color) {
        for (OrderedText line : this.textRenderer.wrapLines(text, width)) {
            if (y > infoBottom) break;
            ctx.drawTextWithShadow(this.textRenderer, line, x, y, color);
            y += 10;
        }
        return y;
    }
}

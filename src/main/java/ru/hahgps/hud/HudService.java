package ru.hahgps.hud;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import ru.hahgps.config.Messages;
import ru.hahgps.config.PluginConfig;
import ru.hahgps.model.NavStep;
import ru.hahgps.model.TurnInfo;
import ru.hahgps.model.Vec;
import ru.hahgps.nav.NavigationSession;
import ru.hahgps.util.Text;

import java.util.List;

public final class HudService {

    public record View(NavStep step, Vec position, double remaining, double total, int index, int count,
                       boolean wrongWorld, String stepWorld, String destination) {
    }

    private final PluginConfig config;
    private final Messages messages;

    public HudService(PluginConfig config, Messages messages) {
        this.config = config;
        this.messages = messages;
    }

    public void attach(NavigationSession session, Player player) {
        if (config.bossBarEnabled()) {
            BossBar bar = BossBar.bossBar(Component.empty(), 1.0F,
                    config.barColor(session.transport()), BossBar.Overlay.PROGRESS);
            player.showBossBar(bar);
            session.setBossBar(bar);
        }
        if (!config.itemsAdderEnabled() || !ItemsAdderHook.available()) {
            return;
        }
        Object holder = ItemsAdderHook.createHolder(player);
        if (holder == null) {
            return;
        }
        session.setItemsAdderHolder(holder);

        Object hud = ItemsAdderHook.createCustomHud(holder, config.itemsAdderHudId());
        if (hud == null) {
            hud = ItemsAdderHook.createQuantityHud(holder, config.itemsAdderHudId());
        }
        if (hud == null) {
            hud = ItemsAdderHook.createHud(holder, config.itemsAdderHudId());
        }
        if (hud != null) {
            ItemsAdderHook.setVisible(hud, true);
            session.setItemsAdderHud(hud);
        }

        Object totalHud = ItemsAdderHook.createQuantityHud(holder, config.itemsAdderTotalHudId());
        if (totalHud == null) {
            totalHud = ItemsAdderHook.createHud(holder, config.itemsAdderTotalHudId());
        }
        if (totalHud != null) {
            ItemsAdderHook.setVisible(totalHud, true);
            session.setItemsAdderTotalHud(totalHud);
        }
    }

    public void update(NavigationSession session, Player player, View view) {
        NavStep step = view.step();
        TurnInfo turn = step.nextTurn(view.position(),
                config.turnStraightDegrees(), config.turnSlightDegrees(), config.turnSharpDegrees());
        double progress = view.total() <= 0.0D
                ? 1.0D
                : Math.max(0.0D, Math.min(1.0D, 1.0D - (view.remaining() / view.total())));
        String percent = String.valueOf((int) Math.round(progress * 100.0D));
        String next = step.nextLabel(view.position(), 2.0D);
        String turnIcon = messages.raw("turns." + turn.turn().id());

        if (session.bossBar() != null) {
            session.bossBar().name(messages.get("hud.bossbar",
                    "turn", turnIcon,
                    "icon", step.transport().icon(),
                    "step", step.title(),
                    "remaining", Text.distance(view.remaining()),
                    "total", Text.distance(view.total()),
                    "percent", percent,
                    "turn_distance", Text.distance(turn.distance()),
                    "dest", view.destination(),
                    "transport", step.transport().displayName(),
                    "index", String.valueOf(view.index() + 1),
                    "count", String.valueOf(view.count())));
            session.bossBar().progress((float) progress);
        }

        if (config.actionBarEnabled()) {
            if (view.wrongWorld()) {
                player.sendActionBar(messages.get("hud.other-world",
                        "world", view.stepWorld() == null ? "-" : view.stepWorld(),
                        "remaining", Text.distance(view.remaining())));
            } else {
                boolean hasNext = !next.equals(step.targetLabel());
                player.sendActionBar(messages.get(hasNext ? "hud.actionbar" : "hud.actionbar-no-next",
                        "turn", turnIcon,
                        "icon", step.transport().icon(),
                        "step", step.title(),
                        "remaining", Text.distance(view.remaining()),
                        "total", Text.distance(view.total()),
                        "percent", percent,
                        "turn_distance", Text.distance(turn.distance()),
                        "turn_label", turn.label(),
                        "next", next,
                        "dest", view.destination(),
                        "index", String.valueOf(view.index() + 1),
                        "count", String.valueOf(view.count())));
            }
        }

        updateItemsAdder(session, step, turn, view.remaining());
    }

    private void updateItemsAdder(NavigationSession session, NavStep step, TurnInfo turn, double remaining) {
        if (!config.itemsAdderQuantity()) {
            return;
        }
        if (session.itemsAdderHud() != null) {
            String imagesKey = step.transport().id() + "|" + turn.turn().id();
            if (!imagesKey.equals(session.itemsAdderImages())) {
                session.setItemsAdderImages(imagesKey);
                ItemsAdderHook.setImages(session.itemsAdderHud(),
                        List.of(config.turnImage(turn.turn()), config.transportImage(step.transport())));
            }
            int meters = (int) Math.round(turn.distance());
            if (session.lastHudQuantity() != meters) {
                session.setLastHudQuantity(meters);
                ItemsAdderHook.setValue(session.itemsAdderHud(), meters);
            }
        }
        if (session.itemsAdderTotalHud() != null) {
            int meters = (int) Math.round(remaining);
            if (session.lastTotalQuantity() != meters) {
                session.setLastTotalQuantity(meters);
                ItemsAdderHook.setValue(session.itemsAdderTotalHud(), meters);
            }
        }
    }

    public void detach(NavigationSession session, Player player) {
        if (session.bossBar() != null) {
            if (player != null) {
                player.hideBossBar(session.bossBar());
            }
            session.setBossBar(null);
        }
        if (session.itemsAdderHud() != null) {
            ItemsAdderHook.setVisible(session.itemsAdderHud(), false);
            session.setItemsAdderHud(null);
        }
        if (session.itemsAdderTotalHud() != null) {
            ItemsAdderHook.setVisible(session.itemsAdderTotalHud(), false);
            session.setItemsAdderTotalHud(null);
        }
        session.setItemsAdderHolder(null);
        session.setItemsAdderImages("");
    }
}

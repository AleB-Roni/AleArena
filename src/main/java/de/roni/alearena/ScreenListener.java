package de.roni.alearena;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Schützt die Leinwand-Rahmen und leitet Klicks an den Browser weiter. */
final class ScreenListener implements Listener {

    private final AleArenaPlugin plugin;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    ScreenListener(AleArenaPlugin plugin) {
        this.plugin = plugin;
    }

    private Screen screenOf(Entity e) {
        String tag = e.getPersistentDataContainer().get(plugin.key(), PersistentDataType.STRING);
        return tag == null ? null : plugin.screens().get(tag);
    }

    private boolean isOurs(Entity e) {
        return e.getPersistentDataContainer().has(plugin.key(), PersistentDataType.STRING);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBreak(HangingBreakEvent e) {
        if (isOurs(e.getEntity())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent e) {
        if (!isOurs(e.getEntity())) {
            return;
        }
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent ed && ed.getDamager() instanceof Player p) {
            handle(p, e.getEntity(), false);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!isOurs(e.getRightClicked())) {
            return;
        }
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) {
            handle(e.getPlayer(), e.getRightClicked(), true);
        }
    }

    private void handle(Player p, Entity frame, boolean right) {
        Screen sc = screenOf(frame);
        if (sc == null || sc.browser == null || !p.hasPermission("alearena.control")) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        if (last != null && now - last < 200) {
            return;
        }
        lastClick.put(p.getUniqueId(), now);
        boolean sneak = p.isSneaking();
        plugin.screens().click(p, sc, sneak && !right, sneak && right);
    }
}

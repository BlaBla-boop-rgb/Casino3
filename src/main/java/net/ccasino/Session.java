package net.ccasino;

import net.ccasino.screens.Screen;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** Ein Spieler am Casino-Tisch. Hält das offene Inventar und den aktuellen Screen. */
public final class Session implements InventoryHolder {
	private final CasinoPlugin plugin;
	private final Player player;
	private Inventory inv;
	private Screen screen;
	private boolean switching;
	private long tick;
	private long partyUntil = -1;
	private final java.util.List<Screen> keepAlive = new java.util.ArrayList<>();

	public Session(CasinoPlugin plugin, Player player) {
		this.plugin = plugin;
		this.player = player;
	}

	public CasinoPlugin plugin() { return plugin; }
	public Player player() { return player; }
	public long tick() { return tick; }
	public boolean fancy() { return plugin.fancy(player); }
	public boolean isSwitching() { return switching; }

	@Override
	public Inventory getInventory() { return inv; }

	public void open(Screen next) {
		if (screen != null && screen != next && !keepAlive.contains(screen)) screen.forceFinish();
		keepAlive.remove(next);
		screen = next;
		inv = Bukkit.createInventory(this, 54, next.title());
		next.render(inv);
		switching = true;
		try {
			player.openInventory(inv);
		} finally {
			switching = false;
		}
	}

	/** Wechsel aus einem Klick heraus: erst im nächsten Tick öffnen (Bukkit-Empfehlung). */
	public void switchTo(Screen next) {
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (player.isOnline() && inv != null && player.getOpenInventory().getTopInventory() == inv) open(next);
		});
	}

	/** Screen, der beim Wechsel nicht abgerechnet werden soll (z. B. Roulette während der Zahlenwahl). */
	public void keep(Screen s, boolean keep) {
		if (keep) keepAlive.add(s);
		else keepAlive.remove(s);
	}

	public void update() {
		tick++;
		if (screen == null || inv == null) return;
		screen.tick();
		screen.render(inv);
	}

	public void click(int slot, boolean right) {
		if (screen != null) screen.click(slot, right);
	}

	public void finishAll() {
		for (Screen s : keepAlive) s.forceFinish();
		keepAlive.clear();
		if (screen != null) screen.forceFinish();
	}

	// ------------------------------------------------------------------ Einsatz

	public double bet() { return plugin.chips()[plugin.betIndex(player)]; }
	public int betIndex() { return plugin.betIndex(player); }
	public void setBetIndex(int i) { plugin.setBetIndex(player, i); }

	// ------------------------------------------------------------------ Party (blinkende Lichter)

	public void party(int ticks) { partyUntil = tick + ticks; }
	public boolean party() { return tick < partyUntil; }
}

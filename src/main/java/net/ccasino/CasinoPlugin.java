package net.ccasino;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.ccasino.screens.LobbyScreen;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class CasinoPlugin extends JavaPlugin implements Listener {
	private static CasinoPlugin instance;

	private Economy economy;
	private final Map<UUID, Session> sessions = new HashMap<>();
	private final Map<UUID, Integer> betIndex = new HashMap<>();
	private final Set<UUID> packLoaded = new HashSet<>();
	private double[] chips = {10, 50, 100, 500, 1000};
	private ResourcePackInfo packInfo;
	private UUID packId;

	public static CasinoPlugin get() { return instance; }

	@Override
	public void onEnable() {
		instance = this;
		saveDefaultConfig();
		if (!setupEconomy()) {
			getLogger().severe("Kein Vault-Economy-Plugin gefunden (z. B. EssentialsX). Craft Casino wird deaktiviert.");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		loadSettings();
		getServer().getPluginManager().registerEvents(this, this);
		Bukkit.getScheduler().runTaskTimer(this, () -> {
			for (Session s : sessions.values().toArray(new Session[0])) {
				try {
					s.update();
				} catch (Exception ex) {
					getLogger().warning("Fehler in Casino-Session von " + s.player().getName() + ": " + ex);
					ex.printStackTrace();
				}
			}
		}, 1L, 1L);
	}

	@Override
	public void onDisable() {
		for (Session s : sessions.values().toArray(new Session[0])) {
			s.finishAll();
			s.player().closeInventory();
		}
		sessions.clear();
	}

	private boolean setupEconomy() {
		if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
		RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
		if (rsp == null) return false;
		economy = rsp.getProvider();
		return economy != null;
	}

	private void loadSettings() {
		reloadConfig();
		List<Double> list = getConfig().getDoubleList("chips");
		if (list.size() == 5) {
			chips = new double[5];
			for (int i = 0; i < 5; i++) chips[i] = list.get(i);
		} else {
			getLogger().warning("config.yml: 'chips' braucht genau 5 Werte – benutze Standard 10/50/100/500/1000.");
		}
		packInfo = null;
		String url = getConfig().getString("resource-pack.url", "");
		if (getConfig().getBoolean("resource-pack.enabled", true) && url != null && !url.isBlank()) {
			packId = UUID.nameUUIDFromBytes(("ccasino:" + url).getBytes(StandardCharsets.UTF_8));
			try {
				ResourcePackInfo.resourcePackInfo().id(packId).uri(URI.create(url)).computeHashAndBuild()
						.whenComplete((info, err) -> {
							if (err != null) {
								getLogger().warning("Resourcepack konnte nicht geladen werden (" + err.getMessage() + ") – sende ohne Hash.");
								packInfo = ResourcePackInfo.resourcePackInfo().id(packId).uri(URI.create(url)).build();
							} else {
								packInfo = info;
								getLogger().info("Casino-Resourcepack bereit (SHA-1 " + info.hash() + ").");
							}
						});
			} catch (Exception ex) {
				getLogger().warning("Ungültige Resourcepack-URL: " + url);
			}
		} else {
			getLogger().info("Kein Casino-Resourcepack eingestellt – das Casino läuft im Vanilla-Look.");
		}
	}

	// ------------------------------------------------------------------ Economy

	public double balance(Player p) { return economy.getBalance(p); }

	public boolean take(Player p, double amount) {
		if (amount <= 0 || !economy.has(p, amount)) return false;
		return economy.withdrawPlayer(p, amount).transactionSuccess();
	}

	public void give(Player p, double amount) {
		if (amount > 0) economy.depositPlayer(p, amount);
	}

	public String money(double v) { return economy.format(v); }

	public double[] chips() { return chips; }

	public int betIndex(Player p) { return betIndex.getOrDefault(p.getUniqueId(), 1); }

	public void setBetIndex(Player p, int i) { betIndex.put(p.getUniqueId(), i); }

	// ------------------------------------------------------------------ Resourcepack

	public boolean fancy(Player p) {
		return packInfo != null && packLoaded.contains(p.getUniqueId());
	}

	private void sendPack(Player p) {
		if (packInfo == null) return;
		String prompt = getConfig().getString("resource-pack.prompt", "");
		ResourcePackRequest req = ResourcePackRequest.resourcePackRequest()
				.packs(packInfo)
				.required(getConfig().getBoolean("resource-pack.required", false))
				.replace(false) // vorhandene Server-Packs (z. B. ClansPack) bleiben aktiv
				.prompt(Tiles.text(prompt))
				.build();
		p.sendResourcePacks(req);
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		Player p = e.getPlayer();
		Bukkit.getScheduler().runTaskLater(this, () -> {
			if (p.isOnline()) sendPack(p);
		}, 40L);
	}

	@EventHandler
	public void onPackStatus(PlayerResourcePackStatusEvent e) {
		if (packId == null || !packId.equals(e.getID())) return;
		switch (e.getStatus()) {
			case SUCCESSFULLY_LOADED -> packLoaded.add(e.getPlayer().getUniqueId());
			case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> packLoaded.remove(e.getPlayer().getUniqueId());
			default -> { }
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent e) {
		Session s = sessions.remove(e.getPlayer().getUniqueId());
		if (s != null) s.finishAll();
		packLoaded.remove(e.getPlayer().getUniqueId());
	}

	// ------------------------------------------------------------------ Befehl

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
			if (!sender.hasPermission("casino.admin")) {
				sender.sendMessage(Tiles.text("§cDazu hast du keine Rechte."));
				return true;
			}
			loadSettings();
			sender.sendMessage(Tiles.text("§6[Casino] §7Konfiguration neu geladen."));
			return true;
		}
		if (!(sender instanceof Player p)) {
			sender.sendMessage("Nur für Spieler.");
			return true;
		}
		if (args.length > 0 && args[0].equalsIgnoreCase("pack")) {
			if (packInfo == null) p.sendMessage(Tiles.text("§6[Casino] §7Auf diesem Server ist kein Casino-Pack eingestellt."));
			else sendPack(p);
			return true;
		}
		openCasino(p);
		return true;
	}

	public void openCasino(Player p) {
		Session s = sessions.get(p.getUniqueId());
		if (s == null) {
			s = new Session(this, p);
			sessions.put(p.getUniqueId(), s);
		}
		s.open(new LobbyScreen(s));
	}

	// ------------------------------------------------------------------ Inventar-Events

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onClick(InventoryClickEvent e) {
		if (!(e.getView().getTopInventory().getHolder(false) instanceof Session s)) return;
		e.setCancelled(true);
		int raw = e.getRawSlot();
		if (raw < 0 || raw >= 54) return;
		ClickType c = e.getClick();
		if (c == ClickType.DOUBLE_CLICK) return;
		boolean right = c.isRightClick();
		if (!right && !c.isLeftClick()) return;
		try {
			s.click(raw, right);
		} catch (Exception ex) {
			getLogger().warning("Klick-Fehler: " + ex);
			ex.printStackTrace();
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onDrag(InventoryDragEvent e) {
		if (e.getView().getTopInventory().getHolder(false) instanceof Session) e.setCancelled(true);
	}

	@EventHandler
	public void onClose(InventoryCloseEvent e) {
		if (!(e.getInventory().getHolder(false) instanceof Session s)) return;
		if (s.isSwitching()) return;
		s.finishAll();
		sessions.remove(s.player().getUniqueId());
	}
}

package net.playavalon.avnrep.data.reputation;

import net.playavalon.avngui.GUI.Buttons.Button;
import net.playavalon.avngui.GUI.GUIInventory;
import net.playavalon.avngui.GUI.Window;
import net.playavalon.avngui.GUI.WindowGroup;
import net.playavalon.avngui.GUI.WindowGroupManager;
import net.playavalon.avnitems.AvalonItems;
import net.playavalon.avnitems.database.AvalonItem;
import net.playavalon.avnitems.utility.ItemUtils;
import net.playavalon.avnrep.Utils;
import net.playavalon.avnrep.data.player.AvalonPlayer;
import net.playavalon.avnrep.data.player.Reputation;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.Timestamp;
import java.util.*;

import static net.playavalon.avnrep.AvNRep.debugPrefix;
import static net.playavalon.avnrep.AvNRep.plugin;

public class Faction {

    private String namespace;
    private String displayName;
    private ItemStack displayIcon;

    private double minRep;
    private double maxRep;
    private int maxLevel;
    private double curve;

    private boolean currencyEnabled;
    private ItemStack currency;
    private String currencyName;
    private int currencyRate;

    private HashMap<String, RepSource> repSources;
    private ArrayList<DynamicRepSource> dynamicSources;
    private HashMap<Integer, List<String>> levelCommands;

    private BukkitRunnable dynamicSourceCycle;
    private long currentDate;

    public Faction(ConfigurationSection data) {
        if (data == null) return;
        namespace = data.getName().toLowerCase();
        displayName = data.getString("DisplayName", namespace);
        minRep = data.getDouble("FirstLevelCost", 25);
        maxRep = data.getDouble("LastLevelCost", 5000);
        maxLevel = data.getInt("MaxLevel", 20);
        curve = data.getDouble("RepCurve", -0.5);

        Material mat;
        if (plugin.avni != null) {

            AvalonItem aItem = plugin.avni.itemManager.getItem(data.getString("IconItem"));
            if (aItem == null) {
                mat = Material.matchMaterial(data.getString("IconItem", "BLUE_STAINED_GLASS_PANE"));
                if (mat == null) mat = Material.BLUE_STAINED_GLASS_PANE;
                displayIcon = new ItemStack(mat);
            } else {
                displayIcon = new ItemStack(aItem.item.getType());
                ItemMeta meta = displayIcon.getItemMeta();
                meta.setCustomModelData(aItem.getCustomModelData());

                displayIcon.setItemMeta(meta);
            }

        } else {

            mat = Material.matchMaterial(data.getString("IconItem", "BLUE_STAINED_GLASS_PANE"));
            if (mat == null) mat = Material.BLUE_STAINED_GLASS_PANE;
            displayIcon = new ItemStack(mat);

        }

        // Faction currency handling
        currencyEnabled = data.getBoolean("Currency.Enabled", false);
        if (currencyEnabled) {
            String itemName = data.getString("Currency.Item", "EMERALD");
            assert itemName != null;
            Material currencyMat = Material.matchMaterial(itemName);

            if (currencyMat != null) {

                currency = new ItemStack(currencyMat);
                currencyName = currencyMat.toString();

            } else {

                // If AvNi is present...
                if (plugin.avni != null) {

                    AvalonItem aItem = plugin.avni.itemManager.getItem(itemName);
                    if (aItem != null) {
                        currency = aItem.item;
                        currencyName = aItem.fullName;
                    }
                    else {
                        currency = new ItemStack(Material.EMERALD);
                        currencyName = Material.EMERALD.toString();
                    }

                } else {
                    currency = new ItemStack(Material.EMERALD);
                    currencyName = Material.EMERALD.toString();
                }

            }

            currencyRate = Math.max(data.getInt("Currency.CurrencyRate", 25), 1);

        }


        repSources = new HashMap<>();
        ConfigurationSection repSourceConfigs = data.getConfigurationSection("Sources");
        if (repSourceConfigs != null) {
            for (String key : repSourceConfigs.getKeys(false)) {
                ConfigurationSection repSourceConfig = repSourceConfigs.getConfigurationSection(key);
                if (repSourceConfig == null) continue;
                repSources.put(repSourceConfig.getName(), new RepSource(repSourceConfig));
            }
        }

        dynamicSources = new ArrayList<>();
        ConfigurationSection dynRepConfig = data.getConfigurationSection("DynamicSources");
        if (dynRepConfig != null) {
            for (String key : dynRepConfig.getKeys(false)) {
                ConfigurationSection dynSourceConfig = dynRepConfig.getConfigurationSection(key);
                if (dynSourceConfig == null) continue;
                dynamicSources.add(new DynamicRepSource(dynSourceConfig));
            }
        }

        currentDate = (System.currentTimeMillis() / 60000) / 1440;
        dynamicSourceCycle = new BukkitRunnable() {
            @Override
            public void run() {
                long newDate = (System.currentTimeMillis() / 60000) / 1440;
                if (currentDate == newDate) return;
                currentDate = newDate;

                System.out.println(debugPrefix + "Good morning! Updating dynamic reputation sources for factions...");

                randomizeDynamicSources();
            }
        };
        dynamicSourceCycle.runTaskTimerAsynchronously(plugin, 0, 1200);

        randomizeDynamicSources();


        levelCommands = new HashMap<>();
        ConfigurationSection levels = data.getConfigurationSection("Commands");
        if (levels != null) {
            List<String> commands;
            for (String path : levels.getKeys(false)) {
                commands = levels.getStringList(path);
                int level = Integer.parseInt(path);
                levelCommands.put(level, commands);
            }
        }


        initGui();


        System.out.println(this);

    }

    private void initGui() {
        Window gui = new Window("factioninfo_" + namespace, 27, displayName + " Reputation");
        Button button;

        // Top-Row blank slot setup
        button = new Button("blank", Material.GRAY_STAINED_GLASS_PANE, "");
        for (int i = 0; i < 9; i++) {
            if (i != 3 && i != 5) gui.addButton(i, button);
        }

        // Back button
        button = new Button(namespace + "_back", Material.RED_STAINED_GLASS_PANE, "&cBack");
        button.addAction("back", event -> {
            Player player = (Player)event.getWhoClicked();
            plugin.avnAPI.openGUI(player, "factionlist");
        });
        gui.addButton(3, button);

        // General info slot setup
        button = new Button(namespace + "_info", Material.MAP, displayName + " Info");
        gui.addButton(5, button);


        // Reputation source display setup
        gui.addOpenAction("loadsources", event -> {
            Player player = (Player)event.getPlayer();

            GUIInventory playerGui = gui.getPlayersGUI(player);
            if (playerGui == null) return;

            int i = 9;
            for (RepSource source : repSources.values()) {
                Button sourceButton = gui.getButtons().get(i);
                if (sourceButton == null) sourceButton = new Button(namespace + "_" + source.getTrigger(), source.getDisplayIcon());
                sourceButton.setDisplayName(source.getDisplayName());
                sourceButton.clearLore();
                sourceButton.addLore(Utils.colorize("&aReputation: " + (int)source.getValue()));
                sourceButton.addLore(Utils.colorize("&8------------------"));
                sourceButton.addLore(source.getDescription());

                playerGui.setButton(i, sourceButton);
                i++;
            }

            gui.updateButtons(player);
        });


        // Info button player data
        gui.addOpenAction("updateinfo", event -> {

            Player player = (Player)event.getPlayer();
            AvalonPlayer aPlayer = plugin.getAvalonPlayer(player);
            Reputation rep = aPlayer.getReputation(namespace);

            Button infoButton = gui.editPlayersButton(player, 5);
            infoButton.clearLore();
            infoButton.addLore(Utils.colorize("&a&lLevel: &b" + rep.getRepLevel()));
            infoButton.addLore(Utils.colorize("&aReputation EXP: &b" + (int)rep.getRepValue()));
            infoButton.addLore(Utils.colorize("&aTotal EXP Needed: &b" + (int)Utils.calcLevelCost(this, rep.getRepLevel()+1)));

            infoButton.addLore(Utils.colorize("&8------------------"));

            infoButton.addLore(Utils.colorize("&aMax Level: &b" + maxLevel));
            if (currencyEnabled) {
                infoButton.addLore(Utils.colorize("&aCurrency: &b" + currencyName));
                infoButton.addLore(Utils.colorize("&aEXP Per Currency: &b" + currencyRate));
            }

            gui.updateButtons(player);

        });

    }


    public void randomizeDynamicSources() {
        System.out.println(debugPrefix + "Updating dynamic rep sources for faction '" + namespace + "': ");
        for (DynamicRepSource dynamicSource : dynamicSources) {
            // Remove the old reputation source
            if (dynamicSource.getCurrentSource() != null)
                repSources.remove(dynamicSource.getCurrentSource().getTrigger());

            // Update and retrieve a new reputation source
            RepSource source = dynamicSource.next();

            System.out.println(debugPrefix + "- " + dynamicSource.getNamespace() + ": " + source.getTrigger());

            // Register the new source with this faction
            repSources.put(source.getTrigger(), source);
        }
    }


    public String getName() {
        return namespace;
    }
    public String getDisplayName() {
        return Utils.colorize(displayName);
    }
    public ItemStack getDisplayIcon() {
        return displayIcon;
    }
    public double getMinRep() {
        return minRep;
    }
    public double getMaxRep() {
        return maxRep;
    }
    public int getMaxLevel() {
        return maxLevel;
    }
    public double getCurve() {
        return curve;
    }
    public boolean hasCurrency() {
        return currencyEnabled;
    }
    public ItemStack getCurrency() {
        return currency;
    }
    public String getCurrencyName() {
        return currencyName;
    }
    public int getCurrencyRate() {
        return currencyRate;
    }
    public HashMap<String, RepSource> getRepSources() {
        return repSources;
    }
    public double getSourceValue(String source) {
        if (!hasSource(source)) return 0; // We return 0 since it's possible for sources to have negative values.

        RepSource repSource = repSources.get(source.toUpperCase());

        if (repSource == null) return 0;

        return repSource.getValue();
    }

    public boolean hasSource(String source) {
        return repSources.containsKey(source.toUpperCase());
    }

    public HashMap<Integer, List<String>> getLevelCommands() {
        return levelCommands;
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(namespace.toUpperCase());
        sb.append("\n-- Max Level: " + maxLevel);
        sb.append("\n-- Rep Curve: " + curve);
        sb.append("\n-- Min Rep: " + minRep);
        sb.append("\n-- Max Rep: " + maxRep);
        if (currencyEnabled) {
            sb.append("\n-- Currency: " + currencyName);
            sb.append("\n-- Currency Rate: " + currencyRate);
        }
        sb.append("\n-- Rep Sources: ");
        for (Map.Entry<String, RepSource> pair : repSources.entrySet()) {
            sb.append("\n---- " + pair.getKey() + ": " + pair.getValue().getValue());
        }

        return sb.toString();
    }
}

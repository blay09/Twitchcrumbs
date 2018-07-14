package net.blay09.mods.twitchcrumbs;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

@Mod(modid = "twitchcrumbs", name = "Twitchcrumbs", dependencies = "required-after:headcrumbs")
public class Twitchcrumbs {

    public static final Logger logger = LogManager.getLogger();

    @Mod.Instance
    public static Twitchcrumbs instance;

    private final List<String> whitelists = new ArrayList<>();
    private boolean autoReload;
    private int cacheTime;
    private int reloadInterval;
    private boolean firstTick = true;

    private Thread thread;

    private String[] originalNames;
    private int tickTimer;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        String[] sources = config.getStringList("sources", "general", new String[0], "One whitelist source link per line. Example: http://whitelist.twitchapps.com/list.php?id=12345");
        Collections.addAll(whitelists, sources);
        cacheTime = config.getInt("cacheTime", "general", 60 * 60 * 24, 0, Integer.MAX_VALUE, "How long should the cache be used until updates are pulled? (if autoReload is false) (in seconds)");
        autoReload = config.getBoolean("autoReload", "general", false, "Should the Twitchcrumbs automatically be reloaded in a specific interval? This will mean reading the remote file again and will reset Headcrumb's already-spawned list. The Creative Tab and NEI won't be updated until the game restarts, though.");
        reloadInterval = config.getInt("reloadInterval", "general", 60, 10, 60 * 12, "If autoReload is enabled, at what interval in minutes should the reload happen? (approximately, based on TPS)") * 60 * 20;
        config.save();

        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandBase() {
            @Override
            public String getName() {
                return "twitchcrumbs";
            }

            @Override
            public String getUsage(ICommandSender sender) {
                return "/twitchcrumbs reload";
            }

            @Override
            public void execute(MinecraftServer server, final ICommandSender sender, String[] args) throws CommandException {
                if (args.length != 1) {
                    throw new WrongUsageException(getUsage(sender));
                }
                if (args[0].equals("reload")) {
                    sender.sendMessage(new TextComponentString("Reloading Twitchcrumbs..."));
                    reloadTwitchCrumbs(registered -> sender.sendMessage(new TextComponentString("Reloaded Twitchcrumbs - registered " + registered + " users.")));
                    return;
                }
                throw new WrongUsageException(getUsage(sender));
            }

            @Override
            public int getRequiredPermissionLevel() {
                return 2;
            }

            @Override
            public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
                return args.length == 1 ? Collections.singletonList("reload") : super.getTabCompletions(server, sender, args, targetPos);
            }
        });
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (firstTick) { // We do this here instead of in init to forcefully skip Headcrumb's initialization code. Creating all the head stacks, adding all the dungeon loot and all that other stuff is too much for huge lists like SF2.5.
            firstTick = false;
            reloadTwitchCrumbs(null);
        }

        if (autoReload) {
            tickTimer++;
            if (tickTimer > reloadInterval) {
                tickTimer = 0;
                reloadTwitchCrumbs(null);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void reloadTwitchCrumbs(Consumer<Integer> reloadDone) {
        if (thread != null) {
            return;
        }

        thread = new Thread(() -> {
            // Load the whitelist from all sources
            List<String> list = new ArrayList<>();
            for (String source : whitelists) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(CachedAPI.loadCachedAPI(source, source.replace(":", "_").replace("/", "_").replace("?", "_"), 1000 * cacheTime)))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        list.add(line);
                    }
                } catch (IOException e) {
                    logger.error("Failed to load whitelist from source {}: {}", source, e);
                }
            }

            Runnable update = () -> {
                // Grab the count here as the list will be modified in updateData
                int count = list.size();

                updateData(list);

                if (reloadDone != null) {
                    reloadDone.accept(count);
                }

                thread = null;
            };

            if (FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT) {
                FMLClientHandler.instance().getClient().addScheduledTask(update);
            } else {
                FMLCommonHandler.instance().getMinecraftServerInstance().addScheduledTask(update);
            }
        });

        thread.start();
    }

    private void updateData(List<String> list) {
        logger.info("Registering {} Twitchcrumbs users...", list.size());
        // We don't use Headcrumb's IMC API because it's inefficient and requires mod-sent option to be enabled
        // Append our whitelist names to the "others" list in Headcrumbs instead
        try {
            Class headcrumbs = Class.forName("ganymedes01.headcrumbs.Headcrumbs");
            Field othersField = headcrumbs.getField("others");
            String[] others = (String[]) othersField.get(null);
            if (originalNames == null) {
                originalNames = new String[others.length];
                System.arraycopy(others, 0, originalNames, 0, others.length);
            } else {
                others = originalNames;
            }

            Collections.addAll(list, others);
            othersField.set(null, list.toArray(new String[0]));

            // Clear EntityHuman's name cache to allow immediate spawning of new names in case of a reload
            Class entityHuman = Class.forName("ganymedes01.headcrumbs.entity.EntityHuman");
            Field namesField = entityHuman.getDeclaredField("names");
            namesField.setAccessible(true);
            List<String> names = (List<String>) namesField.get(null);
            names.clear();
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException | ClassCastException e) {
            logger.error("Oops! Twitchcrumbs is not compatible with this version of Headcrumbs!", e);
        }
    }

}

package de.roni.alearena;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import org.bukkit.World;

import java.util.UUID;

/**
 * Anbindung an Simple Voice Chat: der Stream-Ton kommt positionsbasiert aus der Leinwand.
 * Wird nur per Reflection geladen, wenn das Voicechat-Plugin installiert ist.
 */
public final class VoiceBridge implements VoicechatPlugin, AudioOut {

    private static final String CATEGORY = "alearena";
    private final AleArenaPlugin plugin;
    private volatile VoicechatServerApi api;

    private VoiceBridge(AleArenaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Aufruf per Reflection aus AleArenaPlugin. */
    public static AudioOut register(AleArenaPlugin plugin) {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            return null;
        }
        VoiceBridge bridge = new VoiceBridge(plugin);
        service.registerPlugin(bridge);
        return bridge;
    }

    @Override
    public String getPluginId() {
        return "alearena";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi s) {
            setApi(s);
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, e -> setApi(e.getVoicechat()));
    }

    private void setApi(VoicechatServerApi s) {
        if (this.api == s) {
            return;
        }
        this.api = s;
        try {
            VolumeCategory cat = s.volumeCategoryBuilder()
                    .setId(CATEGORY)
                    .setName("AleArena")
                    .setDescription("Ton der AleArena-Leinwände")
                    .build();
            s.registerVolumeCategory(cat);
        } catch (Throwable t) {
            plugin.getLogger().fine("Lautstärke-Kategorie nicht registriert: " + t);
        }
        plugin.getLogger().info("Simple Voice Chat verbunden – Ton kommt ingame aus der Leinwand.");
    }

    @Override
    public boolean ready() {
        return api != null;
    }

    @Override
    public Runnable start(World world, double x, double y, double z, float distance, PcmBuffer pcm) {
        VoicechatServerApi s = api;
        if (s == null) {
            return null;
        }
        LocationalAudioChannel channel = s.createLocationalAudioChannel(UUID.randomUUID(),
                s.fromServerLevel(world), s.createPosition(x, y, z));
        if (channel == null) {
            return null;
        }
        channel.setDistance(distance);
        try {
            channel.setCategory(CATEGORY);
        } catch (Throwable ignored) {
        }
        AudioPlayer player = s.createAudioPlayer(channel, s.createEncoder(), pcm::next);
        player.startPlaying();
        return player::stopPlaying;
    }
}

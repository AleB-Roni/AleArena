package de.roni.alearena;

import org.bukkit.World;

/** Ton-Ausgabe ingame (umgesetzt über Simple Voice Chat, falls installiert). */
public interface AudioOut {

    /**
     * Startet positionsbasierten Ton an der Leinwand.
     * @return Runnable zum Stoppen, oder null wenn gerade nicht möglich.
     */
    Runnable start(World world, double x, double y, double z, float distance, PcmBuffer pcm);

    boolean ready();
}

package net.webtide.cluster.configuration;

import net.webtide.cluster.JvmSettings;
import net.webtide.cluster.NodeArrayTopology;

public interface NodeArrayConfiguration
{
    String id();
    JvmSettings jvmSettings();
    NodeArrayTopology topology();
}

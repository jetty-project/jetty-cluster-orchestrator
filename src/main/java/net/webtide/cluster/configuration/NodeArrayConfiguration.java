package net.webtide.cluster.configuration;

public interface NodeArrayConfiguration
{
    String id();
    JvmSettings jvmSettings();
    NodeArrayTopology topology();
}

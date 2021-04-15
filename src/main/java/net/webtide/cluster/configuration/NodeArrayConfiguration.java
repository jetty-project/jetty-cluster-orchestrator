package net.webtide.cluster.configuration;

public interface NodeArrayConfiguration
{
    String id();
    Jvm jvm();
    NodeArrayTopology topology();
}

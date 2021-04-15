package net.webtide.cluster.configuration;

import java.util.Collection;

public interface ClusterConfiguration
{
    Jvm jvm();
    Collection<NodeArrayConfiguration> nodeArrays();
    HostLauncher hostLauncher();
}

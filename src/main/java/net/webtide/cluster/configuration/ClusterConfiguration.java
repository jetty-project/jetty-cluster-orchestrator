package net.webtide.cluster.configuration;

import java.util.Collection;

public interface ClusterConfiguration
{
    JvmSettings jvmSettings();
    Collection<NodeArrayConfiguration> nodeArrays();
    NodeArrayConfiguration nodeArray(String id);
    RemotingConfiguration remotingConfiguration();
}

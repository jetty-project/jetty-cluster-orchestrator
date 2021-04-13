package net.webtide.cluster.configuration;

import java.util.Collection;

import net.webtide.cluster.JvmSettings;

public interface ClusterConfiguration
{
    JvmSettings jvmSettings();
    Collection<NodeArrayConfiguration> nodeArrays();
    NodeArrayConfiguration nodeArray(String id);
    RemotingConfiguration remotingConfiguration();
}

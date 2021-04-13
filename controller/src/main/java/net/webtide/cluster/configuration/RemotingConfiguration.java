package net.webtide.cluster.configuration;

import net.webtide.cluster.RemoteHostLauncher;

public interface RemotingConfiguration
{
    RemoteHostLauncher buildRemoteNodeLauncher();
}

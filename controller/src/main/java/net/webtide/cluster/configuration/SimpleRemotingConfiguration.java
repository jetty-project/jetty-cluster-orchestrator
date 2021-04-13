package net.webtide.cluster.configuration;

import net.webtide.cluster.RemoteNodeLauncher;
import net.webtide.cluster.SshRemoteNodeLauncher;

public class SimpleRemotingConfiguration implements RemotingConfiguration
{
    private RemoteNodeLauncher remoteNodeLauncher = new SshRemoteNodeLauncher();

    @Override
    public RemoteNodeLauncher remoteNodeLauncher()
    {
        return remoteNodeLauncher;
    }
}

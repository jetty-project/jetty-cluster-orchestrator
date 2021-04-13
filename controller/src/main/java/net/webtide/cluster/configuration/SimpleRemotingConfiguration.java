package net.webtide.cluster.configuration;

import net.webtide.cluster.JvmSettings;
import net.webtide.cluster.RemoteNodeLauncher;
import net.webtide.cluster.SshRemoteNodeLauncher;

public class SimpleRemotingConfiguration implements RemotingConfiguration
{
    private JvmSettings jvmSettings;
    private RemoteNodeLauncher remoteNodeLauncher = new SshRemoteNodeLauncher();

    @Override
    public JvmSettings jvmSettings()
    {
        return jvmSettings;
    }

    public SimpleRemotingConfiguration jvmSettings(JvmSettings jvmSettings)
    {
        this.jvmSettings = jvmSettings;
        return this;
    }

    @Override
    public RemoteNodeLauncher remoteNodeLauncher()
    {
        return remoteNodeLauncher;
    }
}

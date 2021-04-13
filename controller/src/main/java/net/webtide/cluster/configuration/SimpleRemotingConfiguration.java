package net.webtide.cluster.configuration;

import net.webtide.cluster.JvmSettings;
import net.webtide.cluster.RemoteNodeLauncher;
import net.webtide.cluster.SshRemoteNodeLauncher;

public class SimpleRemotingConfiguration implements RemotingConfiguration
{
    private JvmSettings jvmSettings;

    public SimpleRemotingConfiguration jvmSettings(JvmSettings jvmSettings)
    {
        this.jvmSettings = jvmSettings;
        return this;
    }

    public JvmSettings jvmSettings()
    {
        return jvmSettings;
    }

    @Override
    public RemoteNodeLauncher buildRemoteNodeLauncher()
    {
        return new SshRemoteNodeLauncher(jvmSettings);
    }
}

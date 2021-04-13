package net.webtide.cluster.configuration;

import net.webtide.cluster.common.JvmSettings;
import net.webtide.cluster.RemoteHostLauncher;
import net.webtide.cluster.SshRemoteHostLauncher;

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
    public RemoteHostLauncher buildRemoteNodeLauncher()
    {
        return new SshRemoteHostLauncher(jvmSettings);
    }
}

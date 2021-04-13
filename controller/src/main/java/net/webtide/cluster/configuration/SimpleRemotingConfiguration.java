package net.webtide.cluster.configuration;

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

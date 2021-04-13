package net.webtide.cluster.configuration;

public class SimpleRemotingConfiguration implements RemotingConfiguration
{
    private JvmSettings jvmSettings = JvmSettings.DEFAULT;

    @Override
    public RemoteHostLauncher buildRemoteNodeLauncher()
    {
        return new SshRemoteHostLauncher(jvmSettings);
    }

    public JvmSettings jvmSettings()
    {
        return jvmSettings;
    }

    public SimpleRemotingConfiguration jvmSettings(JvmSettings jvmSettings)
    {
        this.jvmSettings = jvmSettings;
        return this;
    }
}

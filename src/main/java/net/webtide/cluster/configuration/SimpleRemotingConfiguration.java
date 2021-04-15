package net.webtide.cluster.configuration;

public class SimpleRemotingConfiguration implements RemotingConfiguration
{
    private Jvm jvm = Jvm.DEFAULT;

    @Override
    public RemoteHostLauncher buildRemoteNodeLauncher()
    {
        return new SshRemoteHostLauncher(jvm);
    }

    public Jvm jvm()
    {
        return jvm;
    }

    public SimpleRemotingConfiguration jvm(Jvm jvm)
    {
        this.jvm = jvm;
        return this;
    }
}

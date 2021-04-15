package net.webtide.cluster.configuration;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class SimpleClusterConfiguration implements ClusterConfiguration
{
    private Jvm jvm = Jvm.DEFAULT;
    private final Map<String, NodeArrayConfiguration> nodeArrayConfigurations = new HashMap<>();
    private RemotingConfiguration remotingConfiguration = new SimpleRemotingConfiguration();

    public SimpleClusterConfiguration jvm(Jvm jvm)
    {
        this.jvm = jvm;
        if (remotingConfiguration instanceof SimpleRemotingConfiguration)
        {
            SimpleRemotingConfiguration simpleRemotingConfiguration = (SimpleRemotingConfiguration)remotingConfiguration;
            if (simpleRemotingConfiguration.jvm() == null)
                simpleRemotingConfiguration.jvm(jvm);
        }
        return this;
    }

    @Override
    public Jvm jvm()
    {
        return jvm;
    }

    @Override
    public Collection<NodeArrayConfiguration> nodeArrays()
    {
        return nodeArrayConfigurations.values();
    }

    @Override
    public NodeArrayConfiguration nodeArray(String id)
    {
        return nodeArrayConfigurations.get(id);
    }

    @Override
    public RemotingConfiguration remotingConfiguration()
    {
        return remotingConfiguration;
    }

    public SimpleClusterConfiguration nodeArray(NodeArrayConfiguration nodeArrayConfiguration)
    {
        String id = nodeArrayConfiguration.id();
        if (nodeArrayConfigurations.containsKey(id))
            throw new IllegalArgumentException("Duplicate node array ID: " + id);
        nodeArrayConfigurations.put(id, nodeArrayConfiguration);
        return this;
    }

    public SimpleClusterConfiguration remotingConfiguration(RemotingConfiguration remotingConfiguration)
    {
        this.remotingConfiguration = remotingConfiguration;
        return this;
    }
}

package net.webtide.cluster.configuration;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import net.webtide.cluster.common.JvmSettings;

public class SimpleClusterConfiguration implements ClusterConfiguration
{
    private JvmSettings jvmSettings;
    private final Map<String, NodeArrayConfiguration> nodeArrayConfigurations = new HashMap<>();
    private RemotingConfiguration remotingConfiguration = new SimpleRemotingConfiguration();

    public SimpleClusterConfiguration jvmSettings(JvmSettings jvmSettings)
    {
        this.jvmSettings = jvmSettings;
        if (remotingConfiguration instanceof SimpleRemotingConfiguration)
        {
            SimpleRemotingConfiguration simpleRemotingConfiguration = (SimpleRemotingConfiguration)remotingConfiguration;
            if (simpleRemotingConfiguration.jvmSettings() == null)
                simpleRemotingConfiguration.jvmSettings(jvmSettings);
        }
        return this;
    }

    @Override
    public JvmSettings jvmSettings()
    {
        return jvmSettings;
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

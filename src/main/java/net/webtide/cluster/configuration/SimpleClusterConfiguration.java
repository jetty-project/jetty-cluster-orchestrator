package net.webtide.cluster.configuration;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class SimpleClusterConfiguration implements ClusterConfiguration, JvmDependent
{
    private static final Jvm DEFAULT_JVM = new Jvm(() -> "java");

    private Jvm jvm = DEFAULT_JVM;
    private final Map<String, NodeArrayConfiguration> nodeArrayConfigurations = new HashMap<>();
    private RemotingConfiguration remotingConfiguration = new SimpleRemotingConfiguration();

    public SimpleClusterConfiguration jvm(Jvm jvm)
    {
        this.jvm = jvm;
        ensureJvmSet(remotingConfiguration);
        nodeArrayConfigurations.values().forEach(this::ensureJvmSet);
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
        ensureJvmSet(nodeArrayConfiguration);
        return this;
    }

    public SimpleClusterConfiguration remotingConfiguration(RemotingConfiguration remotingConfiguration)
    {
        this.remotingConfiguration = remotingConfiguration;
        ensureJvmSet(remotingConfiguration);
        return this;
    }

    private void ensureJvmSet(Object obj)
    {
        if (obj instanceof JvmDependent)
        {
            JvmDependent jvmDependent = (JvmDependent)obj;
            if (jvmDependent.jvm() == null)
                jvmDependent.jvm(jvm);
        }
    }
}

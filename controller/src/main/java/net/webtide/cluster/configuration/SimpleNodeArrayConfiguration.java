package net.webtide.cluster.configuration;

public class SimpleNodeArrayConfiguration implements NodeArrayConfiguration
{
    private final String id;
    private JvmSettings jvmSettings;
    private NodeArrayTopology nodeArrayTopology;

    public SimpleNodeArrayConfiguration(String id)
    {
        this.id = id;
    }

    @Override
    public String id()
    {
        return id;
    }

    @Override
    public JvmSettings jvmSettings()
    {
        return jvmSettings;
    }

    @Override
    public NodeArrayTopology topology()
    {
        return nodeArrayTopology;
    }

    public SimpleNodeArrayConfiguration jvmSettings(JvmSettings jvmSettings)
    {
        this.jvmSettings = jvmSettings;
        return this;
    }

    public SimpleNodeArrayConfiguration topology(NodeArrayTopology nodeArrayTopology)
    {
        this.nodeArrayTopology = nodeArrayTopology;
        return this;
    }
}

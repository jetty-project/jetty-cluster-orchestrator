package net.webtide.cluster.configuration;

public class SimpleNodeArrayConfiguration implements NodeArrayConfiguration
{
    private final String id;
    private Jvm jvm = Jvm.DEFAULT;
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
    public Jvm jvm()
    {
        return jvm;
    }

    @Override
    public NodeArrayTopology topology()
    {
        return nodeArrayTopology;
    }

    public SimpleNodeArrayConfiguration jvm(Jvm jvm)
    {
        this.jvm = jvm;
        return this;
    }

    public SimpleNodeArrayConfiguration topology(NodeArrayTopology nodeArrayTopology)
    {
        this.nodeArrayTopology = nodeArrayTopology;
        return this;
    }
}

package net.webtide.cluster.configuration;

import java.util.Arrays;
import java.util.Collection;

import net.webtide.cluster.configuration.Node;

public class NodeArrayTopology
{
    private final Collection<Node> nodes;

    public NodeArrayTopology(Node... nodes)
    {
        this.nodes = Arrays.asList(nodes);
    }

    public Collection<Node> nodes()
    {
        return nodes;
    }
}

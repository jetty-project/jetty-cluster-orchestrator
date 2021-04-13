package net.webtide.cluster;

import java.util.Arrays;
import java.util.Collection;

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

package net.webtide.cluster.configuration;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class NodeArrayTopology
{
    private final Map<String, Node> nodes = new HashMap<>();

    public NodeArrayTopology(Node... nodes)
    {
        for (Node node : nodes)
        {
            if (this.nodes.put(node.getId(), node) != null)
                throw new IllegalArgumentException("Duplicate node ID: " + node.getId());
        }
    }

    public Collection<Node> nodes()
    {
        return nodes.values();
    }
}

package net.webtide.cluster;

public class Node
{
    private final String id;
    private final String hostname;

    public Node(String id, String hostname)
    {
        this.id = id;
        this.hostname = hostname;
    }

    public String getId()
    {
        return id;
    }

    public String getHostname()
    {
        return hostname;
    }
}

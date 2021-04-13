package net.webtide.cluster;

public interface RemoteNodeLauncher extends AutoCloseable
{
    void launch(Node node, String rendezVous);
}

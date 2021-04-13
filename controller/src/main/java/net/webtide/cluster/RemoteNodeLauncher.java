package net.webtide.cluster;

public interface RemoteNodeLauncher extends AutoCloseable
{
    void launch(String nodeId, String rendezVous);
}

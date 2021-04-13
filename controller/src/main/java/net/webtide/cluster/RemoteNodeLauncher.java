package net.webtide.cluster;

public interface RemoteNodeLauncher
{
    void launch(JvmSettings jvmSettings, Node node, String rendezVous);
}

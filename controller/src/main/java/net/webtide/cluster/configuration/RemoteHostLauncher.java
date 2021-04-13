package net.webtide.cluster.configuration;

public interface RemoteHostLauncher extends AutoCloseable
{
    void launch(String remoteHostId, String rendezVous) throws Exception;
}

package net.webtide.cluster.configuration;

public interface RemoteHostLauncher extends AutoCloseable
{
    void launch(String hostname, String rendezVous) throws Exception;
}

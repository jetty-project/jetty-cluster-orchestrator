package net.webtide.cluster.configuration;

public interface HostLauncher extends AutoCloseable
{
    void launch(String hostname, String hostId, String rendezVous) throws Exception;
}

package net.webtide.cluster;

public interface RemoteHostLauncher extends AutoCloseable
{
    void launch(String hostname, String rendezVous) throws Exception;
}

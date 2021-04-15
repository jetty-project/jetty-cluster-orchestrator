package net.webtide.cluster.rpc.command;

import net.webtide.cluster.ClusterTools;

public class ShutdownCommand implements Command
{
    @Override
    public Object execute(ClusterTools clusterTools) throws Exception
    {
        throw new ShutdownException();
    }

    public static class ShutdownException extends Exception
    {
    }
}

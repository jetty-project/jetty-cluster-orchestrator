package net.webtide.cluster.rpc.command;

import java.io.Serializable;

import net.webtide.cluster.ClusterTools;

public interface Command extends Serializable
{
    Object execute(ClusterTools clusterTools) throws Exception;
}

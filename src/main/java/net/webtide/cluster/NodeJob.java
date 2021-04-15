package net.webtide.cluster;

import java.io.Serializable;

@FunctionalInterface
public interface NodeJob extends Serializable
{
    void execute(ClusterTools env) throws Exception;
}

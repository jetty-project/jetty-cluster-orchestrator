package net.webtide.cluster;

import java.io.Serializable;

@FunctionalInterface
public interface NodeJob extends Serializable
{
    void execute(ClusterEnvironment env) throws Exception;
}

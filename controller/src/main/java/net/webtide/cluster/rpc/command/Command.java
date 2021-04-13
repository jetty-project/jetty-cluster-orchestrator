package net.webtide.cluster.rpc.command;

import java.io.Serializable;

public interface Command extends Serializable
{
    Object execute() throws Exception;
}

package net.webtide.cluster.common.command;

import java.io.Serializable;

public interface Command extends Serializable
{
    Object execute() throws Exception;
}

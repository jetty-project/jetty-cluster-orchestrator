package net.webtide.cluster.rpc;

import java.io.Serializable;

import net.webtide.cluster.rpc.command.Command;

public class Request implements Serializable
{
    private final long id;
    private final Command command;

    public Request(long id, Command command)
    {
        this.id = id;
        this.command = command;
    }

    public long getId()
    {
        return id;
    }

    public Command getCommand()
    {
        return command;
    }

    @Override
    public String toString()
    {
        return "Request{" +
            "id=" + id +
            ", command=" + command +
            '}';
    }
}

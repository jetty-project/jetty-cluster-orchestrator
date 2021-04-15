package net.webtide.cluster.rpc;

import java.io.Serializable;

public class Response implements Serializable
{
    private final long id;
    private final Object result;
    private final Exception exception;

    public Response(long id, Object result, Exception exception)
    {
        this.id = id;
        this.result = result;
        this.exception = exception;
    }

    public long getId()
    {
        return id;
    }

    public Object getResult()
    {
        return result;
    }

    public Exception getException()
    {
        return exception;
    }

    @Override
    public String toString()
    {
        return "Response{" +
            "id=" + id +
            ", result=" + result +
            ", exception=" + exception +
            '}';
    }
}

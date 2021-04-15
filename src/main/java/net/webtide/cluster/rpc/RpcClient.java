package net.webtide.cluster.rpc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.ExecutionException;

import net.webtide.cluster.rpc.command.Command;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.SimpleDistributedQueue;

public class RpcClient
{
    private final SimpleDistributedQueue commandQueue;
    private final SimpleDistributedQueue responseQueue;

    public RpcClient(CuratorFramework curator, String nodeId)
    {
        commandQueue = new SimpleDistributedQueue(curator, "/clients/" + nodeId + "/commandQ");
        responseQueue = new SimpleDistributedQueue(curator, "/clients/" + nodeId + "/responseQ");
    }

    public Object call(Command command) throws Exception
    {
        byte[] cmdBytes = serialize(command);
        commandQueue.offer(cmdBytes);
        byte[] respBytes = responseQueue.take();
        Object resp = deserialize(respBytes);
        if (resp instanceof Exception)
            throw new ExecutionException((Throwable)resp);
        return resp;
    }

    private static Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException
    {
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
        return ois.readObject();
    }

    private static byte[] serialize(Object obj) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(obj);
        return baos.toByteArray();
    }
}

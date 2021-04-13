package net.webtide.cluster.node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import net.webtide.cluster.common.command.Command;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.SimpleDistributedQueue;

public class RpcServer
{
    private final SimpleDistributedQueue commandQueue;
    private final SimpleDistributedQueue responseQueue;

    public RpcServer(CuratorFramework curator, String nodeId)
    {
        commandQueue = new SimpleDistributedQueue(curator, "/clients/" + nodeId + "/commandQ");
        responseQueue = new SimpleDistributedQueue(curator, "/clients/" + nodeId + "/responseQ");
    }

    public void run() throws Exception
    {
        while (true)
        {
            byte[] cmdBytes = commandQueue.take();
            Object obj = deserialize(cmdBytes);
            if (!(obj instanceof Command))
                continue;
            Command command = (Command)obj;
            Object result;
            try
            {
                result = command.execute();
            }
            catch (Exception e)
            {
                result = e;
            }
            byte[] resBytes = serialize(result);
            responseQueue.offer(resBytes);
        }
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

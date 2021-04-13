package net.webtide.cluster.node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import net.webtide.cluster.common.command.Command;
import net.webtide.cluster.common.command.ShutdownCommand;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.SimpleDistributedQueue;

public class RpcServer
{
    private final String nodeId;
    private final SimpleDistributedQueue commandQueue;
    private final SimpleDistributedQueue responseQueue;
    private volatile boolean active;

    public RpcServer(CuratorFramework curator, String nodeId)
    {
        this.nodeId = nodeId;
        commandQueue = new SimpleDistributedQueue(curator, "/clients/" + nodeId + "/commandQ");
        responseQueue = new SimpleDistributedQueue(curator, "/clients/" + nodeId + "/responseQ");
    }

    public void abort() throws Exception
    {
        if (active)
            commandQueue.offer(serialize(new AbortCommand()));
        while (active)
            Thread.sleep(5);
    }

    public void run() throws Exception
    {
        active = true;
        while (active)
        {
            byte[] cmdBytes;
            try
            {
                cmdBytes = commandQueue.take();
            }
            catch (Exception e)
            {
                active = false;
                throw new RuntimeException("Error reading command on node " + nodeId, e);
            }
            Object result;
            try
            {
                Object obj = deserialize(cmdBytes);
                if (!(obj instanceof Command))
                    continue;
                Command command = (Command)obj;
                result = command.execute();
            }
            catch (AbortCommand.AbortException e)
            {
                active = false;
                return;
            }
            catch (ShutdownCommand.ShutdownException e)
            {
                result = null;
                active = false;
            }
            catch (Exception e)
            {
                result = e;
            }

            byte[] resBytes;
            try
            {
                resBytes = serialize(result);
            }
            catch (IOException e)
            {
                resBytes = serialize(e);
            }
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

    private static class AbortCommand implements Command
    {
        private static class AbortException extends Exception
        {
        }

        @Override
        public Object execute() throws Exception
        {
            throw new AbortException();
        }
    }
}

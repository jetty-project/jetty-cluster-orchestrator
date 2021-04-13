package net.webtide.cluster.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.DistributedQueue;
import org.apache.curator.framework.recipes.queue.QueueBuilder;
import org.apache.curator.framework.recipes.queue.QueueConsumer;
import org.apache.curator.framework.recipes.queue.QueueSerializer;

public class InitHelper
{
    public static <T> DistributedQueue<T> buildCommandQueue(CuratorFramework curator, String nodeId, QueueConsumer<T> queueConsumer) throws Exception
    {
        QueueBuilder<T> builder = QueueBuilder.builder(curator, queueConsumer,
            new SimpleQueueSerializer<>(),
            "/clients/" + nodeId + "/commandQ");
        DistributedQueue<T> queue = builder.buildQueue();
        queue.start();
        return queue;
    }

    public static <T> DistributedQueue<T> buildResponseQueue(CuratorFramework curator, String nodeId, QueueConsumer<T> queueConsumer) throws Exception
    {
        QueueBuilder<T> builder = QueueBuilder.builder(curator, queueConsumer,
            new SimpleQueueSerializer<>(),
            "/clients/" + nodeId + "/responseQ");
        DistributedQueue<T> queue = builder.buildQueue();
        queue.start();
        return queue;
    }

    private static class SimpleQueueSerializer<T> implements QueueSerializer<T>
    {
        @Override
        public T deserialize(byte[] bytes)
        {
            try
            {
                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
                return (T)ois.readObject();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public byte[] serialize(Object o)
        {
            try
            {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ObjectOutputStream oos = new ObjectOutputStream(baos);
                oos.writeObject(o);
                return baos.toByteArray();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }
}

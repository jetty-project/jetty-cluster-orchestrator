package net.webtide.cluster.nodemanager;

import net.webtide.cluster.common.InitHelper;
import net.webtide.cluster.common.command.Command;
import net.webtide.cluster.common.util.IOUtil;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.BlockingQueueConsumer;
import org.apache.curator.framework.recipes.queue.DistributedQueue;
import org.apache.curator.framework.recipes.queue.QueueConsumer;
import org.apache.curator.framework.state.ConnectionState;

public class CommandProcessor implements AutoCloseable
{
    private final BlockingQueueConsumer<Command> queueConsumer = new BlockingQueueConsumer<>((client, newState) -> {});
    private final DistributedQueue<Command> commandQueue;
    private final DistributedQueue<Object> responseQueue;

    public CommandProcessor(CuratorFramework curator, String nodeId) throws Exception
    {
        commandQueue = InitHelper.buildCommandQueue(curator, nodeId, queueConsumer);
        responseQueue = InitHelper.buildResponseQueue(curator, nodeId, new QueueConsumer<>() {
            @Override
            public void consumeMessage(Object message)
            {
            }
            @Override
            public void stateChanged(CuratorFramework client, ConnectionState newState)
            {
            }
        });
    }

    @Override
    public void close() throws Exception
    {
        IOUtil.close(commandQueue);
        IOUtil.close(responseQueue);
    }

    public void run() throws Exception
    {
        while (true)
        {
            Command take = queueConsumer.take();
            try
            {
                Object response = take.execute();
                responseQueue.put(response);
            }
            catch (Exception e)
            {
                responseQueue.put(e);
            }
        }
    }
}

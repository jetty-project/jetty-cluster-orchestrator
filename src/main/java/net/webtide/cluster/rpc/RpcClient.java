package net.webtide.cluster.rpc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import net.webtide.cluster.rpc.command.Command;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.queue.SimpleDistributedQueue;

public class RpcClient implements AutoCloseable
{
    private final SimpleDistributedQueue commandQueue;
    private final SimpleDistributedQueue responseQueue;
    private final ExecutorService executorService;
    private final ConcurrentMap<Long, CompletableFuture<Object>> calls = new ConcurrentHashMap<>();
    private final AtomicLong requestIdGenerator = new AtomicLong();

    public RpcClient(CuratorFramework curator, String nodeId)
    {
        commandQueue = new SimpleDistributedQueue(curator, "/clients/" + nodeId + "/commandQ");
        responseQueue = new SimpleDistributedQueue(curator, "/clients/" + nodeId + "/responseQ");
        executorService = Executors.newSingleThreadExecutor();
        executorService.submit(() ->
        {
            while (true)
            {
                byte[] respBytes = responseQueue.take();
                Response resp = (Response)deserialize(respBytes);
                CompletableFuture<Object> future = calls.remove(resp.getId());
                if (resp.getException() != null)
                    future.completeExceptionally(new ExecutionException(resp.getException()));
                else
                    future.complete(resp.getResult());
            }
        });
    }

    public CompletableFuture<Object> callAsync(Command command) throws Exception
    {
        long requestId = requestIdGenerator.getAndIncrement();
        CompletableFuture<Object> completableFuture = new CompletableFuture<>();
        calls.put(requestId, completableFuture);
        Request request = new Request(requestId, command);
        byte[] cmdBytes = serialize(request);
        commandQueue.offer(cmdBytes);
        return completableFuture;
    }

    public Object call(Command command) throws Exception
    {
        CompletableFuture<Object> future = callAsync(command);
        return future.get();
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

    @Override
    public void close()
    {
        executorService.shutdownNow();
    }
}

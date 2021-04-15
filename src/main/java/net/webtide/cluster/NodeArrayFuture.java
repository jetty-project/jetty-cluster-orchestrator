package net.webtide.cluster;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class NodeArrayFuture
{
    private final List<CompletableFuture<Object>> futures;

    NodeArrayFuture(List<CompletableFuture<Object>> futures)
    {
        this.futures = futures;
    }

    public void get() throws ExecutionException, InterruptedException
    {
        for (CompletableFuture<Object> future : futures)
        {
            future.get();
        }
    }
}

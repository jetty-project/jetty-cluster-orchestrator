package sample;

import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.framework.recipes.locks.Locker;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.Test;

public class CuratorTest
{

    @Test
    public void test() throws Exception
    {
        try (TestingServer server = new TestingServer(true))
        {
            try (CuratorFramework curator = CuratorFrameworkFactory.newClient(server.getConnectString(), new ExponentialBackoffRetry(1000, 3)))
            {
                curator.start();
                curator.blockUntilConnected();
                System.out.println("client connected");

                InterProcessMutex mutex = new InterProcessMutex(curator, "/lock");

                try ( Locker locker = new Locker(mutex, 3000, TimeUnit.MILLISECONDS) )
                {
                    System.out.println("locked");
                }
                System.out.println("unlocked");
            }
        }
    }

}

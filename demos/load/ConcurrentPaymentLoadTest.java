import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone concurrency lab for the Experiment 2 concept. */
public class ConcurrentPaymentLoadTest {
    public static void main(String[] args) throws Exception {
        int n=args.length>0?Integer.parseInt(args[0]):20;
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(n,10));
        CountDownLatch start=new CountDownLatch(1); AtomicInteger done=new AtomicInteger();
        long t=System.currentTimeMillis();
        for(int i=1;i<=n;i++){final int id=i;pool.submit(()->{try{start.await();Thread.sleep(300+(id%4)*100);System.out.println("PAY-LOAD-"+id+" served by "+Thread.currentThread().getName());done.incrementAndGet();}catch(Exception e){Thread.currentThread().interrupt();}});}
        start.countDown();pool.shutdown();pool.awaitTermination(30,TimeUnit.SECONDS);
        System.out.println("Completed="+done.get()+" / "+n+" in "+(System.currentTimeMillis()-t)+" ms");
    }
}

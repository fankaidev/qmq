package qunar.tc.qmq.concurrent;

import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import qunar.tc.qmq.concurrent.ActorSystem.Actor;
import qunar.tc.qmq.concurrent.ActorSystem.Processor;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author fankai
 */
@Slf4j
public class ActorTest {

    private static ActorSystem sys = new ActorSystem("test", 2, true);

    private static class StrProcess implements Processor<String> {

        private AtomicInteger cnt = new AtomicInteger();

        @Override
        public boolean process(String message, Actor<String> self) {
            log.info("hello {}", message);
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (cnt.get() == 5) {
                log.info("suspend {}", self.getName());
                self.suspend();
                new Thread(() -> {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    log.info("resume {}", self.getName());
                    sys.resume(self.getName());
                }).start();
            }
            cnt.incrementAndGet();
            return true;
        }
    }


    @Test
    public void test() throws InterruptedException {

        StrProcess processor = new StrProcess();
        for (int i = 0; i < 20; ++i) {
            sys.dispatch("actor1", "111", processor);
            sys.dispatch("actor2", "222", processor);
            sys.dispatch("actor3", "333", processor);
        }

        Thread.sleep(200);
        log.info("exit");
    }
}

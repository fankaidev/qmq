/*
 * Copyright 2018 Qunar, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qunar.tc.qmq.concurrent;

import com.google.common.collect.Maps;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qunar.tc.qmq.monitor.QMon;

import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by zhaohui.yu
 * 16/4/8
 */
public class ActorSystem {
    private static final Logger LOG = LoggerFactory.getLogger(ActorSystem.class);

    private static final int DEFAULT_QUEUE_SIZE = 10000;

    private final ConcurrentMap<String, Actor> actors;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger actorsCount;
    private final String name;

    public ActorSystem(String name) {
        this(name, Runtime.getRuntime().availableProcessors() * 4, true);
    }

    public ActorSystem(String name, int threads, boolean fair) {
        this.name = name;
        this.actorsCount = new AtomicInteger();
        BlockingQueue<Runnable> queue = fair ? new PriorityBlockingQueue<>() : new LinkedBlockingQueue<>();
        this.executor = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.MINUTES, queue, new NamedThreadFactory("actor-sys-" + name));
        this.actors = Maps.newConcurrentMap();
        QMon.dispatchersGauge(name, actorsCount::doubleValue);
        QMon.actorSystemQueueGauge(name, () -> (double) executor.getQueue().size());
    }

    public <E> void dispatch(String actorPath, E msg, Processor<E> processor) {
        Actor<E> actor = createOrGet(actorPath, processor);
        actor.dispatch(msg);
        schedule(actor, true);
    }

    public void suspend(String actorPath) {
        Actor actor = actors.get(actorPath);
        if (actor == null) return;

        actor.suspend();
    }

    public void resume(String actorPath) {
        Actor actor = actors.get(actorPath);
        if (actor == null) return;

        actor.resume();
        schedule(actor, false);
    }

    private <E> Actor<E> createOrGet(String actorPath, Processor<E> processor) {
        Actor<E> actor = actors.get(actorPath);
        if (actor != null) return actor;

        Actor<E> add = new Actor<>(this.name, actorPath, this, processor, DEFAULT_QUEUE_SIZE);
        Actor<E> old = actors.putIfAbsent(actorPath, add);
        if (old == null) {
            LOG.info("create actorSystem: {}", actorPath);
            actorsCount.incrementAndGet();
            return add;
        }
        return old;
    }

    private <E> boolean schedule(Actor<E> actor, boolean hasMessageHint) {
        if (!actor.canBeSchedule(hasMessageHint)) return false;
        if (actor.setAsScheduled()) {
            actor.submitTs = System.currentTimeMillis();
            this.executor.execute(actor);
            return true;
        }
        return false;
    }

    public interface Processor<T> {
        boolean process(T message, Actor<T> self);
    }

    public static class Actor<E> implements Runnable, Comparable<Actor> {
        private static final int Open = 0;
        private static final int Scheduled = 2;
        private static final int shouldScheduleMask = 3;
        private static final int shouldNotProcessMask = ~2;
        private static final int suspendUnit = 4;
        //每个actor至少执行的时间片
        private static final int QUOTA = 5;
        private static long statusOffset;

        static {
            try {
                statusOffset = Unsafe.instance.objectFieldOffset(Actor.class.getDeclaredField("status"));
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        final String systemName;
        final ActorSystem actorSystem;
        final BoundedNodeQueue<E> queue;
        final Processor<E> processor;
        @Getter
        private final String name;
        private long total;
        private volatile long submitTs;
        //通过Unsafe操作
        private volatile int status;

        Actor(String systemName, String name, ActorSystem actorSystem, Processor<E> processor, final int queueSize) {
            this.systemName = systemName;
            this.name = name;
            this.actorSystem = actorSystem;
            this.processor = processor;
            this.queue = new BoundedNodeQueue<>(queueSize);

            QMon.actorQueueGauge(systemName, name, () -> (double) queue.count());
        }

        boolean dispatch(E message) {
            return queue.add(message);
        }

        @Override
        public void run() {
            long start = System.currentTimeMillis();
            String old = Thread.currentThread().getName();
            try {
                Thread.currentThread().setName(systemName + "-" + name);
                if (shouldProcessMessage()) {
                    processMessages();
                }
            } finally {
                long duration = System.currentTimeMillis() - start;
                total += duration;
                QMon.actorProcessTime(name, duration);

                Thread.currentThread().setName(old);
                setAsIdle();
                this.actorSystem.schedule(this, false);
            }
        }

        void processMessages() {
            long deadline = System.currentTimeMillis() + QUOTA;
            while (true) {
                E message = queue.peek();
                if (message == null) return;
                boolean process = processor.process(message, this);
                if (!process) return;

                queue.pollNode();
                if (System.currentTimeMillis() >= deadline) {
                    LOG.info("deadline reached");
                    return;
                }
            }
        }

        final boolean shouldProcessMessage() {
            return (currentStatus() & shouldNotProcessMask) == 0;
        }

        private boolean canBeSchedule(boolean hasMessageHint) {
            int s = currentStatus();
            if (s == Open || s == Scheduled) return hasMessageHint || !queue.isEmpty();
            return false;
        }

        public final boolean resume() {
            while (true) {
                int s = currentStatus();
                int next = s < suspendUnit ? s : s - suspendUnit;
                if (updateStatus(s, next)) return next < suspendUnit;
            }
        }

        public final void suspend() {
            while (true) {
                int s = currentStatus();
                if (updateStatus(s, s + suspendUnit)) return;
            }
        }

        final boolean setAsScheduled() {
            while (true) {
                int s = currentStatus();
                if ((s & shouldScheduleMask) != Open) return false;
                if (updateStatus(s, s | Scheduled)) return true;
            }
        }

        final void setAsIdle() {
            while (true) {
                int s = currentStatus();
                if (updateStatus(s, s & ~Scheduled)) return;
            }
        }

        final int currentStatus() {
            return Unsafe.instance.getIntVolatile(this, statusOffset);
        }

        private boolean updateStatus(int oldStatus, int newStatus) {
            boolean success = Unsafe.instance.compareAndSwapInt(this, statusOffset, oldStatus, newStatus);
            if (success) {
                LOG.info("{} status {} -> {}", name, oldStatus, newStatus);
            }
            return success;
        }

        @Override
        public int compareTo(Actor o) {
            int result = Long.compare(total, o.total);
            return result == 0 ? Long.compare(submitTs, o.submitTs) : result;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Actor<?> actor = (Actor<?>) o;
            return Objects.equals(systemName, actor.systemName) &&
                    Objects.equals(name, actor.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(systemName, name);
        }
    }

    /**
     * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
     */

    static class Unsafe {
        public final static sun.misc.Unsafe instance;

        static {
            try {
                sun.misc.Unsafe found = null;
                for (Field field : sun.misc.Unsafe.class.getDeclaredFields()) {
                    if (field.getType() == sun.misc.Unsafe.class) {
                        field.setAccessible(true);
                        found = (sun.misc.Unsafe) field.get(null);
                        break;
                    }
                }
                if (found == null) throw new IllegalStateException("Can't find instance of sun.misc.Unsafe");
                else instance = found;
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }
    }
}

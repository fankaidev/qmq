package qunar.tc.qmq.concurrent;

import qunar.tc.qmq.concurrent.ActorSystem.Unsafe;

/**
 * Lock-free bounded non-blocking multiple-producer single-consumer queue based on the works of:
 * <p/>
 * Andriy Plokhotnuyk (https://github.com/plokhotnyuk)
 * - https://github.com/plokhotnyuk/actors/blob/2e65abb7ce4cbfcb1b29c98ee99303d6ced6b01f/src/test/scala/akka/dispatch/Mailboxes.scala
 * (Apache V2: https://github.com/plokhotnyuk/actors/blob/master/LICENSE)
 * <p/>
 * Dmitriy Vyukov's non-intrusive MPSC queue:
 * - http://www.1024cores.net/home/lock-free-algorithms/queues/non-intrusive-mpsc-node-based-queue
 * (Simplified BSD)
 */
@SuppressWarnings("serial")
class BoundedNodeQueue<T> {

    private final static long enqOffset, deqOffset;

    static {
        try {
            enqOffset = Unsafe.instance.objectFieldOffset(BoundedNodeQueue.class.getDeclaredField("_enqDoNotCallMeDirectly"));
            deqOffset = Unsafe.instance.objectFieldOffset(BoundedNodeQueue.class.getDeclaredField("_deqDoNotCallMeDirectly"));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private final int capacity;
    @SuppressWarnings("unused")
    private volatile Node<T> _enqDoNotCallMeDirectly;
    @SuppressWarnings("unused")
    private volatile Node<T> _deqDoNotCallMeDirectly;

    protected BoundedNodeQueue(final int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("AbstractBoundedNodeQueue.capacity must be >= 0");
        this.capacity = capacity;
        final Node<T> n = new Node<T>();
        setDeq(n);
        setEnq(n);
    }

    @SuppressWarnings("unchecked")
    private Node<T> getEnq() {
        return (Node<T>) Unsafe.instance.getObjectVolatile(this, enqOffset);
    }

    private void setEnq(Node<T> n) {
        Unsafe.instance.putObjectVolatile(this, enqOffset, n);
    }

    private boolean casEnq(Node<T> old, Node<T> nju) {
        return Unsafe.instance.compareAndSwapObject(this, enqOffset, old, nju);
    }

    @SuppressWarnings("unchecked")
    private Node<T> getDeq() {
        return (Node<T>) Unsafe.instance.getObjectVolatile(this, deqOffset);
    }

    private void setDeq(Node<T> n) {
        Unsafe.instance.putObjectVolatile(this, deqOffset, n);
    }

    private boolean casDeq(Node<T> old, Node<T> nju) {
        return Unsafe.instance.compareAndSwapObject(this, deqOffset, old, nju);
    }

    public final int count() {
        final Node<T> lastNode = getEnq();
        final int lastNodeCount = lastNode.count;
        return lastNodeCount - getDeq().count;
    }

    /**
     * @return the maximum capacity of this queue
     */
    public final int capacity() {
        return capacity;
    }

    // Possible TODO — impl. could be switched to addNode(new Node(value)) if we want to allocate even if full already
    public final boolean add(final T value) {
        for (Node<T> n = null; ; ) {
            final Node<T> lastNode = getEnq();
            final int lastNodeCount = lastNode.count;
            if (lastNodeCount - getDeq().count < capacity) {
                // Trade a branch for avoiding to create a new node if full,
                // and to avoid creating multiple nodes on write conflict á la Be Kind to Your GC
                if (n == null) {
                    n = new Node<T>();
                    n.value = value;
                }

                n.count = lastNodeCount + 1; // Piggyback on the HB-edge between getEnq() and casEnq()

                // Try to putPullLogs the node to the end, if we fail we continue loopin'
                if (casEnq(lastNode, n)) {
                    lastNode.setNext(n);
                    return true;
                }
            } else return false; // Over capacity—couldn't add the node
        }
    }

    public final boolean isEmpty() {
        return getEnq() == getDeq();
    }

    /**
     * Removes the first element of this queue if any
     *
     * @return the value of the first element of the queue, null if empty
     */
    public final T poll() {
        final Node<T> n = pollNode();
        return (n != null) ? n.value : null;
    }

    public final T peek() {
        Node<T> n = peekNode();
        return (n != null) ? n.value : null;
    }

    @SuppressWarnings("unchecked")
    protected final Node<T> peekNode() {
        for (; ; ) {
            final Node<T> deq = getDeq();
            final Node<T> next = deq.next();
            if (next != null || getEnq() == deq)
                return next;
        }
    }

    /**
     * Removes the first element of this queue if any
     *
     * @return the `Node` of the first element of the queue, null if empty
     */
    public final Node<T> pollNode() {
        for (; ; ) {
            final Node<T> deq = getDeq();
            final Node<T> next = deq.next();
            if (next != null) {
                if (casDeq(deq, next)) {
                    deq.value = next.value;
                    deq.setNext(null);
                    next.value = null;
                    return deq;
                } // else we retry (concurrent consumers)
            } else if (getEnq() == deq) return null; // If we got a null and head meets tail, we are empty
        }
    }

    public static class Node<T> {
        private final static long nextOffset;

        static {
            try {
                nextOffset = Unsafe.instance.objectFieldOffset(Node.class.getDeclaredField("_nextDoNotCallMeDirectly"));
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        protected T value;
        protected int count;
        @SuppressWarnings("unused")
        private volatile Node<T> _nextDoNotCallMeDirectly;

        @SuppressWarnings("unchecked")
        public final Node<T> next() {
            return (Node<T>) Unsafe.instance.getObjectVolatile(this, nextOffset);
        }

        protected final void setNext(final Node<T> newNext) {
            Unsafe.instance.putOrderedObject(this, nextOffset, newNext);
        }
    }
}

package com.lilingyan.reentrantreadwritelock;

import com.lilingyan.customreentrantnonfairlock.MyAbstractOwnableSynchronizer;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.locks.LockSupport;

/**
 * @Author: lilingyan
 * @Date 2019/3/9 15:37
 */
@SuppressWarnings("Duplicates")
public class MyAbstractQueuedSynchronizer extends MyAbstractOwnableSynchronizer {

    /**
     * 普通的双向链表
     */
    static final class Node {
        static final Node SHARED = new Node();
        static final Node EXCLUSIVE = null;

        //标识线程已处于结束状态
        static final int CANCELLED =  1;
        //后节点等待被唤醒状态
        static final int SIGNAL    = -1;
        //在condition队列中才会标记状态
        static final int CONDITION = -2;

        static final int PROPAGATE = -3;

        volatile int waitStatus;

        volatile Node prev;

        volatile Node next;

        //节点所对应的线程
        volatile Thread thread;

        /**
         * 独占 或者 共享 锁标记
         */
        Node nextWaiter;

        final boolean isShared() {
            return nextWaiter == SHARED;
        }

        /**
         * 获取前驱节点(就是前一个节点)
         * @return
         * @throws NullPointerException
         */
        final Node predecessor() throws NullPointerException {
            Node p = prev;
            if (p == null)
                throw new NullPointerException();
            else
                return p;
        }

        Node() {    // Used to establish initial head or SHARED marker
        }

        Node(Thread thread, Node mode) {     // Used by addWaiter
            this.nextWaiter = mode;
            this.thread = thread;
        }

        Node(Thread thread, int waitStatus) { // Used by Condition
            this.waitStatus = waitStatus;
            this.thread = thread;
        }
    }

    private transient volatile Node head;

    private transient volatile Node tail;

    private volatile int state;

    protected final int getState() {
        return state;
    }

    protected final void setState(int newState) {
        state = newState;
    }

    /**
     * cas添加尾节点
     * @param node
     * @return  返回前驱节点
     */
    private Node enq(final Node node) {
        for (;;) {
            Node t = tail;
            /**
             * 如果是空队列
             * 则初始化(添加了一个空节点！！！！！！！！！！！！！！！！！！！！！！！！！！！！！！！！  第一个是空的)
             * 头节点是一个全空节点
             * 因为CLH队列需要前一个节点标记后节点是否需要执行
             */
            if (t == null) { // Must initialize
                if (compareAndSetHead(new Node()))
                    tail = head;
            } else {
                //一直尝试添加尾节点
                node.prev = t;
                if (compareAndSetTail(t, node)) {
                    t.next = node;
                    return t;
                }
            }
        }
    }

    /**
     * 创建一个尾节点
     * @param mode
     * @return
     */
    private Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode);
        Node pred = tail;
        /**
         * 先尝试添加一下尾节点(快速添加)
         */
        if (pred != null) {
            node.prev = pred;
            if (compareAndSetTail(pred, node)) {
                pred.next = node;
                return node;
            }
        }
        /**
         * 如果快速添加失败
         * 则cas添加(重量)
         */
        enq(node);
        return node;
    }

    private void setHead(Node node) {
        head = node;
        node.thread = null;
        node.prev = null;
    }

    /**
     * 唤醒后继节点
     * @param node
     */
    private void unparkSuccessor(Node node) {
        int ws = node.waitStatus;
        /**
         * 已经去唤醒后继节点了
         * 原本当前节点是SIGNAL 或 PROPAGATE现在已经不需要了
         */
        if (ws < 0)
            compareAndSetWaitStatus(node, ws, 0);

        Node s = node.next;
        /**
         * 如果发现后继节点是取消的
         * 则遍历一遍
         * 然后把取消状态的节点都删掉
         *
         * 因为头结点的next是空的
         * 所以需要倒着遍历
         */
        if (s == null || s.waitStatus > 0) {
            s = null;
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    s = t;
        }
        /**
         * 如果有后继节点
         * 直接唤醒
         */
        if (s != null)
            LockSupport.unpark(s.thread);
    }

    /**
     *  取消节点
     * @param node
     */
    private void cancelAcquire(Node node) {
        if (node == null)
            return;

        //去掉线程引用
        node.thread = null;

        //找到非取消的前驱节点
        Node pred = node.prev;
        while (pred.waitStatus > 0)
            node.prev = pred = pred.prev;

        Node predNext = pred.next;

        //设置当前线程节点状态为取消
        node.waitStatus = Node.CANCELLED;

        /**
         * 如果是尾节点
         * 并且有用的前置节点成功替换掉了尾节点指针
         * (当然 尾节点也可能已经被别的线程设置了)
         */
        if (node == tail && compareAndSetTail(node, pred)) {
            /**
             * 执行到这里 说明上面的尾节点自己设置成功了
             * 但是可能还有两种情况
             * 1.没有新线程加入队列
             *      那现在的尾节点next指针置空成功
             * 2.如果有新线程在这个时候加入到了队列
             *      那下面这句话设置不成功也没关系
             */
            compareAndSetNext(pred, predNext, null);
        } else {
            int ws;
            /**
             * 如果是中间节点
             * 因为还有后节点(说明需要被唤醒)
             * 所以前节点要么是SIGNAL状态
             * 如果不是 则把他置为SIGNAL
             */
            if (pred != head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||   //如果当前节点还有后继节点要唤醒
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) && //-3
                    pred.thread != null) {  //pred.thread != null这个判断是因为 可能先后两个节点同时失效 然后都执行了上面的node.thread = null;
                Node next = node.next;
                if (next != null && next.waitStatus <= 0)
                /**
                 * 这里也可能执行失败
                 * 但是失败原因 要么是又有新节点挂载上去了
                 * 要么后节点已经被唤醒了
                 *
                 * 不管那种状态 这次失败都已经没有影响
                 */
                    compareAndSetNext(pred, predNext, next);
            } else {
                /**
                 * 如果前面都失败了
                 * 就去抢一下锁 会重新规整链表(效率低 不过最可靠)
                 */
                unparkSuccessor(node);
            }

            node.next = node; // help GC
        }
    }

    /**
     * 是否要阻塞
     * @param pred
     * @param node
     * @return
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus;
        /**
         * 如果前节点还在(可能是被唤醒重新抢了下锁)
         * 如果前节点已经是signal状态了，那当前必定是需要挂起的
         */
        if (ws == Node.SIGNAL)
            return true;
        if (ws > 0) {   //如果前驱节点是已经取消状态(执行完了)
            do {
                //清理所有取消的前驱节点
                //然后重新去外面抢一下锁
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            /**
             * 说明是新加的节点 ws == 0 或者共享状态的 -3
             * 所以把前节点的waitStatus标记置为后节点需要唤醒
             */
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    static void selfInterrupt() {
        Thread.currentThread().interrupt();
    }

    /**
     * 阻塞当前线程
     * @return
     */
    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this);
        /**
         * 线程到上面那句会一直阻塞
         * 有两种可能继续
         * 1 被唤醒
         * 2 会中断
         *  所以这里返回interrupted值 为外层做后续处理
         */
        return Thread.interrupted();
    }

    /**
     * 一直自旋(会wait   park)
     * 如果发现自己是最早进来的线程 而且抢到了锁
     * 则执行自己
     *
     * 进来 如果是第一个 先抢锁
     * 如果不是第一个 设置前节点为SIGNAL 并阻塞 等待被唤醒
     *
     * @param node
     * @param arg
     * @return
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            //等待过程中是否被中断过
            boolean interrupted = false;
            for (;;) {
                /**
                 * 执行到这里
                 * 说明当期线程是被唤醒的
                 * 则去抢锁
                 */
                final Node p = node.predecessor();
                /**
                 * 如果他已经是头节点了
                 * 就去尝试获取锁(即使是第一个节点，但可能前一个线程还没释放)
                 */
                if (p == head && tryAcquire(arg)) {
                    /**
                     * 因为头节点是空节点
                     * 这里是删掉了头节点
                     * 然后把当前节点(第二个)的内容都删了，也就变成了空节点
                     *
                     * 这里没有重置waitStatus状态 还是第二个节点的！！！！！！！！！！！！！！！！！！！！！！！！！！
                     */
                    setHead(node);
                    p.next = null; // help GC      //头结点的后指针是空的(包括自动创建的头结点)！！！！！！！！！！！！！
                    failed = false;
                    return interrupted;
                }
                /**
                 * 如果不是头节点  或者 抢锁失败
                 *
                 * 如果不是头节点
                 * 但是这个节点的前几个节点可能已经CANCELLED了
                 * 所以需要去判断一下还有没有前节点了(清理一下CANCELLED的节点)
                 */
                if (shouldParkAfterFailedAcquire(p, node) &&
                        //阻塞
                        parkAndCheckInterrupt())
                /**
                 * 如果parkAndCheckInterrupt()阻塞了
                 * 可以unpark或者Interrupt唤醒
                 * 如果是unpark唤醒了，就从新抢锁
                 * 如果是Interrupt唤醒，那就条件成立，标记一下interrupted状态
                 */
                    interrupted = true;
            }
        } finally {
            /**
             * 如果没有获取执行机会
             * 并且又退出了循环(出错)
             */
            if (failed)
                cancelAcquire(node);
        }
    }

    protected boolean tryAcquire(int arg) {
        throw new UnsupportedOperationException();
    }

    protected boolean tryRelease(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 获取锁
     * 如果失败
     * 则阻塞
     * @param arg
     */
    public final void acquire(int arg) {
        if (!tryAcquire(arg) &&
                //如果获取不到锁
                acquireQueued(addWaiter(Node.EXCLUSIVE), arg))  //加入等待队列
        /**
         * 如果获取锁成功
         * 则不会到
         * 如果获取锁失败 并且返回的Interrupt是true(说明是被主动Interrupt的)
         * 则会执行这句话
         */
            selfInterrupt();
    }

    /**
     * 释放一次锁(方法必定线程安全 不然抛错)
     * 如果重入完全释放 则叫醒后继节点
     * @param arg
     * @return
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) {  //完全释放(重入次数减光)
            //如果释放成功(重入的话 就是全部释放)
            Node h = head;
            if (h != null && h.waitStatus != 0) //如果等待队列不等于空
                //唤醒后继等待线程
                unparkSuccessor(h);
            return true;
        }
        return false;
    }

    /**
     * 队列中是否还有线程等待
     * @return
     */
    public final boolean hasQueuedThreads() {
        return head != tail;
    }

    /**
     * 当前线程前面是否有已经等待的线程
     *  (判断第一个是不是自己)
     * @return
     */
    public final boolean hasQueuedPredecessors() {
        Node t = tail;
        Node h = head;
        Node s;
        /**
         * 如果里面有节点
         * 并且第一个节点(第二个)不是自己
         */
        return h != t &&
                ((s = h.next) == null || s.thread != Thread.currentThread());
    }

    /**
     * 释放锁
     * @param node
     * @return
     */
    final int fullyRelease(Node node) {
        boolean failed = true;
        try {
            /**
             * 获取当前所有的重入次数
             * 并一次性释放
             */
            int savedState = getState();
            if (release(savedState)) {  //其实必定是会减光重入次数的
                failed = false;
                return savedState;
            } else {
                throw new IllegalMonitorStateException();
            }
        } finally {
            if (failed)
                node.waitStatus = Node.CANCELLED;
        }
    }

    final boolean isOnSyncQueue(Node node) {
        /**
         * 如果该节点的状态为CONDITION（该状态只能在CONDITION队列中出现，CLH队列中不会出现CONDITION状态）
         * CLH队列等待线程节点必定有一个前节点
         */
        if (node.waitStatus == Node.CONDITION || node.prev == null)
            return false;
        if (node.next != null) // If has successor, it must be on queue
            return true;
        /*
         * node.prev can be non-null, but not yet on queue because
         * the CAS to place it on queue can fail. So we have to
         * traverse from tail to make sure it actually made it.  It
         * will always be near the tail in calls to this method, and
         * unless the CAS failed (which is unlikely), it will be
         * there, so we hardly ever traverse much.
         */
        return findNodeFromTail(node);
    }

    /**
     * 遍历相等的节点
     * @param node
     * @return
     */
    private boolean findNodeFromTail(Node node) {
        Node t = tail;
        for (;;) {
            if (t == node)
                return true;
            if (t == null)
                return false;
            t = t.prev;
        }
    }

    /**
     * 将condition节点加入到CLH队列
     * @param node
     * @return
     */
    final boolean transferAfterCancelledWait(Node node) {
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            enq(node);
            return true;
        }
        /*
         * If we lost out to a signal(), then we can't proceed
         * until it finishes its enq().  Cancelling during an
         * incomplete transfer is both rare and transient, so just
         * spin.
         */
        while (!isOnSyncQueue(node))
            Thread.yield();
        return false;
    }

    protected boolean isHeldExclusively() {
        throw new UnsupportedOperationException();
    }

    final boolean transferForSignal(Node node) {
        /**
         * 当前有线程已经执行了
         * 可能是cancelled状态
         */
        if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
            return false;

        Node p = enq(node);
        int ws = p.waitStatus;
        /**
         * 如果CLH尾节点是cancelled的 或者在设置为signal时发生了抢占
         * 则让该线程自己去抢一下锁(acquireQueued)
         */
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            LockSupport.unpark(node.thread);
        return true;
    }

    public final boolean releaseShared(int arg) {
        if (tryReleaseShared(arg)) {    //全部重入释放成功
            doReleaseShared();
            return true;
        }
        return false;
    }

    protected boolean tryReleaseShared(int arg) {
        throw new UnsupportedOperationException();
    }

    public final void acquireShared(int arg) {
        if (tryAcquireShared(arg) < 0)  //尝试获取读锁
            doAcquireShared(arg);   //获取失败 则加入队列
    }

    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * 抢锁
     * 如果失败则阻塞
     * 被唤醒后 循环抢锁
     * @param arg
     */
    private void doAcquireShared(int arg) {
        final Node node = addWaiter(Node.SHARED);   //在CLH队列末尾创建一个共享模式节点
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {    //如果当前线程是第一个节点了
                    int r = tryAcquireShared(arg);  //抢锁
                    if (r >= 0) {       //抢锁成功
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        if (interrupted)    //如果在等待中被Interrupt 则标记一下
                            selfInterrupt();
                        failed = false;
                        return;             //不去下面等待了
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     *
     * @param node
     * @param propagate     传入 1
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // 这里原来的头
        setHead(node);  //这里会把当前node设置为头

        /**
         * 如果可用的信号量数量还大于0  则唤醒后继线程(少到不够为止)  这里没用
         *  在当前aqs中 使用这个方法的场景下 执行到这里 h不可能为null h == null 这个判断好像并没有用 为了严谨？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？
         *  当前节点后有需要唤醒的节点
         *  因为可能存在并发问题 上边已经把head移动到现在这个节点了
         *  如果刚刚又有节点加入到队列 那就是在当前节点之后了 所以也要判断下 当前节点后面是否有要唤醒的节点
         */
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
                (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            //如果后继节点为共享节点 唤醒
            if (s == null || s.isShared())
                doReleaseShared();
        }
    }

    /**
     * 存在并发
     */
    private void doReleaseShared() {
        /**
         * 如果有等待线程 所以唤醒一下第一个
         * 如果发现又有线程进入 那再继续唤醒
         */
        for (;;) {
            Node h = head;
            /**
             * 如果需要唤醒 直接唤醒
             * 如果还不能唤醒 标记一下 让后继节点不要阻塞
             */
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                if (ws == Node.SIGNAL) {        //如果SIGNAL 则回复标记为0  并唤醒后继节点
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                        continue;            // loop to recheck cases
                    unparkSuccessor(h);
                }
                /**
                 * 因为是并发的  所以在执行上面的判断前 可能队列还有 但判断时 被释放了
                 * 会进入这里
                 * 所以用了PROPAGATE 标识一下 该节点能被挂载后继节点 在shouldParkAfterFailedAcquire中标记前节点时
                 * 特意为它留了判断区间
                 * 在doAcquireShared的setHeadAndPropagate方法中 如果发现<0 也会继续唤醒(也包含了这个可能)
                 *
                 * 也可能刚有一个后继节点被加入 但还没标记头节点为SIGNAL时候
                 * 另一个线程执行了释放操作
                 * 所以会标记一下head节点为PROPAGATE，最后也是执行shouldParkAfterFailedAcquire()时，不阻塞
                 */
                else if (ws == 0 &&
                        !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    continue;                // loop on failed CAS
            }
            /**
             * 这到底有什么用？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？？
             */
            if (h == head)                   // loop if head changed
                break;
        }
    }


    /**
     * 在当前对象 偏移offset(需要修改的指针前面所有指针的长度)的地方
     *  (ep:c语言中，结构体内部指针可以靠偏移过前面的指针大小而被选中)
     * cas替换期望值
     */
    //=========================原子修改方法封装==========================
    private static final Unsafe unsafe;
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long waitStatusOffset;
    private static final long nextOffset;

    static {
        try {

            /**
             * unsafe不允许直接获取
             */
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);

            /**
             * 获取这些指针在jvm中的偏移大小
             */
            stateOffset = unsafe.objectFieldOffset
                    (MyAbstractQueuedSynchronizer.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                    (MyAbstractQueuedSynchronizer.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                    (MyAbstractQueuedSynchronizer.class.getDeclaredField("tail"));
            waitStatusOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("waitStatus"));
            nextOffset = unsafe.objectFieldOffset
                    (Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    /**
     * 原子替换state值
     * @param expect
     * @param update
     * @return
     */
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }
    /**
     * 原子替换头节点
     *  与下 compareAndSetTail(Node expect, Node update) 同理
     * @param update
     * @return
     */
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }
    /**
     * 原子替换尾节点
     * @param expect
     * @param update
     * @return
     */
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }
    private static final boolean compareAndSetWaitStatus(Node node,
                                                         int expect,
                                                         int update) {
        return unsafe.compareAndSwapInt(node, waitStatusOffset,
                expect, update);
    }
    private static final boolean compareAndSetNext(Node node,
                                                   Node expect,
                                                   Node update) {
        return unsafe.compareAndSwapObject(node, nextOffset, expect, update);
    }
    //=========================原子修改方法封装==========================

    public String toString() {
        int s = getState();
        String q  = hasQueuedThreads() ? "non" : "";
        return super.toString() +
                "[State = " + s + ", " + q + "empty queue]";
    }

}

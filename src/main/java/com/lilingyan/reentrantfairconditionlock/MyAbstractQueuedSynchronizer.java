package com.lilingyan.reentrantfairconditionlock;

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
        static final Node EXCLUSIVE = null;

        //标识线程已处于结束状态
        static final int CANCELLED =  1;
        //后节点等待被唤醒状态
        static final int SIGNAL    = -1;

        static final int CONDITION = -2;

        volatile int waitStatus;

        volatile Node prev;

        volatile Node next;

        //节点所对应的线程
        volatile Thread thread;

        /**
         * 独占 或者 共享 锁标记
         */
        Node nextWaiter;

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
         * 原本当前节点是SIGNAL 现在已经不需要了
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
     *
     * 顺带清理一下失效的节点
     * 这个方法很重要(在其它地方加入节点时，如果设置不了前节点的SIGNAL状态，可以让线程自己执行这个方法)
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
             * 说明是新加的节点 ws == 0
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
                acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
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
     * @return      如果true 说明是当前方法把node发到了CLH中 如果false 说明是signal()方法把node发送到了CLH中
     */
    final boolean transferAfterCancelledWait(Node node) {
        if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
            enq(node);
            return true;
        }
        /**
         * 执行到这里
         * 说明在这之前 已经有别的线程执行了signal()
         * 所以等待signal()完成
         */
        while (!isOnSyncQueue(node))
            Thread.yield(); //signal()还没完成 放一下cpu
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
         * 执行到这里 说明节点已经加在了CLH上
         * 如果CLH尾节点是cancelled的 或者在设置为signal时发生了抢占(别的节点也加上了)
         * 则让该线程自己去抢一下锁(acquireQueued)  如果是独占锁 就是在acquireQueued中 如果是共享锁 下一步也会执行到acquireQueued中
         * 这样不仅能清理失效的节点 还能保证让自己前节点设置为SIGNAL
         */
        if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
            LockSupport.unpark(node.thread);
        return true;
    }

    public class Condition{
        //头
        private transient Node firstWaiter;
        //尾
        private transient Node lastWaiter;

        /**
         * 这个方法外面已经获取过锁了
         * 所以必定是线程安全的
         * @throws InterruptedException
         */
        public final void await() throws InterruptedException {
            if (Thread.interrupted())
                throw new InterruptedException();
            //将该线程添加到CONDITION队列中
            Node node = addConditionWaiter();
            //释放全部重入次数并获取重入次数
            int savedState = fullyRelease(node);
            int interruptMode = 0;
            /**
             * 判断是否在CLH队列中(只有在CLH队列中才可能被唤醒并去抢锁)
             * 被signal()方法放到了CLH队列中
             */
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                /**
                 * 如果被中断了
                 * 则将当前节点加入到CLH队列中
                 * 然后跳出
                 */
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            //抢锁
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;
            /**
             * 如果正常signal()唤醒 是会把node.nextWaiter置空的
             * 如果不为空 说明当前线程interrupt的
             * 所以清理一下当前节点
             */
            if (node.nextWaiter != null)
                unlinkCancelledWaiters();
            if (interruptMode != 0)
            /**
             * 判断是直接抛错(可以直接拦截不执行后面的方法了)
             * 还是让后面方法自己去判断是否中断
             */
                reportInterruptAfterWait(interruptMode);
        }

        public final void signal() {
            //只有锁持有者 才可以释放condition
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignal(first);
        }

        public final void signalAll() {
            if (!isHeldExclusively())
                throw new IllegalMonitorStateException();
            Node first = firstWaiter;
            if (first != null)
                doSignalAll(first);
        }

        /**
         * 添加新condition节点尾部
         */
        private Node addConditionWaiter() {
            Node t = lastWaiter;
            /**
             * 如果尾节点状态不是CONDITION
             * 清理一下Cancelled
             *
             * 比如interrupted后 会被置为cancelled
             */
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null)
                firstWaiter = node;
            else
                t.nextWaiter = node;
            lastWaiter = node;
            return node;
        }

        /**
         * 从第一个节点开始向CLH链表插入(插入一个)
         * 直到成功 返回
         * @param first
         */
        private void doSignal(Node first) {
            do {
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                first.nextWaiter = null;
                /**
                 * 删除condition链表中的头节点
                 * 并把它发到CLH队列
                 */
            } while (!transferForSignal(first) &&
                    (first = firstWaiter) != null);
        }

        /**
         * 全部都插到CLH队列
         * @param first
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }

        /**
         *  遍历condition队列
         *  删掉里面Cancelled状态的节点
         */
        private void unlinkCancelledWaiters() {
            Node t = firstWaiter;
            Node trail = null;      //记录一下当前操作的前一个节点
            while (t != null) {
                Node next = t.nextWaiter;
                if (t.waitStatus != Node.CONDITION) {   //如果是Cancelled状态
                    t.nextWaiter = null;
                    if (trail == null)  //删除的是头节点
                        firstWaiter = next;
                    else
                        trail.nextWaiter = next;
                    if (next == null)   //删除了尾节点
                        lastWaiter = trail;
                }
                else
                    trail = t;
                t = next;
            }
        }

        private static final int REINTERRUPT =  1;
        private static final int THROW_IE    = -1;

        /**
         * 是否中断
         *
         * 如果在别的线程唤醒signal()之前Interrupt
         * 那直接在wait()这里抛错 可以判断执不执行了
         *
         * 如果在唤醒之后Interrupt
         * 那就让后面程序执行 在后面的程序里面去自己判断是否Interrupt
         *
         * 两个处理逻辑是不一样的
         *
         * @param node
         * @return  如果在signal()之前 返回THROW_IE  在signal()之后 返回REINTERRUPT
         */
        private int checkInterruptWhileWaiting(Node node) {
            return Thread.interrupted() ?
                    (transferAfterCancelledWait(node) ? THROW_IE : REINTERRUPT) :
                    0;
        }

        /**
         * 看checkInterruptWhileWaiting(Node node) 注释
         * @param interruptMode
         * @throws InterruptedException
         */
        private void reportInterruptAfterWait(int interruptMode)
                throws InterruptedException {
            if (interruptMode == THROW_IE)
                throw new InterruptedException();
            else if (interruptMode == REINTERRUPT)
                selfInterrupt();
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

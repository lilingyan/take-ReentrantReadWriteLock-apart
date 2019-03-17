package com.lilingyan.reentrantnonfairlock;

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

    static final long spinForTimeoutThreshold = 1000L;

    /**
     * cas添加尾节点
     * @param node
     * @return
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
         * 当前节点作为头结点了
         * 头结点必定是0
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
         */
        if (node == tail && compareAndSetTail(node, pred)) {
            /**
             * 则删掉
             */
            compareAndSetNext(pred, predNext, null);
        } else {
            int ws;
            /**
             * 如果是中间节点
             * 因为还有后节点(说明需要被唤醒)
             * 所以前节点要么是SIGNAL状态
             * 如果不是
             * 则把他置为SIGNAL
             */
            if (pred != head &&
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) && //-2 -3
                    pred.thread != null) {
                Node next = node.next;
                if (next != null && next.waitStatus <= 0)
                    compareAndSetNext(pred, predNext, next);
            } else {
                /**
                 * 如果他是头节点，并且出问题了
                 * 那就要唤醒一下后继节点 让他去抢一下锁
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
         * 如果前节点还在
         * 说明这个节点需要阻塞
         */
        if (ws == Node.SIGNAL)
            return true;
        if (ws > 0) {   //如果前驱节点是已经取消状态(执行完了)
            do {
                //清理所有取消的前驱节点
                //然后重新去外面的循环获取一下锁
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            /**
             * 挂载的这个节点是需要被执行的
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
     * 如果发现自己是最早进来的线程
     * 则执行自己
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
                 * 说明当期线程是被唤醒的(可能是第一个进来 也可能是挂起的线程被别的线程唤醒或者中断)
                 * 则去抢锁
                 */
                final Node p = node.predecessor();
                /**
                 * 如果他已经是头节点了
                 * 就去尝试获取锁
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
                 * 如果当前节点不是头结点
                 * 但是当前节点的前面节点可能已经执行完了
                 * 这里就要判断一下前面是否还有需要执行的线程
                 * 如果还有
                 * 则阻塞
                 * 如果没有
                 * 则继续抢锁
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
     * 释放一次锁
     * @param arg
     * @return
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) {
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

package com.lilingyan.reentrantreadwritelock;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

/**
 * @Author: lilingyan
 * @Date 2019/3/13 14:05
 */
@SuppressWarnings("Duplicates")
public class MyReentrantReadWriteLock {

    private final MyReentrantReadWriteLock.ReadLock readerLock;
    private final MyReentrantReadWriteLock.WriteLock writerLock;
    final Sync sync;

    public MyReentrantReadWriteLock() {
        sync =  new FairSync();
        readerLock = new ReadLock(this);
        writerLock = new WriteLock(this);
    }


    public MyReentrantReadWriteLock.WriteLock writeLock() { return writerLock; }
    public MyReentrantReadWriteLock.ReadLock  readLock()  { return readerLock; }

    abstract static class Sync extends MyAbstractQueuedSynchronizer {

        /**
         * int 32位
         * 以16 分高低
         * 高位存读锁重入
         * 低位存写锁重入
         */
        static final int SHARED_SHIFT   = 16;
        /**
         * 写锁的最小单位
         * 因为写重入记录在高位
         * 不好+1+1
         *  所以搞了这个 直接加上这个值 就是+1操作
         */
        static final int SHARED_UNIT    = (1 << SHARED_SHIFT);
        /**
         * 左移16 再减1
         * 0000000000000000 1111111111111111
         * 左右两边都不可能大于全1的  所以是最大值
         */
        static final int MAX_COUNT      = (1 << SHARED_SHIFT) - 1;
        /**
         * 左移16 再减1
         * 0000000000000000 1111111111111111
         *
         * 独占锁(读锁)的掩码(和ip掩码同理)
         * &上这个  那就只有低16位的值会有用了
         */
        static final int EXCLUSIVE_MASK = (1 << SHARED_SHIFT) - 1;

        /**
         * 无符号右移16位
         * ep: 0001 0000      ->     0001
         * 这样就获取了高位的数字(读锁的重入次数)
         * @param c
         * @return
         */
        static int sharedCount(int c)    { return c >>> SHARED_SHIFT; }

        /**
         * &上写锁的掩码
         * 就是写锁的重入次数
         * @param c
         * @return
         */
        static int exclusiveCount(int c) { return c & EXCLUSIVE_MASK; }

        /**
         * 线程重入计数器(用于读锁 因为写锁可能不同线程重入)
         */
        static final class HoldCounter {
            int count = 0;
            final long tid = getThreadId(Thread.currentThread());
        }

        /**
         * 线程第一次获取自己的重入计数器时，初始化一个
         */
        static final class ThreadLocalHoldCounter
                extends ThreadLocal<HoldCounter> {
            public HoldCounter initialValue() {
                return new HoldCounter();
            }
        }

        /**
         *  readLock 获取记录容器 ThreadLocal(ThreadLocal 的使用过程中当 HoldCounter.count == 0 时要进行 remove , 不然很有可能导致 内存的泄露)
         */
        private transient ThreadLocalHoldCounter readHolds;

        /**
         * 最后一次获取 readLock 的 HoldCounter 的缓存
         */
        private transient HoldCounter cachedHoldCounter;

        /**
         * 第一个获取读锁的线程和重入次数
         */
        private transient Thread firstReader = null;
        private transient int firstReaderHoldCount;

        Sync() {
            readHolds = new ThreadLocalHoldCounter();
            setState(getState()); // ensures visibility of readHolds
        }

        abstract boolean readerShouldBlock();

        abstract boolean writerShouldBlock();

        /**
         * 释放写锁
         * 因为写线程是独占的
         * 所以这里必定是线程安全
         * @param releases
         * @return
         */
        protected final boolean tryRelease(int releases) {
            if (!isHeldExclusively())   //只有锁的那个线程才允许释放
                throw new IllegalMonitorStateException();
            /**
             * 减重入次数
             * 如果减光了 就释放一下独占线程信息
             */
            int nextc = getState() - releases;
            boolean free = exclusiveCount(nextc) == 0;
            if (free)
                setExclusiveOwnerThread(null);
            setState(nextc);
            return free;
        }

        /**
         * 获取写锁
         * @param acquires
         * @return
         */
        protected final boolean tryAcquire(int acquires) {
            Thread current = Thread.currentThread();
            int c = getState();
            int w = exclusiveCount(c);
            if (c != 0) {   //如果锁已经被获取
                /**
                 * 如果读锁已经被获取了(c != 0&&w == 0)
                 * 不管是否是当前线程获取的 读不允许再获取写锁(ReentrantReadWriteLock不支持读锁升级)
                 *
                 * 或者如果有别的线程已经获取了写锁
                 * 那当前线程也获取失败
                 */
                if (w == 0 || current != getExclusiveOwnerThread())
                    return false;
                /**
                 * 执行到这里
                 * 说明当前线程是已经获取过了这个写锁
                 * 直接可以重入(已经线程安全)
                 */
                if (w + exclusiveCount(acquires) > MAX_COUNT)   //溢出检测
                    throw new Error("Maximum lock count exceeded");
                //写重入
                setState(c + acquires);
                return true;
            }
            /**
             * 执行到这里
             * 说明是第一次去获取写锁
             * 需要抢一下
             */
            if (writerShouldBlock() ||  //判断是否已经有等待了 如果没有 才去抢锁
                    !compareAndSetState(c, c + acquires))   //抢锁
                return false;
            setExclusiveOwnerThread(current);   //抢锁成功 记录线程信息
            return true;
        }

        /**
         * 释放读锁
         * @param unused
         * @return
         */
        protected final boolean tryReleaseShared(int unused) {
            Thread current = Thread.currentThread();
            if (firstReader == current) {
                /**
                 * 如果是第一个读锁线程
                 * 释放一下重入次数
                 */
                if (firstReaderHoldCount == 1)
                    firstReader = null;
                else
                    firstReaderHoldCount--;
            } else {
                /**
                 * 如果不是第一个线程
                 * 那判断下是否是最后一个获取读锁的线程
                 * 如果读不是 则重线程中获取一下该线程的重入次数计数器
                 */
                HoldCounter rh = cachedHoldCounter;
                if (rh == null || rh.tid != getThreadId(current))
                    rh = readHolds.get();       //获取一下当前线程的读重入次数
                int count = rh.count;
                if (count <= 1) {       //==1
                    readHolds.remove();         //如果0 删掉该线程的计数器(可能内存泄露？？？？ 感觉也没什么用)
                    if (count <= 0)     //不可能0
                        throw unmatchedUnlockException();
                }
                --rh.count;
            }
            for (;;) {                      //cas减读锁  如果还有重入 则-1
                int c = getState();
                int nextc = c - SHARED_UNIT;
                if (compareAndSetState(c, nextc))
                    return nextc == 0;              //读重入是否释放完
            }
        }

        private IllegalMonitorStateException unmatchedUnlockException() {
            return new IllegalMonitorStateException(
                    "attempt to unlock read lock, not locked by current thread");
        }

        /**
         * 获取读锁
         * @param unused
         * @return
         */
        protected final int tryAcquireShared(int unused) {
            Thread current = Thread.currentThread();
            int c = getState();
            /**
             * 写锁已经被别的线程拿到了
             * 那当前的线程读写都不能再获取锁
             */
            if (exclusiveCount(c) != 0 &&
                    getExclusiveOwnerThread() != current)
                return -1;
            //获取读重入次数
            int r = sharedCount(c);
            /**
             * 是否有前驱节点
             * 有 可能是有并发线程抢了读锁(后面的读写都会被等待)
             * 也可能是 已经有别的读锁被占了 后面的写锁还在等待
             *
             * 主要目的是 因为是公平的
             * 锁如果有写锁先获取 失败后等待了 那现在的读锁虽然可以获取 但为了公平 还是应该去等待
             */
            if (!readerShouldBlock() &&
                    r < MAX_COUNT &&        //防止溢出
                    compareAndSetState(c, c + SHARED_UNIT)) {   //这里是快速抢锁 如果有线程争抢失败 则进入下面的完整版抢锁过程
                if (r == 0) {   //如果读锁是第一次被抢到(快速记录一下该线程信息)
                    firstReader = current;
                    firstReaderHoldCount = 1;
                } else if (firstReader == current) {    //如果当前线程是读锁第一次被抢到的那个线程(记录一下重入次数)
                    firstReaderHoldCount++;
                } else {    //如果当前线程不是第一个获取读锁的线程
                    /**
                     * 前提 当前线程不是第一个获取读锁的线程
                     * 先判断一下该线程是否是最后一个获取读锁的线程(也就是上一次获取)
                     * 如果不是，则重线程中获取它的重入计数器
                     * 然后计数
                     */
                    HoldCounter rh = cachedHoldCounter;
                    if (rh == null || rh.tid != getThreadId(current))
                    /**
                     * 获取一下当前线程绑定的重入计数器
                     */
                        cachedHoldCounter = rh = readHolds.get();
                    else if (rh.count == 0) {
                        /**
                         * 如果当前线程已经获取过读锁并释放了它
                         * (在释放时，如果减光了重入次数，会把线程中的计数器删掉，
                         * 但cachedHoldCounter还会保留 所以上面的判断无法拦截 需要这里重新设定一下)
                         *
                         * 在写锁等待时获取读锁
                         * 也会被删掉计数器 然后扔进等待队列(然后没有别的读线程抢锁 它又去获取锁的时候)
                         */
                        readHolds.set(rh);
                    }
                    rh.count++; //记录一次重入
                }
                return 1;
            }
            return fullTryAcquireShared(current);
        }

        /**
         * tryAcquireShared()中cas抢锁失败
         * 进入这个完整版的抢锁
         * @param current
         * @return
         */
        final int fullTryAcquireShared(Thread current) {
            HoldCounter rh = null;
            for (;;) {
                int c = getState();
                /**
                 * 如果写锁被别的线程获取
                 * 直接返回失败
                 *
                 * 否则没写线程 或者 写线程就是自己(允许降级)
                 */
                if (exclusiveCount(c) != 0) {
                    if (getExclusiveOwnerThread() != current)
                        return -1;
                /**
                 * 是否有前驱节点
                 *
                 * 继上面tryAcquireShared()方法
                 * 如果是当前线程重入去获取读锁 哪怕已经有写锁等待了  那不管！！！ 任性
                 * 如果不是当前线程重入 那按照公平原则 丢去等待队列
                 */
                } else if (readerShouldBlock()) {
                    if (firstReader == current) {       //如果是重入
                        // assert firstReaderHoldCount > 0;
                    } else {    //不是第一个获取读锁的线程
                        if (rh == null) {
                            rh = cachedHoldCounter;
                            if (rh == null || rh.tid != getThreadId(current)) {
                                rh = readHolds.get();
                                if (rh.count == 0)
                                    readHolds.remove();
                            }
                        }
                        if (rh.count == 0)
                            return -1;
                    }
                }
                /**
                 * 逻辑与tryAcquireShared()一致
                 */
                if (sharedCount(c) == MAX_COUNT)    //溢出
                    throw new Error("Maximum lock count exceeded");
                if (compareAndSetState(c, c + SHARED_UNIT)) {   //抢锁
                    if (sharedCount(c) == 0) {      //原来是0 说明现在已经是第一个获取了
                        firstReader = current;
                        firstReaderHoldCount = 1;
                    } else if (firstReader == current) {
                        firstReaderHoldCount++;
                    } else {
                        /**
                         * 与tryAcquireShared()逻辑一致
                         */
                        if (rh == null)
                            rh = cachedHoldCounter;
                        if (rh == null || rh.tid != getThreadId(current))
                            rh = readHolds.get();
                        else if (rh.count == 0)
                            readHolds.set(rh);
                        rh.count++;
                        cachedHoldCounter = rh; // cache for release
                    }
                    return 1;
                }
            }
        }

        protected final boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

    }

    static final class FairSync extends Sync {
        final boolean writerShouldBlock() {
            return hasQueuedPredecessors();
        }
        final boolean readerShouldBlock() {
            return hasQueuedPredecessors();
        }
    }

    public static class ReadLock {
        private final Sync sync;

        protected ReadLock(MyReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        public void lock() {
            sync.acquireShared(1);
        }

        public void unlock() {
            sync.releaseShared(1);
        }
    }

    /**
     * 写锁其实和普通的重入锁逻辑差不多
     * 因为都是独占锁
     */
    public static class WriteLock{
        private final Sync sync;

        protected WriteLock(MyReentrantReadWriteLock lock) {
            sync = lock.sync;
        }

        public void lock() {
            sync.acquire(1);
        }

        public void unlock() {
            sync.release(1);
        }
    }

    static final long getThreadId(Thread thread) {
        return UNSAFE.getLongVolatile(thread, TID_OFFSET);
    }

    private static final Unsafe UNSAFE;
    private static final long TID_OFFSET;
    static {
        try {
            /**
             * unsafe不允许直接获取
             */
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
//            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> tk = Thread.class;
            TID_OFFSET = UNSAFE.objectFieldOffset
                    (tk.getDeclaredField("tid"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}

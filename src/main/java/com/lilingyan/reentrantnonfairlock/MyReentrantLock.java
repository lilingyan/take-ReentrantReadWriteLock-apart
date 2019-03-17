package com.lilingyan.reentrantnonfairlock;

/**
 * @Author: lilingyan
 * @Date 2019/3/9 16:12
 */
@SuppressWarnings("Duplicates")
public class MyReentrantLock{

    private final MyReentrantLock.Sync sync;

    public MyReentrantLock() {
        sync = new NonfairSync();
    }

    public void lock() {
        sync.lock();
    }

    public void unlock() {
        sync.release(1);
    }

    abstract static class Sync extends MyAbstractQueuedSynchronizer {

        abstract void lock();

        /**
         * 自己线程不会出现安全问题
         * @param releases
         * @return
         */
        protected final boolean tryRelease(int releases) {
            //减一下重入数量
            int c = getState() - releases;
            if (Thread.currentThread() != getExclusiveOwnerThread())
                throw new IllegalMonitorStateException();
            boolean free = false;
            /**
             * 如果重入数量减为0了
             * 说明线程释放了锁
             */
            if (c == 0) {
                free = true;
                setExclusiveOwnerThread(null);
            }
            setState(c);
            return free;
        }

        /**
         * 直接抢锁 不管队列
         * @param acquires
         * @return
         */
        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {
                if (compareAndSetState(0, acquires)) {
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            //重入
            else if (current == getExclusiveOwnerThread()) {
                int nextc = c + acquires;
                if (nextc < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }

    }

    static final class NonfairSync extends Sync {
        private static final long serialVersionUID = 7316153563782823691L;

        final void lock() {
            /**
             * 先直接去抢锁 所以不公平
             * 如果没抢到 则进入队列
             */
            if (compareAndSetState(0, 1))
                setExclusiveOwnerThread(Thread.currentThread());
            else
                acquire(1);
        }

        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }
    }

}

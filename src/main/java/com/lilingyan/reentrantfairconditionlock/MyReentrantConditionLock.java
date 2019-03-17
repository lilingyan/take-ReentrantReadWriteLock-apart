package com.lilingyan.reentrantfairconditionlock;

/**
 * @Author: lilingyan
 * @Date 2019/3/9 16:12
 */
@SuppressWarnings("Duplicates")
public class MyReentrantConditionLock {

    private final MyReentrantConditionLock.Sync sync;

    public MyReentrantConditionLock() {
        sync = new FairSync();
    }

    public void lock() {
        sync.lock();
    }

    public void unlock() {
        sync.release(1);
    }

    public MyAbstractQueuedSynchronizer.Condition newCondition() {
        return sync.newCondition();
    }

    abstract static class Sync extends MyAbstractQueuedSynchronizer {

        abstract void lock();

        /**
         * 自己线程不会出现安全问题
         * @param releases
         * @return  如果不是自己线程 或者重入次数没有减光 则返回false
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

        final MyAbstractQueuedSynchronizer.Condition newCondition() {
            return this.new Condition();
        }

    }

    static final class FairSync extends Sync {

        final void lock() {
            acquire(1);
        }

        /**
         * 尝试获取锁
         * @param acquires
         * @return  成功与否都返回
         */
        protected final boolean tryAcquire(int acquires) {
            final Thread current = Thread.currentThread();
            int c = getState();
            if (c == 0) {   //第一个获取锁的线程
                /**
                 *  如果等待队列没有线程(如果有，就阻塞自己，要让那个线程先执行)  、
                 *  并且通过cas赋值状态值成功(可能同时有多线程竞争)
                 *  如果赋值失败
                 *  则重新抢一遍锁
                 */
                if (!hasQueuedPredecessors() &&
                        compareAndSetState(0, acquires)) {
                    //记录当前锁获取线程
                    setExclusiveOwnerThread(current);
                    return true;
                }
            }
            /**
             * 重入判定
             * 如果当前锁已经被线程持有 或者已经有先来的等待线程
             * 则判断持有线程是否就是当前线程(支持重入)
             *
             * 因为一个线程不可能有竞争
             * 所以这里不需要线程安全机制
             */
            else if (current == getExclusiveOwnerThread()) {
                //记录重入次数
                int nextc = c + acquires;
                if (nextc < 0)  //高于31位 溢出
                    throw new Error("Maximum lock count exceeded");
                setState(nextc);
                return true;
            }
            return false;
        }

        /**
         * 是否是当前线程
         * @return
         */
        protected final boolean isHeldExclusively() {
            return getExclusiveOwnerThread() == Thread.currentThread();
        }

    }

}

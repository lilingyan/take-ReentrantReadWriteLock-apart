package com.lilingyan.customreentrantnonfairlock;

/**
 * 重入非公平锁
 * @Author: lilingyan
 * @Date 2019/3/9 15:01
 */
public class MyCustomReentrantNonFairLock extends MyAbstractOwnableSynchronizer {

    /**
     * 记录锁持有线程重复获取了几次锁
     */
    private volatile int state = 0;

    public synchronized void lock() throws InterruptedException {

        //先获取下当前线程
        final Thread current = Thread.currentThread();

        /**
         * 如果上一次抢到锁的线程不是当前线程
         * 则挂起
         */
        if (state != 0 && current != getExclusiveOwnerThread()) {
            wait();
        }
        /**
         * 如果抢到锁
         * 说明要么是第一次获取锁，要么是同一个线程多次获取这把锁
         * 记录获取线程信息和累加一下获取次数
         */
        state++;
        setExclusiveOwnerThread(current);
    }

    public synchronized void unlock() {
        /**
         * 释放一次获取次数
         * 如果为0了，说明当前线程重入栈已经全部被释放
         * 所以可以唤醒一个线程
         */
        if(--state == 0){
            notify();
        }
    }

}

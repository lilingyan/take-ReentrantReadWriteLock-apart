package com.lilingyan.lock;

/**
 * 普通锁，不能重入
 * @Author: lilingyan
 * @Date 2019/3/9 14:31
 */
public class MyLock{

    /**
     * 标记当前是否已有线程占用了这把锁
     */
    private volatile boolean isLocked = false;

    /**
     * 在这个监视器代码块中
     * 同时只可能有一个线程进入(与unlock()互斥)
     * @throws InterruptedException
     */
    public synchronized void lock() throws InterruptedException {
        /**
         * 如果发现已经有线程占用了锁
         * 则挂起当前线程
         */
        if (isLocked){
            wait();
        }
        /**
         * 能执行到这里
         * 说明
         * 要么是前面没有线程占有锁
         * 要么是第二次被唤醒，争抢到了jvm的锁
         */
        isLocked= true;
    }

    /**
     * 在这个监视器代码块中
     * 同时只可能有一个线程进入(lock()互斥)
     */
    public synchronized void unlock() {
        /**
         * 能进入的线程
         * 必定是锁的持有者
         * 直接释放标记
         * 并随机唤醒一个挂起线程
         */
        isLocked = false;
        notify();
    }

}

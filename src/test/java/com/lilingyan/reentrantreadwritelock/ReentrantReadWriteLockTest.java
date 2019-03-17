package com.lilingyan.reentrantreadwritelock;

import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @Author: lilingyan
 * @Date 2019/3/17 15:48
 */
public class ReentrantReadWriteLockTest {

    public static void main(String[] args) {

        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
        ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

        new Thread(){
            public void run() {
                System.out.println("抢写锁1");
                writeLock.lock();
                System.out.println(Thread.currentThread().getName()+"获取写锁1");
                ReentrantReadWriteLockTest.sleep(5000);
                writeLock.unlock();
                System.out.println("释放写锁1");
                while (true){}
            }
        }.start();
        new Thread(){
            public void run() {
                ReentrantReadWriteLockTest.sleep(1000);
                System.out.println("抢读锁1");
                readLock.lock();
                System.out.println(Thread.currentThread().getName()+"获取读锁1");
                while (true){}
            }
        }.start();
        new Thread(){
            public void run() {
                ReentrantReadWriteLockTest.sleep(2000);
                System.out.println("抢写锁2");
                writeLock.lock();
                System.out.println(Thread.currentThread().getName()+"获取写锁2");
                while (true){}
            }
        }.start();
        new Thread(){
            public void run() {
                ReentrantReadWriteLockTest.sleep(3000);
                System.out.println("抢读锁2");
                readLock.lock();
                System.out.println(Thread.currentThread().getName()+"获取读锁2");
                while (true){}
            }
        }.start();


    }

    public static void sleep(long l){
        try {
            Thread.sleep(l);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}

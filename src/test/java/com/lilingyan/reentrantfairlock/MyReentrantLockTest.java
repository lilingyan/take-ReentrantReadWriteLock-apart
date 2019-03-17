package com.lilingyan.reentrantfairlock;

import org.junit.Test;

/**
 * @Author: lilingyan
 * @Date 2019/3/9 14:39
 */
@SuppressWarnings("Duplicates")
public class MyReentrantLockTest {

    private MyReentrantLock lock = new MyReentrantLock();

    private class Sequence{

        private int value = 0;

        public int getNext() throws InterruptedException {
            lock.lock();
            Thread.sleep(100);
            value++;
            lock.unlock();
            return value;
        }

    }

    Sequence sequence = new Sequence();

    @Test
    public void increment(){
        new Thread(()->{
            try {
                while (true)
                    System.out.println(sequence.getNext());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
        new Thread(()->{
            try {
                while (true)
                    System.out.println(sequence.getNext());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();

        while (Thread.activeCount()>0){

        }

    }

    /**
     * 重入测试
     * @throws InterruptedException
     */
    @Test
    public void reentrant() throws InterruptedException {
        f();
    }

    private void f() throws InterruptedException {
        lock.lock();
        System.out.println("i'm f");
        s();
        lock.unlock();
    }

    private void s() throws InterruptedException {
        lock.lock();
        System.out.println("i'm s");
        lock.unlock();
    }

}
